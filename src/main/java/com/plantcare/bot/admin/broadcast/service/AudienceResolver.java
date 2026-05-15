package com.plantcare.bot.admin.broadcast.service;

import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.AudienceFilter;
import com.plantcare.bot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Превращает {@link AudienceFilter} (+опциональный set таймзон) в реальный
 * список юзеров — или просто count для UI-счётчика (issue #60).
 *
 * <p>Выделено в отдельный класс, чтобы счётчик аудитории на лету (HTMX) и
 * сама рассылка опирались на абсолютно одну и ту же логику без копи-пейста
 * SQL-условий.
 */
@Service
@RequiredArgsConstructor
public class AudienceResolver {

    private final UserRepository userRepository;

    public List<User> resolve(AudienceFilter filter, Set<String> timezones) {
        return switch (filter) {
            case ALL_EXCEPT_BLOCKED -> userRepository.findAllNotBlocked();
            case ONLINE             -> userRepository.findOnline(LocalDateTime.now());
            case BY_TIMEZONE        -> isEmptyTz(timezones)
                                       ? List.of()
                                       : userRepository.findByTimezones(timezones);
        };
    }

    public long count(AudienceFilter filter, Set<String> timezones) {
        return switch (filter) {
            case ALL_EXCEPT_BLOCKED -> userRepository.countAllNotBlocked();
            case ONLINE             -> userRepository.countOnline(LocalDateTime.now());
            case BY_TIMEZONE        -> isEmptyTz(timezones)
                                       ? 0L
                                       : userRepository.countByTimezones(timezones);
        };
    }

    /** TZ-ы для multi-select в форме (только зоны, у которых есть юзеры). */
    public List<String> availableTimezones() {
        List<String> tzs = userRepository.findDistinctTimezones();
        return tzs == null ? Collections.emptyList() : tzs;
    }

    private static boolean isEmptyTz(Set<String> tz) {
        return tz == null || tz.isEmpty();
    }
}
