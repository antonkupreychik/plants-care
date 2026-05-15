package com.plantcare.bot.diagnosis;

import com.plantcare.bot.domain.Plant;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class DiagnosisTextFormatter {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public String disclaimer(Plant plant) {
        return """
                🩺 *Диагностика растения*

                Растение: *%s*

                Я не агроном и не ставлю точный диагноз.
                Это подсказки по распространённым бытовым причинам: полив, свет, стресс, вредители.

                Если растение резко ухудшается, гниёт, сильно поражено вредителями или очень ценное — лучше обратиться к специалисту.
                """.formatted(escapeMarkdown(plant.getName()));
    }

    public String symptomQuestion(Plant plant) {
        return """
                Что происходит с растением *%s*?
                """.formatted(escapeMarkdown(plant.getName()));
    }

    public String wateringQuestion() {
        return """
                💧 Как в последнее время с поливом?

                Выбери ближайший вариант.
                """;
    }

    public String soilQuestion() {
        return """
                🪴 Какой сейчас грунт?

                Лучше проверить пальцем или деревянной палочкой на глубине 2–3 см.
                """;
    }

    public String lightQuestion() {
        return """
                ☀️ Какой свет получает растение большую часть дня?
                """;
    }

    public String recentChangesQuestion() {
        return """
                🔄 Были ли недавно изменения?

                Например: покупка, переезд, пересадка, перестановка, смена окна.
                """;
    }

    public String pestsQuestion() {
        return """
                🐛 Видишь ли насекомых, липкость, точки, паутинку или странные следы?

                Проверь нижнюю сторону листьев и молодые побеги.
                """;
    }

    public String result(Plant plant, DiagnosisAnswers answers, DiagnosisResult result) {
        StringBuilder text = new StringBuilder();

        text.append("🩺 *Результат диагностики*\n\n");
        text.append("Растение: *").append(escapeMarkdown(plant.getName())).append("*\n");

        if (answers.symptom() != null) {
            text.append("Симптом: ").append(answers.symptom().label()).append("\n");
        }

        text.append("\n");

        appendSection(text, "Возможные причины", result.possibleCauses(), "• ");
        appendSection(text, "Что сделать сейчас", result.actionsNow(), "☐ ");
        appendSection(text, "Проверь дополнительно", result.additionalChecks(), "• ");

        text.append("\n_Это не точный диагноз. Наблюдай за динамикой и не делай резких изменений сразу._");

        return text.toString();
    }

    public String noteText(Plant plant, DiagnosisAnswers answers, DiagnosisResult result) {
        StringBuilder text = new StringBuilder();

        text.append("🩺 Диагностика от ")
                .append(LocalDate.now().format(DATE_FORMATTER))
                .append("\n\n");

        text.append("Растение: ").append(plant.getName()).append("\n");

        if (answers.symptom() != null) {
            text.append("Симптом: ").append(answers.symptom().label()).append("\n");
        }

        text.append("\n");

        appendPlainSection(text, "Возможные причины", result.possibleCauses(), "— ");
        appendPlainSection(text, "Что сделать сейчас", result.actionsNow(), "☐ ");
        appendPlainSection(text, "Проверь дополнительно", result.additionalChecks(), "— ");

        return text.toString().trim();
    }

    public String photoPrompt() {
        return """
                📸 Отправь фото растения одним сообщением.

                Важно: я не анализирую изображение автоматически.
                Фото можно сохранить как контекст для истории растения.
                """;
    }

    public String photoReceived() {
        return """
                ✅ Фото получил.

                В этой версии я не анализирую изображение, но его можно использовать как контекст для истории растения.
                """;
    }

    private void appendSection(
            StringBuilder text,
            String title,
            List<String> items,
            String bullet
    ) {
        if (items == null || items.isEmpty()) {
            return;
        }

        text.append("*").append(title).append("*\n");

        for (String item : items) {
            text.append(bullet).append(escapeMarkdown(item)).append("\n");
        }

        text.append("\n");
    }

    private void appendPlainSection(
            StringBuilder text,
            String title,
            List<String> items,
            String bullet
    ) {
        if (items == null || items.isEmpty()) {
            return;
        }

        text.append(title).append(":\n");

        for (String item : items) {
            text.append(bullet).append(item).append("\n");
        }

        text.append("\n");
    }

    private String escapeMarkdown(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("`", "\\`");
    }
}