package com.plantcare.bot.admin.broadcast.service;

import com.plantcare.bot.admin.broadcast.repository.AdminBroadcastRepository;
import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.bot.domain.AdminBroadcast;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.AudienceFilter;
import com.plantcare.bot.domain.enums.BroadcastStatus;
import com.plantcare.bot.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Сервис админ-рассылок (issue #60).
 *
 * <p>{@link #startBroadcast} — управляющий метод:
 *   1) синхронно проверяет «нет ли уже RUNNING» (защита от двойного запуска);
 *   2) создаёт запись {@code admin_broadcasts} со status=RUNNING и total_count;
 *   3) отправляет реальный цикл отправки в {@code broadcastExecutor} (singleton).
 *
 * <p>Цикл ({@link #runBroadcastLoop}):
 *   - SendMessage с пауза 50 мс между отправками (≤20 msg/sec, под лимит 30/sec);
 *   - 429 → sleep(retry_after+1), retry той же отправки один раз;
 *   - Forbidden (403) → юзер помечается blocked, счётчик blocked_count++;
 *   - Прочие ошибки → failed_count++, стектрейс в логи;
 *   - Каждые N итераций инкрементальный bump счётчиков (чтобы polling видел прогресс).
 *
 * <p>На остановку проверяет {@code status} перед каждой следующей отправкой —
 * если админ нажал «Остановить», цикл выходит без дальнейших отправок.
 */
@Service
@Slf4j
public class AdminBroadcastService {

    /** Пауза между отправками для соблюдения лимита Telegram (30/sec). */
    public static final long SEND_DELAY_MS = 50;

    /** Сколько раз пытаться повторить отправку при 429. */
    public static final int RATE_LIMIT_RETRIES = 1;

    /** Пакет, после которого пишем прогресс в БД (для polling). */
    public static final int FLUSH_EVERY = 5;

    private final AdminBroadcastRepository broadcastRepository;
    private final AudienceResolver audienceResolver;
    private final TelegramClientProvider telegramClientProvider;
    private final UserRepository userRepository;
    private final ExecutorService broadcastExecutor;

    /**
     * Каждый DB-апдейт в фоновом цикле оборачиваем коротким TX. @Transactional
     * на методах сервиса не работает при self-invocation (this.flushCounters
     * внутри runBroadcastLoop не идёт через Spring proxy), а в фоновой таске
     * мы как раз только self-invocation и используем.
     */
    private final TransactionTemplate txTemplate;

    public AdminBroadcastService(
            AdminBroadcastRepository broadcastRepository,
            AudienceResolver audienceResolver,
            TelegramClientProvider telegramClientProvider,
            UserRepository userRepository,
            @Qualifier("broadcastExecutor") ExecutorService broadcastExecutor,
            PlatformTransactionManager transactionManager
    ) {
        this.broadcastRepository = broadcastRepository;
        this.audienceResolver = audienceResolver;
        this.telegramClientProvider = telegramClientProvider;
        this.userRepository = userRepository;
        this.broadcastExecutor = broadcastExecutor;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Запуск новой рассылки. Возвращает результат с id-ом созданного broadcast'а
     * или причиной отказа (в т.ч. «другой broadcast ещё идёт»).
     *
     * <p>Текст всегда проходит через {@link #escapeMarkdownV2(String)} —
     * это гарантия что сообщение уйдёт в Telegram (невалидный Markdown V2
     * приводит к 400). Цена: пользовательский Markdown не рендерится
     * (звёздочки и подчёркивания превращаются в литералы). Это осознанный
     * trade-off в пользу безопасности доставки.
     */
    @Transactional
    public StartResult startBroadcast(
            String text,
            AudienceFilter filter,
            Set<String> timezones,
            String adminName
    ) {
        if (text == null || text.isBlank()) {
            return StartResult.invalid("Текст пустой");
        }
        if (text.length() > 4000) {
            return StartResult.invalid("Текст длиннее 4000 символов");
        }

        // 1) Защита от двойного запуска.
        Optional<AdminBroadcast> running = broadcastRepository
                .findFirstByStatus(BroadcastStatus.RUNNING);
        if (running.isPresent()) {
            return StartResult.busy(running.get().getId());
        }

        // 2) Резолвим аудиторию ДО создания записи — чтобы корректно
        // выставить total_count и понять есть ли вообще кому слать.
        List<User> audience = audienceResolver.resolve(filter, timezones);
        if (audience.isEmpty()) {
            return StartResult.invalid("В выбранной аудитории 0 юзеров");
        }

        String finalText = escapeMarkdownV2(text);

        AdminBroadcast broadcast = AdminBroadcast.builder()
                .messageText(finalText)
                .audienceFilter(filter)
                .totalCount(audience.size())
                .sentCount(0).failedCount(0).blockedCount(0)
                .status(BroadcastStatus.RUNNING)
                .sentBy(adminName)
                .createdAt(LocalDateTime.now())
                .build();
        broadcast = broadcastRepository.save(broadcast);

        // 3) В лог НЕ кладём текст целиком (issue: может быть конфиденциальным).
        log.info("Broadcast {} started by {}: filter={}, audience={}, length={}",
                broadcast.getId(), adminName, filter, audience.size(), finalText.length());

        // 4) Снимок id+text для async-таски — Hibernate-сессия закроется на выходе
        // из этого метода, нельзя тащить detached-entity внутрь executor'а.
        final Long broadcastId = broadcast.getId();
        final List<Long> chatIds = audience.stream().map(User::getTelegramChatId).toList();
        broadcastExecutor.submit(() -> runBroadcastLoop(broadcastId, finalText, chatIds));

        return StartResult.started(broadcastId);
    }

    /**
     * Запрос на остановку. Возвращает {@code true}, если broadcast действительно
     * был RUNNING (и стал STOPPED). Если уже завершился — {@code false}.
     * Реальный цикл проверяет статус сам перед каждой следующей итерацией.
     */
    @Transactional
    public boolean requestStop(Long broadcastId) {
        int updated = broadcastRepository.requestStop(
                broadcastId, BroadcastStatus.RUNNING, BroadcastStatus.STOPPED);
        if (updated > 0) {
            log.info("Broadcast {} stop requested", broadcastId);
        }
        return updated > 0;
    }

    /** Текущий снимок прогресса (для polling из HTMX). */
    @Transactional(readOnly = true)
    public Optional<AdminBroadcast> snapshot(Long broadcastId) {
        return broadcastRepository.findById(broadcastId);
    }

    // =================================================================
    // Async loop — выполняется в broadcastExecutor (single thread)
    // =================================================================

    void runBroadcastLoop(Long broadcastId, String text, List<Long> chatIds) {
        int sentBatch = 0, failedBatch = 0, blockedBatch = 0;
        int processed = 0;
        int totalSent = 0, totalFailed = 0, totalBlocked = 0;

        try {
            for (Long chatId : chatIds) {
                // Перед каждой итерацией проверяем не нажал ли админ «Остановить».
                // Чтение SELECT-ом тоже требует TX из background-потока — оборачиваем.
                Optional<AdminBroadcast> currentOpt = txTemplate.execute(status ->
                        broadcastRepository.findById(broadcastId));
                if (currentOpt == null || currentOpt.isEmpty()
                        || currentOpt.get().getStatus() != BroadcastStatus.RUNNING) {
                    log.info("Broadcast {} aborted at item {}/{}: status={}",
                            broadcastId, processed, chatIds.size(),
                            currentOpt == null ? null
                                    : currentOpt.map(AdminBroadcast::getStatus).orElse(null));
                    break;
                }

                SendOutcome outcome = sendOne(chatId, text);
                switch (outcome) {
                    case SENT      -> { sentBatch++; totalSent++; }
                    case BLOCKED   -> { blockedBatch++; totalBlocked++; }
                    case FAILED    -> { failedBatch++; totalFailed++; }
                }
                processed++;

                // Периодический flush счётчиков в БД — polling это увидит.
                if (processed % FLUSH_EVERY == 0) {
                    flushCounters(broadcastId, sentBatch, failedBatch, blockedBatch);
                    sentBatch = failedBatch = blockedBatch = 0;
                }

                sleepBetween(SEND_DELAY_MS);
            }

            // Финальный flush + терминальный статус
            if (sentBatch != 0 || failedBatch != 0 || blockedBatch != 0) {
                flushCounters(broadcastId, sentBatch, failedBatch, blockedBatch);
            }

            BroadcastStatus finalStatus = finalStatusFor(broadcastId);
            final BroadcastStatus statusToWrite = finalStatus;
            txTemplate.executeWithoutResult(status ->
                    broadcastRepository.markFinished(broadcastId, statusToWrite, LocalDateTime.now()));
            log.info("Broadcast {} finished: status={}, sent={}, blocked={}, failed={}",
                    broadcastId, finalStatus, totalSent, totalBlocked, totalFailed);

        } catch (Exception e) {
            log.error("Broadcast {} crashed: {}", broadcastId, e.getMessage(), e);
            // Финальный flush, что успели
            if (sentBatch != 0 || failedBatch != 0 || blockedBatch != 0) {
                try { flushCounters(broadcastId, sentBatch, failedBatch, blockedBatch); }
                catch (Exception ignored) {}
            }
            try {
                txTemplate.executeWithoutResult(status ->
                        broadcastRepository.markFinished(
                                broadcastId, BroadcastStatus.FAILED, LocalDateTime.now()));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Итоговый статус: DONE если цикл прошёл от и до, STOPPED если админ остановил
     * (статус уже изменён через requestStop), FAILED никогда не сюда — это исключения.
     */
    private BroadcastStatus finalStatusFor(Long id) {
        BroadcastStatus current = txTemplate.execute(status ->
                broadcastRepository.findById(id)
                        .map(AdminBroadcast::getStatus)
                        .orElse(BroadcastStatus.FAILED));
        if (current == BroadcastStatus.RUNNING) return BroadcastStatus.DONE;
        return current;  // STOPPED оставляем как есть
    }

    /**
     * Инкрементальный flush в БД. Короткая TX — каждые FLUSH_EVERY итераций.
     * НЕ @Transactional: метод вызывается self-invocation из runBroadcastLoop,
     * Spring proxy там не работает; используем явный TransactionTemplate.
     */
    void flushCounters(Long id, int sent, int failed, int blocked) {
        txTemplate.executeWithoutResult(status ->
                broadcastRepository.bumpCounters(id, sent, failed, blocked));
    }

    /**
     * Одна отправка с обработкой 429/403/прочих. Возвращает enum результата;
     * сам блокировку юзера в БД делает здесь же — это решение «по факту получения 403».
     */
    private SendOutcome sendOne(Long chatId, String text) {
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                // MarkdownV2 + предварительный escape гарантируют что сообщение
                // всегда валидно с точки зрения Telegram и точно доставится.
                // V1 («Markdown») ругается на свой набор символов и сделал бы
                // escapeMarkdownV2 несинхронным с парсером.
                .parseMode("MarkdownV2")
                .build();

        for (int attempt = 0; attempt <= RATE_LIMIT_RETRIES; attempt++) {
            try {
                telegramClientProvider.getTelegramClient().execute(msg);
                return SendOutcome.SENT;
            } catch (TelegramApiException e) {
                String em = String.valueOf(e.getMessage());
                if (em.contains("403") || em.toLowerCase().contains("blocked")) {
                    markUserBlocked(chatId);
                    return SendOutcome.BLOCKED;
                }
                if (em.contains("429")) {
                    long retryAfter = extractRetryAfter(em);
                    log.warn("Rate-limited (429) on chat {}: retry after {}s",
                            chatId, retryAfter);
                    sleepBetween((retryAfter + 1) * 1000L);
                    continue;  // следующий attempt
                }
                log.warn("SendMessage failed for chat {}: {}", chatId, em);
                return SendOutcome.FAILED;
            }
        }
        return SendOutcome.FAILED;
    }

    /**
     * Помечаем юзера blocked после 403 от Telegram. Тоже из background-таски,
     * так что @Transactional не сработал бы — используем txTemplate.
     */
    void markUserBlocked(Long chatId) {
        txTemplate.executeWithoutResult(status -> {
            userRepository.findByTelegramChatId(chatId).ifPresent(u -> {
                if (!u.isBlocked()) {
                    u.setBlocked(true);
                    userRepository.save(u);
                    log.info("User {} marked blocked (broadcast detected 403)", chatId);
                }
            });
        });
    }

    /**
     * Достаёт {@code retry_after_seconds} из сообщения исключения. Telegram-bots
     * библиотека форматирует ошибку строкой: пытаемся вытащить число после
     * «retry after». Если не нашли — fallback 1 секунда.
     */
    private static long extractRetryAfter(String errorMessage) {
        if (errorMessage == null) return 1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("retry\\s*after\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(errorMessage);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    private static void sleepBetween(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Минимальный escape Markdown V2: убираем основные мета-символы, чтобы любой
     * текст уйел корректно (Telegram отвергает невалидный MD).
     */
    public static String escapeMarkdownV2(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            if (c == '_' || c == '*' || c == '[' || c == ']' || c == '('
                    || c == ')' || c == '~' || c == '`' || c == '>' || c == '#'
                    || c == '+' || c == '-' || c == '=' || c == '|' || c == '{'
                    || c == '}' || c == '.' || c == '!' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // =================================================================
    // DTOs
    // =================================================================

    public enum SendOutcome { SENT, BLOCKED, FAILED }

    public record StartResult(Status status, Long broadcastId, String reason) {
        public enum Status { STARTED, BUSY, INVALID }

        public static StartResult started(Long id)          { return new StartResult(Status.STARTED, id, null); }
        public static StartResult busy(Long otherId)        { return new StartResult(Status.BUSY, otherId, null); }
        public static StartResult invalid(String reason)    { return new StartResult(Status.INVALID, null, reason); }

        public boolean isStarted() { return status == Status.STARTED; }
        public boolean isBusy()    { return status == Status.BUSY; }
        public boolean isInvalid() { return status == Status.INVALID; }
    }
}
