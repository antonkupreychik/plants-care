package com.plantcare.bot.service;

import com.plantcare.bot.domain.enums.TaskType;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

/**
 * Сборщик клавиатуры под пуш-напоминание.
 *
 * <p>Раньше эта логика жила приватно в {@link NotificationSchedulerService}.
 * С появлением snooze-выбора (issue #118) её также должен уметь собрать
 * {@link NotificationCallbackService} (для кнопки «◀️ Назад» из choice-flow).
 * Прямая зависимость callback → scheduler даёт цикл бинов:
 *   {@code PlantsCareBot → NotificationCallbackService → NotificationSchedulerService
 *   → TelegramClientProvider (=PlantsCareBot)}.
 * Поэтому keyboard-сборка вынесена в отдельный компонент без графа зависимостей.
 *
 * <p>SOIL_CHECK не имеет «Сделал/Отложить/Пропустить» (у него три варианта результата),
 * поэтому фабрика рисует для него специальную клавиатуру с soil-кнопками.
 */
@Component
public class ReminderKeyboardFactory {

    /**
     * Стандартная клавиатура напоминания: ✅ Сделал / ⏰ Отложить / ❌ Пропустить.
     * Для SOIL_CHECK — клавиатура DRY/WET/UNKNOWN (исторически другая семантика).
     */
    public InlineKeyboardMarkup buildReminderKeyboard(Long scheduleId, TaskType taskType) {
        if (taskType == TaskType.SOIL_CHECK) {
            return buildSoilCheckKeyboard(scheduleId);
        }

        String doneBtnLabel = switch (taskType) {
            case WATERING -> "✅ Полил";
            case MISTING -> "✅ Опрыскал";
            case FERTILIZING -> "✅ Удобрил";
            case SOIL_CHECK -> "✅ Проверил"; // unreachable, exhaustive switch
        };

        InlineKeyboardButton doneBtn = InlineKeyboardButton.builder()
                .text(doneBtnLabel)
                .callbackData("v1:done:" + scheduleId)
                .build();

        InlineKeyboardButton snoozeBtn = InlineKeyboardButton.builder()
                .text("⏰ Отложить")
                .callbackData("v1:snooze:" + scheduleId)
                .build();

        InlineKeyboardButton skipBtn = InlineKeyboardButton.builder()
                .text("❌ Пропустить")
                .callbackData("v1:skip:" + scheduleId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(doneBtn, snoozeBtn, skipBtn))
                .build();
    }

    private InlineKeyboardMarkup buildSoilCheckKeyboard(Long scheduleId) {
        InlineKeyboardButton dryBtn = InlineKeyboardButton.builder()
                .text("✅ Сухая")
                .callbackData("v1:soil_dry:" + scheduleId)
                .build();

        InlineKeyboardButton wetBtn = InlineKeyboardButton.builder()
                .text("❌ Влажная")
                .callbackData("v1:soil_wet:" + scheduleId)
                .build();

        InlineKeyboardButton unkBtn = InlineKeyboardButton.builder()
                .text("🤷 Не знаю")
                .callbackData("v1:soil_unk:" + scheduleId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(dryBtn, wetBtn, unkBtn))
                .build();
    }
}
