package com.plantcare.bot.diagnosis;

import com.plantcare.core.diagnosis.DiagnosisAnswers;
import com.plantcare.core.diagnosis.DiagnosisResult;
import com.plantcare.core.diagnosis.DiagnosisRuleEngine;
import com.plantcare.core.diagnosis.DiagnosisSessionService;
import com.plantcare.core.diagnosis.DiagnosisStep;
import com.plantcare.core.diagnosis.DiagnosisSymptom;
import com.plantcare.core.diagnosis.DiagnosisTextFormatter;
import com.plantcare.core.diagnosis.LightCondition;
import com.plantcare.core.diagnosis.PestPresence;
import com.plantcare.core.diagnosis.RecentChanges;
import com.plantcare.core.diagnosis.SoilState;
import com.plantcare.core.diagnosis.WateringFrequency;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.DiseaseCard;
import com.plantcare.core.service.DiseaseService;
import com.plantcare.core.service.PlantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlantDiagnosisService {

    private static final String PREFIX = "PLANT:DIAG:";

    private final PlantService plantService;
    private final DiagnosisSessionService sessionService;
    private final DiagnosisRuleEngine ruleEngine;
    private final DiagnosisTextFormatter textFormatter;
    private final DiagnosisKeyboardFactory keyboardFactory;
    private final DiseaseService diseaseService;

    public boolean supports(String callbackData) {
        return callbackData != null && callbackData.startsWith(PREFIX);
    }

    public void handleCallback(
            CallbackQuery callbackQuery,
            TelegramClient client,
            User user
    ) {
        handleCallbackByIds(callbackQuery.getData(), callbackQuery.getId(), client, user);
    }

    public void handleCallbackByIds(
            String data,
            String callbackId,
            TelegramClient client,
            User user
    ) {
        if (data == null || !data.startsWith(PREFIX)) {
            answerCallback(client, callbackId, "❌ Неизвестная команда");
            return;
        }

        String payload = data.substring(PREFIX.length());
        String[] parts = payload.split(":");

        if (parts.length < 2) {
            answerCallback(client, callbackId, "❌ Неверная команда");
            return;
        }

        String action = parts[0];
        Long plantId = parsePlantId(parts[1]);

        if (plantId == null) {
            answerCallback(client, callbackId, "❌ Неверный ID растения");
            return;
        }

        Optional<Plant> plantOptional = plantService.getPlantForUser(user.getId(), plantId);
        if (plantOptional.isEmpty()) {
            sessionService.finish(user);
            answerCallback(client, callbackId, "❌ Растение не найдено");
            return;
        }

        Plant plant = plantOptional.get();

        try {
            switch (action) {
                case "START" -> start(user, plant, client);
                case "BEGIN" -> showSymptomQuestion(user, plant, client);
                case "SYMPTOM" -> handleSymptom(parts, user, plant, client);
                case "WATERING" -> handleWatering(parts, user, plant, client);
                case "SOIL" -> handleSoil(parts, user, plant, client);
                case "LIGHT" -> handleLight(parts, user, plant, client);
                case "CHANGES" -> handleRecentChanges(parts, user, plant, client);
                case "PESTS" -> handlePests(parts, user, plant, client);
                case "SAVE_NOTE" -> saveDiagnosisToNotes(user, plant, client);
                case "PHOTO" -> askPhoto(user, client);
                case "DONE" -> done(user, plant, client);
                case "CANCEL" -> cancel(user, client);
                default -> answerCallback(client, callbackId, "❌ Неизвестная команда");
            }

            answerCallback(client, callbackId, "");
        } catch (IllegalArgumentException e) {
            log.warn("Invalid diagnosis callback data: {}", data, e);
            answerCallback(client, callbackId, "❌ Неверная команда");
        } catch (RuntimeException e) {
            log.error("Failed to handle diagnosis callback: {}", data, e);
            answerCallback(client, callbackId, "❌ Не удалось выполнить действие");
        }
    }

    public boolean handlePhotoIfWaiting(
            Message message,
            TelegramClient client,
            User user
    ) {
        if (user.getConversationState() == null) {
            return false;
        }

        if (!"AWAITING_PLANT_DIAGNOSIS".equals(user.getConversationState().name())) {
            return false;
        }

        if (sessionService.getStep(user) != DiagnosisStep.WAITING_PHOTO) {
            return false;
        }

        if (message == null || message.getPhoto() == null || message.getPhoto().isEmpty()) {
            return false;
        }

        Long plantId = sessionService.getPlantId(user).orElse(null);
        if (plantId == null) {
            sessionService.finish(user);
            sendText(
                    user.getTelegramChatId(),
                    "❌ Не удалось определить растение для диагностики.",
                    client
            );
            return true;
        }

        Optional<Plant> plantOptional = plantService.getPlantForUser(user.getId(), plantId);
        if (plantOptional.isEmpty()) {
            sessionService.finish(user);
            sendText(
                    user.getTelegramChatId(),
                    "❌ Растение не найдено.",
                    client
            );
            return true;
        }

        Plant plant = plantOptional.get();

        String fileId = message.getPhoto()
                .get(message.getPhoto().size() - 1)
                .getFileId();

        try {
            plantService.updatePhotoFileId(user.getId(), plant.getId(), fileId);

            sendText(
                    user.getTelegramChatId(),
                    """
                    ✅ Фото сохранено в карточку растения.
    
                    Важно: я не анализирую изображение автоматически.
                    Фото сохранено как контекст для истории растения.
                    """,
                    keyboardFactory.afterPhotoKeyboard(plant.getId()),
                    client
            );

            sessionService.setStep(user, DiagnosisStep.RESULT);
            return true;
        } catch (RuntimeException e) {
            log.error("Failed to save diagnosis photo for plant {}", plant.getId(), e);

            sendText(
                    user.getTelegramChatId(),
                    "❌ Не удалось сохранить фото. Попробуй ещё раз или вернись в карточку растения.",
                    keyboardFactory.afterPhotoKeyboard(plant.getId()),
                    client
            );

            sessionService.setStep(user, DiagnosisStep.RESULT);
            return true;
        }
    }

    private void start(
            User user,
            Plant plant,
            TelegramClient client
    ) {
        sessionService.start(user, plant.getId());

        sendMarkdown(
                user.getTelegramChatId(),
                textFormatter.disclaimer(plant),
                keyboardFactory.disclaimerKeyboard(plant.getId()),
                client
        );
    }

    private void showSymptomQuestion(
            User user,
            Plant plant,
            TelegramClient client
    ) {
        sessionService.setStep(user, DiagnosisStep.SYMPTOM);

        sendMarkdown(
                user.getTelegramChatId(),
                textFormatter.symptomQuestion(plant),
                keyboardFactory.symptomKeyboard(plant.getId()),
                client
        );
    }

    private void handleSymptom(
            String[] parts,
            User user,
            Plant plant,
            TelegramClient client
    ) {
        String code = requireCode(parts);
        DiagnosisSymptom symptom = DiagnosisSymptom.fromCode(code);

        sessionService.saveSymptom(user, symptom);
        sessionService.setStep(user, DiagnosisStep.WATERING);

        sendText(
                user.getTelegramChatId(),
                textFormatter.wateringQuestion(),
                keyboardFactory.wateringKeyboard(plant.getId()),
                client
        );
    }

    private void handleWatering(
            String[] parts,
            User user,
            Plant plant,
            TelegramClient client
    ) {
        String code = requireCode(parts);
        WateringFrequency watering = WateringFrequency.fromCode(code);

        sessionService.saveWatering(user, watering);
        sessionService.setStep(user, DiagnosisStep.SOIL);

        sendText(
                user.getTelegramChatId(),
                textFormatter.soilQuestion(),
                keyboardFactory.soilKeyboard(plant.getId()),
                client
        );
    }

    private void handleSoil(
            String[] parts,
            User user,
            Plant plant,
            TelegramClient client
    ) {
        String code = requireCode(parts);
        SoilState soilState = SoilState.fromCode(code);

        sessionService.saveSoil(user, soilState);
        sessionService.setStep(user, DiagnosisStep.LIGHT);

        sendText(
                user.getTelegramChatId(),
                textFormatter.lightQuestion(),
                keyboardFactory.lightKeyboard(plant.getId()),
                client
        );
    }

    private void handleLight(
            String[] parts,
            User user,
            Plant plant,
            TelegramClient client
    ) {
        String code = requireCode(parts);
        LightCondition light = LightCondition.fromCode(code);

        sessionService.saveLight(user, light);
        sessionService.setStep(user, DiagnosisStep.RECENT_CHANGES);

        sendText(
                user.getTelegramChatId(),
                textFormatter.recentChangesQuestion(),
                keyboardFactory.recentChangesKeyboard(plant.getId()),
                client
        );
    }

    private void handleRecentChanges(
            String[] parts,
            User user,
            Plant plant,
            TelegramClient client
    ) {
        String code = requireCode(parts);
        RecentChanges recentChanges = RecentChanges.fromCode(code);

        sessionService.saveRecentChanges(user, recentChanges);
        sessionService.setStep(user, DiagnosisStep.PESTS);

        sendText(
                user.getTelegramChatId(),
                textFormatter.pestsQuestion(),
                keyboardFactory.pestsKeyboard(plant.getId()),
                client
        );
    }

    private void handlePests(
            String[] parts,
            User user,
            Plant plant,
            TelegramClient client
    ) {
        String code = requireCode(parts);
        PestPresence pestPresence = PestPresence.fromCode(code);

        sessionService.savePests(user, pestPresence);
        showResult(user, plant, client);
    }

    private void showResult(
            User user,
            Plant plant,
            TelegramClient client
    ) {
        sessionService.setStep(user, DiagnosisStep.RESULT);

        DiagnosisAnswers answers = sessionService.getAnswers(user);
        DiagnosisResult result = ruleEngine.diagnose(answers);

        sendMarkdown(
                user.getTelegramChatId(),
                textFormatter.result(plant, answers, result),
                keyboardFactory.resultKeyboard(plant.getId()),
                client
        );

        sendDiseaseHint(user, answers, client);
    }

    /**
     * Мягкая подсказка по справочнику болезней (issue #140, ADR-013): по симптому
     * визарда подбираем вероятные болезни и шлём отдельным plain-сообщением.
     * Rule engine (#73) при этом не трогается — это независимый блок поверх результата.
     * Если совпадений нет — ничего не отправляем.
     */
    private void sendDiseaseHint(
            User user,
            DiagnosisAnswers answers,
            TelegramClient client
    ) {
        List<DiseaseCard> matches = diseaseService.matchBySymptom(answers.symptom());

        String hint = textFormatter.diseaseHint(matches);
        if (hint == null) {
            return;
        }

        sendText(user.getTelegramChatId(), hint, client);
    }

    private void saveDiagnosisToNotes(
            User user,
            Plant plant,
            TelegramClient client
    ) {
        DiagnosisAnswers answers = sessionService.getAnswers(user);
        DiagnosisResult result = ruleEngine.diagnose(answers);

        String note = textFormatter.noteText(plant, answers, result);
        String currentNotes = plant.getNotes();

        String newNotes;
        if (currentNotes == null || currentNotes.isBlank()) {
            newNotes = note;
        } else {
            newNotes = currentNotes.trim() + "\n\n---\n\n" + note;
        }

        plantService.updateNotes(user.getId(), plant.getId(), newNotes);

        sendText(
                user.getTelegramChatId(),
                "✅ Результат диагностики записан в заметки растения.",
                keyboardFactory.resultKeyboard(plant.getId()),
                client
        );
    }

    private void askPhoto(
            User user,
            TelegramClient client
    ) {
        sessionService.setStep(user, DiagnosisStep.WAITING_PHOTO);

        sendText(
                user.getTelegramChatId(),
                textFormatter.photoPrompt(),
                client
        );
    }

    private void done(
            User user,
            Plant plant,
            TelegramClient client
    ) {
        sessionService.finish(user);

        sendText(
                user.getTelegramChatId(),
                "✅ Диагностика завершена для растения: " + plant.getName(),
                client
        );
    }

    private void cancel(
            User user,
            TelegramClient client
    ) {
        sessionService.finish(user);

        sendText(
                user.getTelegramChatId(),
                "❌ Диагностика отменена.",
                client
        );
    }

    private Long parsePlantId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String requireCode(String[] parts) {
        if (parts.length < 3 || parts[2] == null || parts[2].isBlank()) {
            throw new IllegalArgumentException("Missing diagnosis answer code");
        }

        return parts[2];
    }

    private void sendText(
            Long chatId,
            String text,
            TelegramClient client
    ) {
        sendText(chatId, text, null, client);
    }

    private void sendText(
            Long chatId,
            String text,
            ReplyKeyboard replyMarkup,
            TelegramClient client
    ) {
        SendMessage.SendMessageBuilder<?, ?> builder = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text);

        if (replyMarkup != null) {
            builder.replyMarkup(replyMarkup);
        }

        try {
            client.execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Failed to send diagnosis text message", e);
        }
    }

    private void sendMarkdown(
            Long chatId,
            String text,
            ReplyKeyboard replyMarkup,
            TelegramClient client
    ) {
        SendMessage.SendMessageBuilder<?, ?> builder = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown");

        if (replyMarkup != null) {
            builder.replyMarkup(replyMarkup);
        }

        try {
            client.execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Failed to send diagnosis markdown message", e);
        }
    }

    private void answerCallback(
            TelegramClient client,
            String callbackId,
            String text
    ) {
        try {
            AnswerCallbackQuery.AnswerCallbackQueryBuilder builder = AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId);

            if (text != null && !text.isBlank()) {
                builder.text(text);
            }

            client.execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Failed to answer diagnosis callback", e);
        }
    }
}