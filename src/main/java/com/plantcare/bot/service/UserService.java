package com.plantcare.bot.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.ConversationState;
import com.plantcare.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    /** Лимит длительности отпуска (issue #53). */
    public static final int MIN_VACATION_DAYS = 1;
    public static final int MAX_VACATION_DAYS = 60;

    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public User findOrCreate(Long chatId, String username) {
        userRepository.insertOrIgnore(chatId, username);

        User user = userRepository.findByTelegramChatId(chatId)
                .orElseThrow(() -> new IllegalStateException("User must exist after insert-or-ignore"));

        return updateUsernameIfChanged(user, username);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByChatId(Long chatId) {
        return userRepository.findByTelegramChatId(chatId);
    }

    @Transactional(readOnly = true)
    public User getByIdOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("User not found: " + userId));
    }

    @Transactional
    public void updateState(User user, ConversationState newState) {
        log.info(
                "State transition for user {}: {} -> {}",
                user.getTelegramChatId(),
                user.getConversationState(),
                newState
        );

        user.setConversationState(newState);
        userRepository.save(user);
    }

    @Transactional
    public void setStateData(User user, String key, String value) {
        log.debug(
                "Setting state data for user {}: {} = {}",
                user.getTelegramChatId(),
                key,
                value
        );

        Map<String, Object> data = user.getStateData();

        if (data == null) {
            data = new HashMap<>();
        }

        data.put(key, value);

        user.setStateData(data);
        userRepository.save(user);
    }

    @Transactional
    public void removeStateData(User user, String key) {
        log.debug(
                "Removing state data for user {}: {}",
                user.getTelegramChatId(),
                key
        );

        Map<String, Object> data = user.getStateData();

        if (data == null) {
            return;
        }

        data.remove(key);

        user.setStateData(data);
        userRepository.save(user);
    }

    @Transactional
    public void resetToIdle(User user) {
        log.info(
                "Resetting user {} to IDLE. Cleaning up state data.",
                user.getTelegramChatId()
        );

        user.setConversationState(ConversationState.IDLE);

        if (user.getStateData() != null) {
            user.getStateData().clear();
        }

        userRepository.save(user);
    }

    /**
     * Отправить юзера в отпуск на N дней (issue #53).
     * N считается «N дней от сейчас в локальной TZ юзера», в БД пишется UTC-LocalDateTime,
     * чтобы быть консистентным с остальной схемой.
     *
     * @throws IllegalArgumentException если {@code days} вне диапазона [{@value #MIN_VACATION_DAYS},
     *                                  {@value #MAX_VACATION_DAYS}]
     */
    @Transactional
    public User startVacation(User user, int days) {
        if (days < MIN_VACATION_DAYS || days > MAX_VACATION_DAYS) {
            throw new IllegalArgumentException(
                    "Дней должно быть от " + MIN_VACATION_DAYS + " до " + MAX_VACATION_DAYS
            );
        }

        ZoneId userZone = resolveZone(user.getTimezone());
        LocalDateTime endUtc = ZonedDateTime.now(clock)
                .withZoneSameInstant(userZone)
                .plusDays(days)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        user.setPausedUntil(endUtc);
        log.info(
                "Vacation started for user {}: until {} ({} days, tz={})",
                user.getTelegramChatId(), endUtc, days, userZone
        );
        return userRepository.save(user);
    }

    /**
     * Досрочно вернуть юзера из отпуска (issue #53). Если он не в отпуске — операция
     * идемпотентна (paused_until уже null, повторная установка null — no-op).
     */
    @Transactional
    public User endVacation(User user) {
        user.setPausedUntil(null);
        log.info("Vacation ended for user {}", user.getTelegramChatId());
        return userRepository.save(user);
    }

    /**
     * Продлить текущий отпуск на дополнительные дни (issue #53, кнопки +3 / +7
     * в напоминании «завтра возвращаюсь»). Складываем от текущего {@code pausedUntil},
     * не от now() — иначе при срабатывании напоминания за сутки до конца отпуск
     * не сдвинется, а сократится.
     *
     * @throws IllegalStateException     если юзер не в отпуске
     * @throws IllegalArgumentException  если итоговая длительность нарушает лимит
     */
    @Transactional
    public User extendVacation(User user, int additionalDays) {
        if (additionalDays < MIN_VACATION_DAYS || additionalDays > MAX_VACATION_DAYS) {
            throw new IllegalArgumentException(
                    "Продление должно быть от " + MIN_VACATION_DAYS + " до " + MAX_VACATION_DAYS + " дней"
            );
        }
        if (user.getPausedUntil() == null) {
            throw new IllegalStateException("Юзер не в отпуске");
        }

        // Кейс «продлил после конца окна»: если из-за downtime/задержки шедулера
        // pausedUntil уже в прошлом, считаем от now(), иначе newEnd останется
        // в прошлом и продление будет no-op.
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime base = user.getPausedUntil().isAfter(now) ? user.getPausedUntil() : now;
        LocalDateTime newEnd = base.plusDays(additionalDays);
        user.setPausedUntil(newEnd);
        log.info(
                "Vacation extended for user {}: +{} days, new end {}",
                user.getTelegramChatId(), additionalDays, newEnd
        );
        return userRepository.save(user);
    }

    private ZoneId resolveZone(String tz) {
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}', defaulting to UTC", tz);
            return ZoneOffset.UTC;
        }
    }

    private User updateUsernameIfChanged(User user, String newUsername) {
        if (newUsername != null && !newUsername.equals(user.getUsername())) {
            log.debug(
                    "Updating username for chatId={}: {} -> {}",
                    user.getTelegramChatId(),
                    user.getUsername(),
                    newUsername
            );

            user.setUsername(newUsername);
            userRepository.save(user);
        }

        return user;
    }
}