package com.plantcare.admin.featureflag.service;

import com.plantcare.admin.audit.AdminAuditAction;
import com.plantcare.admin.audit.AdminAuditTarget;
import com.plantcare.admin.audit.service.AdminAuditService;
import com.plantcare.core.domain.User;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Управление per-user feature flag'ами (issue #78).
 *
 * <p>API минимальный:
 *   - {@link #setFlag} — включить флаг (с произвольным value)
 *   - {@link #clearFlag} — удалить флаг
 *   - {@link #enabledFor} — какие коды флагов сейчас "true" у юзера
 *
 * <p>Проверки «включён ли флаг» делаются прямо на {@link User#hasFeature}
 * без обращения к этому сервису — это снижает чтения БД в горячих путях
 * (рендеринг меню в боте). Сервис нужен только для записи и обзора.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagService {

    /**
     * Допустимый формат кода флага: латиница нижнего регистра, цифры,
     * подчёркивания, длина 2..64. Это закрывает SQL/JSON injection и
     * не даёт админу записать что-то экзотическое в JSONB-ключ.
     */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    private final UserRepository userRepository;
    private final AdminAuditService auditService;

    /**
     * Установить флаг для юзера. {@code enabled=true} → записываем "true";
     * {@code enabled=false} → удаляем ключ (эквивалент clearFlag).
     *
     * @return {@code true} если состояние действительно изменилось
     * @throws IllegalArgumentException если код некорректный или юзер не найден
     */
    @Transactional
    public boolean setFlag(long userId, String flagCode, boolean enabled, String adminName) {
        String code = validateCode(flagCode);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Юзер не найден: " + userId));

        Map<String, Object> flags = user.getFeatureFlags();
        if (flags == null) {
            flags = new HashMap<>();
            user.setFeatureFlags(flags);
        }

        boolean changed;
        if (enabled) {
            Object prev = flags.put(code, "true");
            changed = !"true".equals(prev);
        } else {
            Object removed = flags.remove(code);
            changed = removed != null;
        }

        if (changed) {
            // Сохраняем явно: Hibernate не всегда детектит мутацию JSONB-Map'а,
            // даже если объект persistent. setFeatureFlags(flags) выше гарантирует
            // что reference установлен, но save() ставит точку.
            userRepository.save(user);
            log.info("Feature flag {}={} for user {} (admin={})",
                    code, enabled, userId, adminName);
            auditService.log(
                    enabled ? AdminAuditAction.USER_FLAG_SET : AdminAuditAction.USER_FLAG_CLEAR,
                    adminName, AdminAuditTarget.USER, userId,
                    AdminAuditService.details("flag", code, "enabled", enabled));
        }
        return changed;
    }

    /** То же что {@code setFlag(userId, code, false, admin)}. */
    @Transactional
    public boolean clearFlag(long userId, String flagCode, String adminName) {
        return setFlag(userId, flagCode, false, adminName);
    }

    /** Все коды флагов юзера, у которых значение «истинно». */
    @Transactional(readOnly = true)
    public Set<String> enabledFor(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Юзер не найден: " + userId));
        return enabledFor(user);
    }

    /** Версия без обращения в БД — используется в UI, где User уже загружен. */
    public Set<String> enabledFor(User user) {
        Map<String, Object> flags = user.getFeatureFlags();
        if (flags == null || flags.isEmpty()) return Set.of();
        java.util.Set<String> result = new java.util.HashSet<>();
        for (Map.Entry<String, Object> e : flags.entrySet()) {
            if (e.getValue() != null && "true".equals(String.valueOf(e.getValue()))) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    private static String validateCode(String code) {
        if (code == null) throw new IllegalArgumentException("Код флага пустой");
        String trimmed = code.trim().toLowerCase();
        if (!CODE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Невалидный код флага. Допустимо: латиница нижнего регистра, "
                            + "цифры, подчёркивания, длина 2..64, начинается с буквы. Получено: "
                            + code);
        }
        return trimmed;
    }
}
