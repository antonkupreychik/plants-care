package com.plantcare.core.service;

import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Удаление аккаунта пользователя (issue #181, GDPR / App Store).
 *
 * <p>Стратегия — hard-delete строки {@code users} (ADR-015). Связанные таблицы
 * (plants, care_schedules, care_history, locations, shopping_items, notifications,
 * transplant_supply_suggestions, notification_digests, photo_progress и т.д.)
 * уходят каскадом через FK {@code ON DELETE CASCADE} на уровне БД — так же, как
 * при {@link PlantArchiveService#deleteForever} для отдельного растения.
 *
 * <p>Никаких вызовов внешних API (Telegram) внутри транзакции — нарушение правил
 * стека. Telegram-связка ({@code telegram_chat_id}) уходит вместе с записью: при
 * следующем {@code /start} бот создаст нового пользователя с нуля.
 *
 * <p>Идемпотентность: если пользователь уже удалён (или не существует) —
 * {@code deleteById} без находки = no-op (JPA-реализация проверяет наличие перед
 * физическим delete; если запись исчезла — просто ничего не делает). Контроллер
 * всегда возвращает {@code 204}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepository;

    /**
     * Необратимо удаляет пользователя с {@code userId} и все связанные данные.
     * Если пользователь уже удалён — no-op (идемпотентно).
     *
     * @param userId идентификатор пользователя из claim {@code sub} JWT.
     */
    @Transactional
    public void deleteAccount(Long userId) {
        if (!userRepository.existsById(userId)) {
            log.info("DELETE /api/v1/me: user {} already deleted or never existed — no-op", userId);
            return;
        }
        userRepository.deleteById(userId);
        log.info("DELETE /api/v1/me: user {} permanently deleted with all cascaded data", userId);
    }
}
