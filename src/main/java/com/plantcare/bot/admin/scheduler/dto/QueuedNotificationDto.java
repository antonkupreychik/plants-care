package com.plantcare.bot.admin.scheduler.dto;

/**
 * Одна запись в таблице «Очередь уведомлений» на /admin/scheduler (issue #59).
 *
 * @param scheduleId   ID расписания (используется в action-URL для send/skip)
 * @param chatId       telegram chat ID юзера
 * @param username     telegram username (может быть null/пусто)
 * @param plantName    название растения
 * @param taskType     WATERING / MISTING / FERTILIZING / SOIL_CHECK
 * @param taskEmoji    эмодзи задачи (для компактного отображения)
 * @param dueAtHuman   «5 мин назад», «через 2 ч», «вчера в 14:30»
 * @param overdue      {@code true}, если {@code next_due_at <= now} (опоздание)
 * @param paused       юзер сейчас на паузе → отправка force-only
 * @param intervalDays интервал расписания, для отображения «полив раз в N дней»
 */
public record QueuedNotificationDto(
        Long scheduleId,
        Long chatId,
        String username,
        String plantName,
        String taskType,
        String taskEmoji,
        String dueAtHuman,
        boolean overdue,
        boolean paused,
        int intervalDays
) {}
