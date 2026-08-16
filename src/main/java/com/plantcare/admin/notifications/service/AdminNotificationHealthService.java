package com.plantcare.admin.notifications.service;

import com.plantcare.admin.notifications.dto.ChannelHealthDto;
import com.plantcare.admin.notifications.dto.ChannelSeriesDto;
import com.plantcare.admin.notifications.dto.ErrorCodeCountDto;
import com.plantcare.admin.notifications.dto.HourlyPointDto;
import com.plantcare.admin.notifications.dto.NotificationHealthDto;
import com.plantcare.admin.notifications.dto.ProblemUserDto;
import com.plantcare.admin.notifications.repository.AdminNotificationHealthRepository;
import com.plantcare.core.domain.enums.DeliveryChannel;
import com.plantcare.core.service.PushSender;
import com.plantcare.core.service.PushSender.PushResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Логика health-дашборда каналов уведомлений (issue #95).
 *
 * <p>Каналов ровно два — {@link DeliveryChannel#TELEGRAM} и {@link DeliveryChannel#PUSH}.
 * Отдельного APNs-канала нет: по ADR-016 push единый (FCM маршрутизирует на APNs
 * сам), см. {@code FcmPushSender}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationHealthService {

    /** Окно наблюдения по умолчанию — сутки (AC issue #95). */
    public static final int DEFAULT_HOURS = 24;
    /** Разумные границы окна: меньше часа считать нечего, больше недели — не дашборд. */
    public static final int MIN_HOURS = 1;
    public static final int MAX_HOURS = 168;

    /** Сколько неудач подряд делает юзера «проблемным» (AC issue #95: «FAILED &gt;3 раз подряд»). */
    public static final int PROBLEM_MIN_CONSECUTIVE_FAILURES = 3;

    public static final int TOP_ERRORS_LIMIT = 15;
    public static final int PROBLEM_USERS_LIMIT = 50;

    /** Значение фильтра «без фильтра» — оно же дефолт в контроллере и шаблоне. */
    public static final String CHANNEL_FILTER_ALL = "all";

    /** Текст тестового уведомления — админ должен узнать его в чате/на телефоне. */
    public static final String TEST_NOTIFICATION_TITLE = "Plants Care";
    public static final String TEST_NOTIFICATION_TEXT =
            "🔧 Тестовое уведомление из админки Plants Care. Если ты это видишь — канал жив.";

    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT);

    private final AdminNotificationHealthRepository repository;
    private final PushSender pushSender;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Собрать снапшот дашборда.
     *
     * @param hours   окно в часах (клампится в [{@value #MIN_HOURS}, {@value #MAX_HOURS}])
     * @param channel фильтр по каналу для «топ ошибок» и «проблемных юзеров»;
     *                {@code null} / {@value #CHANNEL_FILTER_ALL} — без фильтра.
     *                Карточки и график всегда по всем каналам: смысл дашборда —
     *                сравнивать каналы между собой.
     */
    public NotificationHealthDto loadHealth(int hours, String channel) {
        int safeHours = clampHours(hours);
        String safeChannel = normalizeChannel(channel);
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofHours(safeHours));

        List<ChannelHealthDto> channels = withAllChannels(repository.summarizeByChannel(since));
        List<HourlyPointDto> points = repository.hourlyBuckets(since);
        List<ErrorCodeCountDto> topErrors = repository.topErrorCodes(since, safeChannel, TOP_ERRORS_LIMIT);
        List<ProblemUserDto> problemUsers = repository.findProblemUsers(
                since, safeChannel, PROBLEM_MIN_CONSECUTIVE_FAILURES, PROBLEM_USERS_LIMIT);

        List<String> labels = chartLabels(points);
        List<ChannelSeriesDto> series = chartSeries(points);

        return new NotificationHealthDto(
                safeHours,
                safeChannel == null ? CHANNEL_FILTER_ALL : safeChannel,
                now,
                channels,
                labels,
                series,
                chartDataJson(labels, series),
                topErrors,
                problemUsers);
    }

    /**
     * Сериализовать данные графика для Chart.js. Шаблон отдаёт результат в
     * {@code <script type="application/json">}, поэтому JSON строим Jackson'ом,
     * а не конкатенацией — экранирование не наша забота.
     *
     * <p>Сломанная сериализация не должна ронять страницу: график — не главное,
     * ради него терять карточки и таблицы незачем.
     */
    private String chartDataJson(List<String> labels, List<ChannelSeriesDto> series) {
        try {
            return objectMapper.writeValueAsString(Map.of("labels", labels, "series", series));
        } catch (Exception e) {
            log.warn("Failed to serialize notification health chart data: {}", e.getMessage());
            return "{\"labels\":[],\"series\":[]}";
        }
    }

    /** Только каналы в алерте — для красной плашки на главной странице админки. */
    public List<ChannelHealthDto> alertingChannels(int hours) {
        Instant since = clock.instant().minus(Duration.ofHours(clampHours(hours)));
        return withAllChannels(repository.summarizeByChannel(since)).stream()
                .filter(ChannelHealthDto::alerting)
                .toList();
    }

    /**
     * «Отписать токен»: снести все push-устройства юзера. Применимо, когда push
     * стабильно фейлится — токен мёртв, а мы долбимся в него каждый тик.
     *
     * @return сколько записей устройств удалено
     */
    public int pruneDevices(long userId, String adminName) {
        int deleted = repository.deleteDevices(userId);
        log.info("Admin action PRUNE_DEVICES: user_id={}, deleted={}, admin={}", userId, deleted, adminName);
        return deleted;
    }

    /**
     * «Отправить тестовое уведомление» в push-канал: шлём на все устройства юзера
     * и возвращаем сводку исходов.
     *
     * <p>Вызов внешнего API — метод намеренно НЕ транзакционный (CLAUDE.md:
     * никаких внешних вызовов внутри открытой транзакции).
     */
    public TestPushResult sendTestPush(long userId, String adminName) {
        List<String> tokens = repository.findPushTokens(userId);
        if (tokens.isEmpty()) {
            return new TestPushResult(0, 0, 0);
        }
        int sent = 0;
        int stale = 0;
        int failed = 0;
        for (String token : tokens) {
            PushResult result = pushSender.send(token, TEST_NOTIFICATION_TITLE, TEST_NOTIFICATION_TEXT);
            switch (result) {
                case SENT -> sent++;
                case STALE_TOKEN -> stale++;
                case FAILED -> failed++;
            }
        }
        log.info("Admin action TEST_PUSH: user_id={}, sent={}, stale={}, failed={}, admin={}",
                userId, sent, stale, failed, adminName);
        return new TestPushResult(sent, stale, failed);
    }

    // ==================================================================
    // Внутренняя кухня
    // ==================================================================

    static int clampHours(int hours) {
        return Math.min(MAX_HOURS, Math.max(MIN_HOURS, hours));
    }

    /** {@code null} / {@value #CHANNEL_FILTER_ALL} / мусор → без фильтра. */
    static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank() || CHANNEL_FILTER_ALL.equalsIgnoreCase(channel)) {
            return null;
        }
        for (DeliveryChannel known : DeliveryChannel.values()) {
            if (known.name().equalsIgnoreCase(channel)) {
                return known.name();
            }
        }
        return null;
    }

    /**
     * Дозаполнить сводку каналами, по которым за окно не было ни одной попытки.
     * Пустая карточка — тоже сигнал: «push не слали сутки» видно только если
     * карточка на месте.
     */
    private static List<ChannelHealthDto> withAllChannels(List<ChannelHealthDto> found) {
        Map<String, ChannelHealthDto> byChannel = new LinkedHashMap<>();
        for (DeliveryChannel channel : DeliveryChannel.values()) {
            byChannel.put(channel.name(), ChannelHealthDto.empty(channel.name()));
        }
        for (ChannelHealthDto dto : found) {
            byChannel.put(dto.channel(), dto);
        }
        return List.copyOf(byChannel.values());
    }

    private List<String> chartLabels(List<HourlyPointDto> points) {
        return sortedBuckets(points).stream()
                .map(bucket -> HOUR_LABEL.format(bucket.atZone(ZoneId.of("UTC"))))
                .toList();
    }

    /**
     * Пивот сырых точек в ряды по каналам: каждый ряд выровнен по общему набору
     * часов, отсутствующие часы забиты нулями (иначе Chart.js съедет по X).
     */
    private List<ChannelSeriesDto> chartSeries(List<HourlyPointDto> points) {
        List<Instant> buckets = sortedBuckets(points);
        Map<String, Map<Instant, Long>> byChannel = new LinkedHashMap<>();
        for (DeliveryChannel channel : DeliveryChannel.values()) {
            byChannel.put(channel.name(), new LinkedHashMap<>());
        }
        for (HourlyPointDto point : points) {
            byChannel.computeIfAbsent(point.channel(), key -> new LinkedHashMap<>())
                    .put(point.bucket(), point.total());
        }

        List<ChannelSeriesDto> series = new ArrayList<>(byChannel.size());
        byChannel.forEach((channel, values) -> {
            List<Long> counts = buckets.stream()
                    .map(bucket -> values.getOrDefault(bucket, 0L))
                    .toList();
            series.add(new ChannelSeriesDto(channel, counts));
        });
        return List.copyOf(series);
    }

    /** Уникальные часы окна по возрастанию — общая ось X для всех рядов. */
    private static List<Instant> sortedBuckets(List<HourlyPointDto> points) {
        return points.stream()
                .map(HourlyPointDto::bucket)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /**
     * Исход тестовой push-рассылки на устройства одного юзера.
     *
     * @param sent   принято провайдером
     * @param stale  провайдер сказал «токен мёртв»
     * @param failed прочие ошибки доставки
     */
    public record TestPushResult(int sent, int stale, int failed) {

        public int total() {
            return sent + stale + failed;
        }
    }
}
