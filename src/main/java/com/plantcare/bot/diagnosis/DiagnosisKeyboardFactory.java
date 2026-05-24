package com.plantcare.bot.diagnosis;

import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;

@Component
public class DiagnosisKeyboardFactory {

    public InlineKeyboardMarkup disclaimerKeyboard(Long plantId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        button("✅ Начать", "PLANT:DIAG:BEGIN:" + plantId),
                        button("❌ Отмена", "PLANT:DIAG:CANCEL:" + plantId)
                )))
                .build();
    }

    public InlineKeyboardMarkup symptomKeyboard(Long plantId) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        for (DiagnosisSymptom symptom : DiagnosisSymptom.values()) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    button(symptom.label(), "PLANT:DIAG:SYMPTOM:" + plantId + ":" + symptom.code())
            )));
        }

        builder.keyboardRow(cancelRow(plantId));

        return builder.build();
    }

    public InlineKeyboardMarkup wateringKeyboard(Long plantId) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        for (WateringFrequency value : WateringFrequency.values()) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    button(value.label(), "PLANT:DIAG:WATERING:" + plantId + ":" + value.code())
            )));
        }

        builder.keyboardRow(cancelRow(plantId));

        return builder.build();
    }

    public InlineKeyboardMarkup soilKeyboard(Long plantId) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        for (SoilState value : SoilState.values()) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    button(value.label(), "PLANT:DIAG:SOIL:" + plantId + ":" + value.code())
            )));
        }

        builder.keyboardRow(cancelRow(plantId));

        return builder.build();
    }

    public InlineKeyboardMarkup lightKeyboard(Long plantId) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        for (LightCondition value : LightCondition.values()) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    button(value.label(), "PLANT:DIAG:LIGHT:" + plantId + ":" + value.code())
            )));
        }

        builder.keyboardRow(cancelRow(plantId));

        return builder.build();
    }

    public InlineKeyboardMarkup recentChangesKeyboard(Long plantId) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        for (RecentChanges value : RecentChanges.values()) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    button(value.label(), "PLANT:DIAG:CHANGES:" + plantId + ":" + value.code())
            )));
        }

        builder.keyboardRow(cancelRow(plantId));

        return builder.build();
    }

    public InlineKeyboardMarkup pestsKeyboard(Long plantId) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> builder = InlineKeyboardMarkup.builder();

        for (PestPresence value : PestPresence.values()) {
            builder.keyboardRow(new InlineKeyboardRow(List.of(
                    button(value.label(), "PLANT:DIAG:PESTS:" + plantId + ":" + value.code())
            )));
        }

        builder.keyboardRow(cancelRow(plantId));

        return builder.build();
    }

    public InlineKeyboardMarkup resultKeyboard(Long plantId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        button("📝 Записать в заметки", "PLANT:DIAG:SAVE_NOTE:" + plantId)
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        button("📸 Сделать фото", "PLANT:DIAG:PHOTO:" + plantId),
                        button("✅ Готово", "PLANT:DIAG:DONE:" + plantId)
                )))
                .build();
    }

    public InlineKeyboardMarkup afterPhotoKeyboard(Long plantId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        button("📝 Записать в заметки", "PLANT:DIAG:SAVE_NOTE:" + plantId)
                )))
                .keyboardRow(new InlineKeyboardRow(List.of(
                        button("✅ Готово", "PLANT:DIAG:DONE:" + plantId)
                )))
                .build();
    }

    private InlineKeyboardRow cancelRow(Long plantId) {
        return new InlineKeyboardRow(List.of(
                button("❌ Отмена", "PLANT:DIAG:CANCEL:" + plantId)
        ));
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(callbackData)
                .build();
    }
}