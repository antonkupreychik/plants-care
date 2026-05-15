package com.plantcare.bot.admin.stuck.service;

import com.plantcare.bot.admin.stuck.dto.StuckUserItemDto;
import com.plantcare.bot.admin.stuck.repository.AdminStuckRepository;
import com.plantcare.bot.admin.users.service.AdminUserActionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Логика страницы /admin/stuck (issue #59).
 *
 * <p>Главная цель — отвечать на «кто из юзеров застрял не-IDLE» (типичный кейс:
 * юзер начал {@code /add}, бросил wizard, любой ввод теперь воспринимается как
 * имя растения). Действие — сбросить state в IDLE через переиспользование
 * {@link AdminUserActionService#resetState}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminStuckService {

    /** Порог застревания: state не менялся дольше 24 часов. */
    public static final int STUCK_AFTER_HOURS = 24;

    private final AdminStuckRepository repository;
    private final AdminUserActionService userActionService;

    public List<StuckUserItemDto> listStuck() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusHours(STUCK_AFTER_HOURS);
        return repository.findStuck(cutoff, now);
    }

    /**
     * Массовый сброс. Идёт по списку, каждый сброс — отдельная транзакция в
     * {@link AdminUserActionService} (так уже сделано). Считаем успешные и
     * ошибки, чтобы вернуть админу осмысленный счёт.
     *
     * <p>Без батч-апдейта намеренно: размер списка обычно небольшой (десятки),
     * а индивидуальные транзакции дают точный аудит-лог по каждому юзеру.
     */
    public BulkResetResult resetBulk(List<Long> userIds, String adminName) {
        if (userIds == null || userIds.isEmpty()) {
            return new BulkResetResult(0, 0);
        }
        int success = 0;
        int failed = 0;
        for (Long userId : userIds) {
            if (userId == null) continue;
            try {
                userActionService.resetState(userId, adminName);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("Bulk reset failed for user {}: {}", userId, e.getMessage());
            }
        }
        log.info("Bulk stuck-reset by {}: success={}, failed={}, total={}",
                adminName, success, failed, userIds.size());
        return new BulkResetResult(success, failed);
    }

    public record BulkResetResult(int success, int failed) {
        public int total() { return success + failed; }
    }
}
