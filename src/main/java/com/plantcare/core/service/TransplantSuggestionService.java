package com.plantcare.core.service;

import com.plantcare.core.config.TransplantSuggestionProperties;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantEvent;
import com.plantcare.core.domain.TransplantSupplySuggestion;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.PlantEventType;
import com.plantcare.core.domain.enums.SuggestionStatus;
import com.plantcare.core.repository.PlantEventRepository;
import com.plantcare.core.repository.PlantRepository;
import com.plantcare.core.repository.TransplantSupplySuggestionRepository;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Подсказка расходников перед предстоящей пересадкой (issue #141).
 *
 * <p>«Предстоящая пересадка» в системе не хранится отдельной датой
 * (ADR — вариант A): она вычисляется как дата последней TRANSPLANT-записи в
 * журнале растения (issue #76) плюс {@link TransplantSuggestionProperties#intervalMonths()}.
 * Подсказка появляется только для растений, у которых уже есть TRANSPLANT в
 * истории.
 *
 * <p>Telegram-вызов остаётся за рамками сервиса (CLAUDE.md): {@link #findDueSuggestions}
 * в read-only транзакции подбирает кандидатов и отдаёт DTO, шедулер шлёт пуш вне
 * транзакции, затем для каждого отправленного дёргает {@link #recordSuggested} в
 * отдельной транзакции. Идемпотентность создания — UNIQUE
 * {@code (user_id, plant_id, source_event_id)} (V29): повторный тик по той же
 * предстоящей пересадке ничего не создаёт и ничего не шлёт.
 *
 * <p>Реакции на кнопки ({@link #addToShoppingList}/{@link #dismiss}) идемпотентны:
 * повторно доставленный/двойной callback приводит к одному конечному состоянию.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TransplantSuggestionService {

    private final TransplantSupplySuggestionRepository suggestionRepository;
    private final PlantEventRepository plantEventRepository;
    private final UserRepository userRepository;
    private final PlantRepository plantRepository;
    private final ShoppingListService shoppingListService;
    private final TransplantSuggestionProperties properties;
    private final TransplantSuggestionInserter suggestionInserter;
    private final Clock clock;

    /**
     * Подобрать растения, у которых предстоящая пересадка (last TRANSPLANT +
     * interval) попадает в окно {@code [today; today + horizonDays]} в TZ юзера
     * и по которым ещё не подсказывали. Возвращает готовые DTO для отправки —
     * Telegram-вызов делает caller, чтобы не держать транзакцию открытой во
     * время HTTP.
     *
     * @param now момент проверки (UTC); передаётся явно — для тестов и
     *            согласованности с {@code Clock}-бином caller'а
     */
    @Transactional(readOnly = true)
    public List<DueSuggestion> findDueSuggestions(Instant now) {
        if (!properties.enabled()) {
            return List.of();
        }

        List<PlantEvent> latestTransplants =
                plantEventRepository.findLatestEventPerActivePlant(PlantEventType.TRANSPLANT);
        if (latestTransplants.isEmpty()) {
            return List.of();
        }

        List<DueSuggestion> due = new ArrayList<>();
        for (PlantEvent event : latestTransplants) {
            Plant plant = event.getPlant();
            User user = plant.getUser();
            if (user == null || user.isBlocked()) {
                continue;
            }

            LocalDateTime predicted = event.getEventDate().plusMonths(properties.intervalMonths());

            if (!isWithinHorizon(predicted, now, user)) {
                continue;
            }

            if (suggestionRepository.existsByUserIdAndPlantIdAndSourceEventId(
                    user.getId(), plant.getId(), event.getId())) {
                continue;
            }

            due.add(new DueSuggestion(
                    user.getId(),
                    user.getTelegramChatId(),
                    plant.getId(),
                    plant.getName(),
                    event.getId(),
                    predicted
            ));
        }
        return due;
    }

    /**
     * Зафиксировать факт отправки подсказки — отдельная транзакция, чтобы caller
     * мог дёрнуть её сразу после успешного Telegram send. Создаёт строку
     * {@link SuggestionStatus#SUGGESTED}.
     *
     * <p>Возвращает id созданной подсказки — он нужен caller'у для подстановки в
     * callback_data кнопок. Если строка уже есть (гонка между тиками), возвращает
     * {@link Optional#empty()} — слать кнопки с «чужим» id нельзя.
     *
     * <p>Сама вставка делегируется {@link TransplantSuggestionInserter} в
     * отдельной транзакции ({@code REQUIRES_NEW}): нарушение UNIQUE при гонке/повторе
     * откатывает только вложенную транзакцию, поэтому эта (внешняя) не помечается
     * rollback-only и {@code DataIntegrityViolationException} здесь ловится без
     * последующего {@code UnexpectedRollbackException} на коммите.
     */
    @Transactional
    public Optional<Long> recordSuggested(DueSuggestion suggestion) {
        try {
            Long id = suggestionInserter.insert(
                    new TransplantSupplySuggestion(
                            userRepository.getReferenceById(suggestion.userId()),
                            plantRepository.getReferenceById(suggestion.plantId()),
                            plantEventRepository.getReferenceById(suggestion.sourceEventId()),
                            suggestion.predictedTransplantAt()
                    )
            );
            return Optional.of(id);
        } catch (DataIntegrityViolationException e) {
            log.debug("Transplant suggestion already exists for user={} plant={} event={}",
                    suggestion.userId(), suggestion.plantId(), suggestion.sourceEventId());
            return Optional.empty();
        }
    }

    /**
     * Реакция «➕ В список покупок». Если подсказка в статусе
     * {@link SuggestionStatus#SUGGESTED} — добавляет каждую позицию из конфига в
     * список покупок юзера (issue #136) и переводит подсказку в
     * {@link SuggestionStatus#ADDED}. Повторное нажатие — no-op
     * ({@link ReactionResult#ALREADY_HANDLED}).
     */
    @Transactional
    public ReactionResult addToShoppingList(Long userId, Long suggestionId) {
        Optional<TransplantSupplySuggestion> optional =
                suggestionRepository.findByUserIdAndId(userId, suggestionId);

        if (optional.isEmpty()) {
            return ReactionResult.NOT_FOUND;
        }

        TransplantSupplySuggestion suggestion = optional.get();

        // Атомарность статусного перехода SUGGESTED -> ADDED здесь не защищена
        // ни @Version, ни SELECT FOR UPDATE: проверка статуса и markAdded() — не
        // атомарны. Это допустимо, потому что callback'и одного юзера
        // сериализуются единственным поллинг-потоком
        // (LongPollingSingleThreadUpdateConsumer), поэтому два конкурентных ADD по
        // одной подсказке невозможны и расходники не задвоятся. При переходе на
        // многопоточную доставку апдейтов сюда нужно добавить @Version
        // (оптимистичная блокировка) или пессимистичную блокировку строки.
        if (suggestion.getStatus() != SuggestionStatus.SUGGESTED) {
            return ReactionResult.ALREADY_HANDLED;
        }

        User user = suggestion.getUser();
        for (String supply : properties.supplies()) {
            shoppingListService.add(user, supply);
        }

        suggestion.markAdded(nowUtc());

        log.info("Transplant suggestion {} of user {} added {} supplies to shopping list",
                suggestionId, userId, properties.supplies().size());

        return ReactionResult.OK;
    }

    /**
     * Реакция «Не нужно». Если подсказка в статусе {@link SuggestionStatus#SUGGESTED}
     * — переводит в {@link SuggestionStatus#DISMISSED}. Повторное нажатие — no-op.
     */
    @Transactional
    public ReactionResult dismiss(Long userId, Long suggestionId) {
        Optional<TransplantSupplySuggestion> optional =
                suggestionRepository.findByUserIdAndId(userId, suggestionId);

        if (optional.isEmpty()) {
            return ReactionResult.NOT_FOUND;
        }

        TransplantSupplySuggestion suggestion = optional.get();

        if (suggestion.getStatus() != SuggestionStatus.SUGGESTED) {
            return ReactionResult.ALREADY_HANDLED;
        }

        suggestion.markDismissed(nowUtc());

        log.info("Transplant suggestion {} of user {} dismissed", suggestionId, userId);

        return ReactionResult.OK;
    }

    /** Текст расходников для подстановки в шаблон нотификации. */
    public String suppliesLabel() {
        return String.join(", ", properties.supplies());
    }

    /** Готовый текст нотификации по шаблону из конфига. */
    public String buildMessageText(String plantName) {
        return String.format(properties.messageTemplate(), plantName, suppliesLabel());
    }

    /**
     * Попадает ли предстоящая пересадка в окно {@code [today; today + horizonDays]}
     * в TZ юзера. {@code predicted} и {@code now} — в UTC (event_date хранится
     * как UTC wall-clock LocalDateTime, см. issue #76). Сравнение ведём по
     * локальным датам в TZ юзера, чтобы граница «скоро» совпадала с восприятием
     * юзера, а не с границей суток UTC.
     */
    private boolean isWithinHorizon(LocalDateTime predicted, Instant now, User user) {
        ZoneId zone = safeZone(user.getTimezone());
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate windowEnd = today.plusDays(properties.horizonDays());

        // predicted — UTC wall-clock; приводим к локальной дате юзера через UTC.
        LocalDate predictedLocalDate = predicted.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(zone)
                .toLocalDate();

        return !predictedLocalDate.isBefore(today) && !predictedLocalDate.isAfter(windowEnd);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static ZoneId safeZone(String tz) {
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    /**
     * DTO с готовыми данными для одной подсказки. record — чтобы шедулер
     * использовал поля напрямую, без лазания в entity с закрытой JPA-сессией.
     */
    public record DueSuggestion(
            Long userId,
            Long telegramChatId,
            Long plantId,
            String plantName,
            Long sourceEventId,
            LocalDateTime predictedTransplantAt
    ) {
    }

    /** Результат реакции на кнопку — для маппинга в ответ юзеру в Telegram-слое. */
    public enum ReactionResult {
        /** Действие применено (добавлено в список / отклонено). */
        OK,
        /** Подсказка уже обработана ранее — идемпотентный повтор. */
        ALREADY_HANDLED,
        /** Подсказка не найдена (или принадлежит другому юзеру). */
        NOT_FOUND
    }
}
