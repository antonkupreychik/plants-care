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
import com.plantcare.core.domain.enums.ConversationState;
import com.plantcare.core.service.DiseaseCard;
import com.plantcare.core.service.DiseaseService;
import com.plantcare.core.service.PlantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlantDiagnosisServiceTest {

    private static final String PREFIX = "PLANT:DIAG:";

    @Mock
    private PlantService plantService;
    @Mock
    private DiagnosisSessionService sessionService;
    @Mock
    private DiagnosisRuleEngine ruleEngine;
    @Mock
    private DiagnosisTextFormatter textFormatter;
    @Mock
    private DiagnosisKeyboardFactory keyboardFactory;
    @Mock
    private DiseaseService diseaseService;
    @Mock
    private TelegramClient telegramClient;
    @Mock
    private CallbackQuery callbackQuery;
    @Mock
    private Message message;

    @InjectMocks
    private PlantDiagnosisService service;

    private User user;
    private Plant plant;
    private final InlineKeyboardMarkup someKeyboard = InlineKeyboardMarkup.builder().build();

    @BeforeEach
    void setUp() {
        user = User.builder().telegramChatId(500L).timezone("UTC").build();
        ReflectionTestUtils.setField(user, "id", 10L);

        plant = Plant.builder().name("Монстера").build();
        ReflectionTestUtils.setField(plant, "id", 5L);
    }

    private void stubPlantFound() {
        when(plantService.getPlantForUser(10L, 5L)).thenReturn(Optional.of(plant));
    }

    private List<SendMessage> capturedMessages() throws Exception {
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, org.mockito.Mockito.atLeastOnce()).execute(captor.capture());
        return captor.getAllValues();
    }

    // ==================== supports() ====================

    @Test
    void should_returnFalse_when_supportsCalledWithNull() {
        assertThat(service.supports(null)).isFalse();
    }

    @Test
    void should_returnTrue_when_supportsCalledWithDiagnosisPrefix() {
        assertThat(service.supports("PLANT:DIAG:START:5")).isTrue();
    }

    @Test
    void should_returnFalse_when_supportsCalledWithOtherPrefix() {
        assertThat(service.supports("PLANT:VIEW:5")).isFalse();
    }

    // ==================== routing / malformed payloads ====================

    @Test
    void should_answerUnknownCommand_when_dataIsNull() throws Exception {
        service.handleCallbackByIds(null, "cb-1", telegramClient, user);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неизвестная команда");
        assertThat(captor.getValue().getCallbackQueryId()).isEqualTo("cb-1");
    }

    @Test
    void should_answerUnknownCommand_when_dataHasWrongPrefix() throws Exception {
        service.handleCallbackByIds("PLANT:VIEW:5", "cb-1", telegramClient, user);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неизвестная команда");
    }

    @Test
    void should_answerInvalidCommand_when_payloadHasFewerThanTwoParts() throws Exception {
        service.handleCallbackByIds(PREFIX + "START", "cb-1", telegramClient, user);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверная команда");
    }

    @Test
    void should_answerInvalidPlantId_when_plantIdIsNotNumeric() throws Exception {
        service.handleCallbackByIds(PREFIX + "START:abc", "cb-1", telegramClient, user);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверный ID растения");
    }

    @Test
    void should_finishSessionAndAnswerNotFound_when_plantDoesNotBelongToUser() throws Exception {
        when(plantService.getPlantForUser(10L, 5L)).thenReturn(Optional.empty());

        service.handleCallbackByIds(PREFIX + "START:5", "cb-1", telegramClient, user);

        verify(sessionService).finish(user);
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Растение не найдено");
    }

    // ==================== START / BEGIN ====================

    @Test
    void should_startSessionAndSendDisclaimer_when_actionIsStart() throws Exception {
        stubPlantFound();
        when(textFormatter.disclaimer(plant)).thenReturn("DISCLAIMER_TEXT");
        when(keyboardFactory.disclaimerKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "START:5", "cb-1", telegramClient, user);

        verify(sessionService).start(user, 5L);
        List<SendMessage> messages = capturedMessages();
        SendMessage sent = messages.stream()
                .filter(m -> "DISCLAIMER_TEXT".equals(m.getText()))
                .findFirst().orElseThrow();
        assertThat(sent.getParseMode()).isEqualTo("Markdown");
        assertThat(sent.getReplyMarkup()).isSameAs(someKeyboard);
        assertThat(sent.getChatId()).isEqualTo("500");
    }

    @Test
    void should_setSymptomStepAndSendSymptomQuestion_when_actionIsBegin() throws Exception {
        stubPlantFound();
        when(textFormatter.symptomQuestion(plant)).thenReturn("SYMPTOM_QUESTION");
        when(keyboardFactory.symptomKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "BEGIN:5", "cb-1", telegramClient, user);

        verify(sessionService).setStep(user, DiagnosisStep.SYMPTOM);
        List<SendMessage> messages = capturedMessages();
        assertThat(messages).anyMatch(m -> "SYMPTOM_QUESTION".equals(m.getText())
                && "Markdown".equals(m.getParseMode())
                && m.getReplyMarkup() == someKeyboard);
    }

    // ==================== SYMPTOM ====================

    @Test
    void should_saveSymptomAndAdvanceToWatering_when_actionIsSymptomWithValidCode() throws Exception {
        stubPlantFound();
        when(textFormatter.wateringQuestion()).thenReturn("WATERING_QUESTION");
        when(keyboardFactory.wateringKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "SYMPTOM:5:yellow_leaves", "cb-1", telegramClient, user);

        verify(sessionService).saveSymptom(user, DiagnosisSymptom.YELLOW_LEAVES);
        verify(sessionService).setStep(user, DiagnosisStep.WATERING);
        List<SendMessage> messages = capturedMessages();
        SendMessage sent = messages.stream()
                .filter(m -> "WATERING_QUESTION".equals(m.getText()))
                .findFirst().orElseThrow();
        assertThat(sent.getParseMode()).isNull();
        assertThat(sent.getReplyMarkup()).isSameAs(someKeyboard);
    }

    @Test
    void should_answerInvalidCommand_when_symptomCodeIsMissing() throws Exception {
        stubPlantFound();

        service.handleCallbackByIds(PREFIX + "SYMPTOM:5", "cb-1", telegramClient, user);

        verify(sessionService, never()).saveSymptom(any(), any());
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверная команда");
    }

    @Test
    void should_answerInvalidCommand_when_symptomCodeIsBlank() throws Exception {
        stubPlantFound();

        service.handleCallbackByIds(PREFIX + "SYMPTOM:5::extra", "cb-1", telegramClient, user);

        verify(sessionService, never()).saveSymptom(any(), any());
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверная команда");
    }

    @Test
    void should_answerInvalidCommand_when_symptomCodeIsUnknown() throws Exception {
        stubPlantFound();

        service.handleCallbackByIds(PREFIX + "SYMPTOM:5:not_a_symptom", "cb-1", telegramClient, user);

        verify(sessionService, never()).saveSymptom(any(), any());
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Неверная команда");
    }

    // ==================== WATERING / SOIL / LIGHT / CHANGES ====================

    @Test
    void should_saveWateringAndAdvanceToSoil_when_actionIsWatering() throws Exception {
        stubPlantFound();
        when(textFormatter.soilQuestion()).thenReturn("SOIL_QUESTION");
        when(keyboardFactory.soilKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "WATERING:5:less", "cb-1", telegramClient, user);

        verify(sessionService).saveWatering(user, WateringFrequency.LESS_THAN_USUAL);
        verify(sessionService).setStep(user, DiagnosisStep.SOIL);
        assertThat(capturedMessages()).anyMatch(m -> "SOIL_QUESTION".equals(m.getText()));
    }

    @Test
    void should_saveSoilAndAdvanceToLight_when_actionIsSoil() throws Exception {
        stubPlantFound();
        when(textFormatter.lightQuestion()).thenReturn("LIGHT_QUESTION");
        when(keyboardFactory.lightKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "SOIL:5:dry", "cb-1", telegramClient, user);

        verify(sessionService).saveSoil(user, SoilState.DRY);
        verify(sessionService).setStep(user, DiagnosisStep.LIGHT);
        assertThat(capturedMessages()).anyMatch(m -> "LIGHT_QUESTION".equals(m.getText()));
    }

    @Test
    void should_saveLightAndAdvanceToRecentChanges_when_actionIsLight() throws Exception {
        stubPlantFound();
        when(textFormatter.recentChangesQuestion()).thenReturn("CHANGES_QUESTION");
        when(keyboardFactory.recentChangesKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "LIGHT:5:low_light", "cb-1", telegramClient, user);

        verify(sessionService).saveLight(user, LightCondition.LOW_LIGHT);
        verify(sessionService).setStep(user, DiagnosisStep.RECENT_CHANGES);
        assertThat(capturedMessages()).anyMatch(m -> "CHANGES_QUESTION".equals(m.getText()));
    }

    @Test
    void should_saveRecentChangesAndAdvanceToPests_when_actionIsChanges() throws Exception {
        stubPlantFound();
        when(textFormatter.pestsQuestion()).thenReturn("PESTS_QUESTION");
        when(keyboardFactory.pestsKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "CHANGES:5:new_plant", "cb-1", telegramClient, user);

        verify(sessionService).saveRecentChanges(user, RecentChanges.NEW_PLANT);
        verify(sessionService).setStep(user, DiagnosisStep.PESTS);
        assertThat(capturedMessages()).anyMatch(m -> "PESTS_QUESTION".equals(m.getText()));
    }

    // ==================== PESTS -> result + disease hint ====================

    @Test
    void should_saveAnswerAndShowResultWithDiseaseHint_when_pestsAnsweredAndMatchesFound() throws Exception {
        stubPlantFound();
        DiagnosisAnswers answers = new DiagnosisAnswers(
                5L, DiagnosisSymptom.PESTS_OR_WEB, WateringFrequency.AS_USUAL,
                SoilState.NORMAL, LightCondition.BRIGHT_DIFFUSED, RecentChanges.NO_CHANGES,
                PestPresence.YES);
        DiagnosisResult result = new DiagnosisResult(List.of("cause"), List.of("action"), List.of("check"));
        List<DiseaseCard> matches = List.of(new DiseaseCard(1L, "Тля", null, "s", "t", "p"));

        when(sessionService.getAnswers(user)).thenReturn(answers);
        when(ruleEngine.diagnose(answers)).thenReturn(result);
        when(textFormatter.result(plant, answers, result)).thenReturn("RESULT_TEXT");
        when(keyboardFactory.resultKeyboard(5L)).thenReturn(someKeyboard);
        when(diseaseService.matchBySymptom(DiagnosisSymptom.PESTS_OR_WEB)).thenReturn(matches);
        when(textFormatter.diseaseHint(matches)).thenReturn("HINT_TEXT");

        service.handleCallbackByIds(PREFIX + "PESTS:5:yes", "cb-1", telegramClient, user);

        verify(sessionService).savePests(user, PestPresence.YES);
        verify(sessionService).setStep(user, DiagnosisStep.RESULT);
        List<SendMessage> messages = capturedMessages();
        assertThat(messages).anyMatch(m -> "RESULT_TEXT".equals(m.getText())
                && "Markdown".equals(m.getParseMode()) && m.getReplyMarkup() == someKeyboard);
        assertThat(messages).anyMatch(m -> "HINT_TEXT".equals(m.getText()) && m.getParseMode() == null);
    }

    @Test
    void should_notSendDiseaseHintMessage_when_noDiseaseMatches() throws Exception {
        stubPlantFound();
        DiagnosisAnswers answers = DiagnosisAnswers.empty(5L).withPestPresence(PestPresence.NO)
                .withSymptom(DiagnosisSymptom.YELLOW_LEAVES);
        DiagnosisResult result = new DiagnosisResult(List.of("cause"), List.of("action"), List.of("check"));

        when(sessionService.getAnswers(user)).thenReturn(answers);
        when(ruleEngine.diagnose(answers)).thenReturn(result);
        when(textFormatter.result(plant, answers, result)).thenReturn("RESULT_TEXT");
        when(keyboardFactory.resultKeyboard(5L)).thenReturn(someKeyboard);
        when(diseaseService.matchBySymptom(DiagnosisSymptom.YELLOW_LEAVES)).thenReturn(List.of());
        when(textFormatter.diseaseHint(List.of())).thenReturn(null);

        service.handleCallbackByIds(PREFIX + "PESTS:5:no", "cb-1", telegramClient, user);

        List<SendMessage> messages = capturedMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getText()).isEqualTo("RESULT_TEXT");
    }

    // ==================== SAVE_NOTE ====================

    @Test
    void should_saveNoteAsIs_when_plantHasNoExistingNotes() throws Exception {
        stubPlantFound();
        plant.setNotes(null);
        DiagnosisAnswers answers = DiagnosisAnswers.empty(5L);
        DiagnosisResult result = new DiagnosisResult(List.of(), List.of(), List.of());
        when(sessionService.getAnswers(user)).thenReturn(answers);
        when(ruleEngine.diagnose(answers)).thenReturn(result);
        when(textFormatter.noteText(plant, answers, result)).thenReturn("NOTE_TEXT");
        when(keyboardFactory.resultKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "SAVE_NOTE:5", "cb-1", telegramClient, user);

        verify(plantService).updateNotes(10L, 5L, "NOTE_TEXT");
        assertThat(capturedMessages()).anyMatch(
                m -> m.getText().contains("записан в заметки") && m.getReplyMarkup() == someKeyboard);
    }

    @Test
    void should_saveNoteAsIs_when_plantNotesAreBlank() throws Exception {
        stubPlantFound();
        plant.setNotes("   ");
        DiagnosisAnswers answers = DiagnosisAnswers.empty(5L);
        DiagnosisResult result = new DiagnosisResult(List.of(), List.of(), List.of());
        when(sessionService.getAnswers(user)).thenReturn(answers);
        when(ruleEngine.diagnose(answers)).thenReturn(result);
        when(textFormatter.noteText(plant, answers, result)).thenReturn("NOTE_TEXT");
        when(keyboardFactory.resultKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "SAVE_NOTE:5", "cb-1", telegramClient, user);

        verify(plantService).updateNotes(10L, 5L, "NOTE_TEXT");
    }

    @Test
    void should_appendNoteToExistingNotes_when_plantAlreadyHasNotes() throws Exception {
        stubPlantFound();
        plant.setNotes("Старая заметка  ");
        DiagnosisAnswers answers = DiagnosisAnswers.empty(5L);
        DiagnosisResult result = new DiagnosisResult(List.of(), List.of(), List.of());
        when(sessionService.getAnswers(user)).thenReturn(answers);
        when(ruleEngine.diagnose(answers)).thenReturn(result);
        when(textFormatter.noteText(plant, answers, result)).thenReturn("NOTE_TEXT");
        when(keyboardFactory.resultKeyboard(5L)).thenReturn(someKeyboard);

        service.handleCallbackByIds(PREFIX + "SAVE_NOTE:5", "cb-1", telegramClient, user);

        verify(plantService).updateNotes(10L, 5L, "Старая заметка\n\n---\n\nNOTE_TEXT");
    }

    // ==================== PHOTO / DONE / CANCEL ====================

    @Test
    void should_setWaitingPhotoStepAndSendPromptWithoutKeyboard_when_actionIsPhoto() throws Exception {
        stubPlantFound();
        when(textFormatter.photoPrompt()).thenReturn("PHOTO_PROMPT");

        service.handleCallbackByIds(PREFIX + "PHOTO:5", "cb-1", telegramClient, user);

        verify(sessionService).setStep(user, DiagnosisStep.WAITING_PHOTO);
        List<SendMessage> messages = capturedMessages();
        SendMessage sent = messages.stream()
                .filter(m -> "PHOTO_PROMPT".equals(m.getText())).findFirst().orElseThrow();
        assertThat(sent.getReplyMarkup()).isNull();
    }

    @Test
    void should_finishSessionAndConfirm_when_actionIsDone() throws Exception {
        stubPlantFound();

        service.handleCallbackByIds(PREFIX + "DONE:5", "cb-1", telegramClient, user);

        verify(sessionService).finish(user);
        assertThat(capturedMessages()).anyMatch(
                m -> m.getText().equals("✅ Диагностика завершена для растения: Монстера"));
    }

    @Test
    void should_finishSessionAndConfirmCancellation_when_actionIsCancel() throws Exception {
        stubPlantFound();

        service.handleCallbackByIds(PREFIX + "CANCEL:5", "cb-1", telegramClient, user);

        verify(sessionService).finish(user);
        assertThat(capturedMessages()).anyMatch(m -> m.getText().equals("❌ Диагностика отменена."));
    }

    // ==================== unknown action / error handling ====================

    @Test
    void should_answerTwice_when_actionIsUnknownButPlantExists() throws Exception {
        stubPlantFound();

        service.handleCallbackByIds(PREFIX + "WEIRD:5", "cb-1", telegramClient, user);

        // Текущее поведение: default-ветка сама шлёт alert, а затем безусловный
        // answerCallback(client, callbackId, "") после switch отвечает повторно —
        // похоже на лишний повторный ответ на тот же callback (см. отчёт агента).
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().get(0).getText()).contains("Неизвестная команда");
        // answerCallback("") не выставляет text вовсе (isBlank() == true) — второй ответ уходит без текста.
        assertThat(captor.getAllValues().get(1).getText()).isNull();
    }

    @Test
    void should_answerActionFailed_when_handlerThrowsRuntimeException() throws Exception {
        stubPlantFound();
        DiagnosisAnswers answers = DiagnosisAnswers.empty(5L);
        DiagnosisResult result = new DiagnosisResult(List.of(), List.of(), List.of());
        when(sessionService.getAnswers(user)).thenReturn(answers);
        when(ruleEngine.diagnose(answers)).thenReturn(result);
        when(textFormatter.noteText(plant, answers, result)).thenReturn("NOTE_TEXT");
        when(plantService.updateNotes(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        service.handleCallbackByIds(PREFIX + "SAVE_NOTE:5", "cb-1", telegramClient, user);

        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient, times(1)).execute(captor.capture());
        assertThat(captor.getValue().getText()).contains("Не удалось выполнить действие");
    }

    // ==================== handleCallback() delegation ====================

    @Test
    void should_delegateToHandleCallbackByIds_when_handleCallbackCalled() throws Exception {
        stubPlantFound();
        when(callbackQuery.getData()).thenReturn(PREFIX + "DONE:5");
        when(callbackQuery.getId()).thenReturn("cb-42");

        service.handleCallback(callbackQuery, telegramClient, user);

        verify(sessionService).finish(user);
        ArgumentCaptor<AnswerCallbackQuery> captor = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getCallbackQueryId()).isEqualTo("cb-42");
    }

    // ==================== handlePhotoIfWaiting() ====================

    @Test
    void should_returnFalse_when_userHasNoConversationState() {
        user.setConversationState(null);

        boolean handled = service.handlePhotoIfWaiting(message, telegramClient, user);

        assertThat(handled).isFalse();
    }

    @Test
    void should_returnFalse_when_userIsNotAwaitingDiagnosis() {
        user.setConversationState(ConversationState.IDLE);

        boolean handled = service.handlePhotoIfWaiting(message, telegramClient, user);

        assertThat(handled).isFalse();
    }

    @Test
    void should_returnFalse_when_sessionStepIsNotWaitingPhoto() {
        user.setConversationState(ConversationState.AWAITING_PLANT_DIAGNOSIS);
        when(sessionService.getStep(user)).thenReturn(DiagnosisStep.RESULT);

        boolean handled = service.handlePhotoIfWaiting(message, telegramClient, user);

        assertThat(handled).isFalse();
    }

    @Test
    void should_returnFalse_when_messageIsNull() {
        user.setConversationState(ConversationState.AWAITING_PLANT_DIAGNOSIS);
        when(sessionService.getStep(user)).thenReturn(DiagnosisStep.WAITING_PHOTO);

        boolean handled = service.handlePhotoIfWaiting(null, telegramClient, user);

        assertThat(handled).isFalse();
    }

    @Test
    void should_returnFalse_when_messageHasNoPhoto() {
        user.setConversationState(ConversationState.AWAITING_PLANT_DIAGNOSIS);
        when(sessionService.getStep(user)).thenReturn(DiagnosisStep.WAITING_PHOTO);
        when(message.getPhoto()).thenReturn(null);

        boolean handled = service.handlePhotoIfWaiting(message, telegramClient, user);

        assertThat(handled).isFalse();
    }

    @Test
    void should_returnFalse_when_messagePhotoListIsEmpty() {
        user.setConversationState(ConversationState.AWAITING_PLANT_DIAGNOSIS);
        when(sessionService.getStep(user)).thenReturn(DiagnosisStep.WAITING_PHOTO);
        when(message.getPhoto()).thenReturn(List.of());

        boolean handled = service.handlePhotoIfWaiting(message, telegramClient, user);

        assertThat(handled).isFalse();
    }

    @Test
    void should_finishSessionAndNotifyMissingPlant_when_plantIdMissingFromSession() throws Exception {
        user.setConversationState(ConversationState.AWAITING_PLANT_DIAGNOSIS);
        when(sessionService.getStep(user)).thenReturn(DiagnosisStep.WAITING_PHOTO);
        when(message.getPhoto()).thenReturn(List.of(PhotoSize.builder().fileId("f1").build()));
        when(sessionService.getPlantId(user)).thenReturn(Optional.empty());

        boolean handled = service.handlePhotoIfWaiting(message, telegramClient, user);

        assertThat(handled).isTrue();
        verify(sessionService).finish(user);
        assertThat(capturedMessages()).anyMatch(
                m -> m.getText().contains("Не удалось определить растение"));
    }

    @Test
    void should_finishSessionAndNotifyPlantNotFound_when_plantNoLongerBelongsToUser() throws Exception {
        user.setConversationState(ConversationState.AWAITING_PLANT_DIAGNOSIS);
        when(sessionService.getStep(user)).thenReturn(DiagnosisStep.WAITING_PHOTO);
        when(message.getPhoto()).thenReturn(List.of(PhotoSize.builder().fileId("f1").build()));
        when(sessionService.getPlantId(user)).thenReturn(Optional.of(5L));
        when(plantService.getPlantForUser(10L, 5L)).thenReturn(Optional.empty());

        boolean handled = service.handlePhotoIfWaiting(message, telegramClient, user);

        assertThat(handled).isTrue();
        verify(sessionService).finish(user);
        assertThat(capturedMessages()).anyMatch(m -> m.getText().equals("❌ Растение не найдено."));
    }

    @Test
    void should_savePhotoAndAdvanceToResult_when_photoUploadedSuccessfully() throws Exception {
        user.setConversationState(ConversationState.AWAITING_PLANT_DIAGNOSIS);
        when(sessionService.getStep(user)).thenReturn(DiagnosisStep.WAITING_PHOTO);
        when(message.getPhoto()).thenReturn(List.of(
                PhotoSize.builder().fileId("small").build(),
                PhotoSize.builder().fileId("big").build()
        ));
        when(sessionService.getPlantId(user)).thenReturn(Optional.of(5L));
        when(plantService.getPlantForUser(10L, 5L)).thenReturn(Optional.of(plant));
        when(keyboardFactory.afterPhotoKeyboard(5L)).thenReturn(someKeyboard);

        boolean handled = service.handlePhotoIfWaiting(message, telegramClient, user);

        assertThat(handled).isTrue();
        verify(plantService).updatePhotoFileId(10L, 5L, "big");
        verify(sessionService).setStep(user, DiagnosisStep.RESULT);
        assertThat(capturedMessages()).anyMatch(
                m -> m.getText().contains("Фото сохранено в карточку растения")
                        && m.getReplyMarkup() == someKeyboard);
    }

    @Test
    void should_sendFailureMessageAndStillAdvanceToResult_when_photoSaveThrows() throws Exception {
        user.setConversationState(ConversationState.AWAITING_PLANT_DIAGNOSIS);
        when(sessionService.getStep(user)).thenReturn(DiagnosisStep.WAITING_PHOTO);
        when(message.getPhoto()).thenReturn(List.of(PhotoSize.builder().fileId("f1").build()));
        when(sessionService.getPlantId(user)).thenReturn(Optional.of(5L));
        when(plantService.getPlantForUser(10L, 5L)).thenReturn(Optional.of(plant));
        when(plantService.updatePhotoFileId(10L, 5L, "f1")).thenThrow(new RuntimeException("s3 down"));
        when(keyboardFactory.afterPhotoKeyboard(5L)).thenReturn(someKeyboard);

        boolean handled = service.handlePhotoIfWaiting(message, telegramClient, user);

        assertThat(handled).isTrue();
        verify(sessionService).setStep(user, DiagnosisStep.RESULT);
        assertThat(capturedMessages()).anyMatch(
                m -> m.getText().contains("Не удалось сохранить фото")
                        && m.getReplyMarkup() == someKeyboard);
    }
}
