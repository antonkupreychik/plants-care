package com.plantcare.core.seasonal.service;

import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.Season;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Экран «🍂 Сезонные интервалы» в настройках (issue #67).
 *
 * <p>Минимальный набор toggle'ов под все настройки:
 * <ul>
 *   <li>Вкл/Выкл общую сезонность</li>
 *   <li>Переключение режима MULTIPLIER ↔ FIXED</li>
 *   <li>Изменение коэффициентов / fixed-интервалов через нажатия на «±»</li>
 * </ul>
 *
 * <p>Без сложных wizard'ов — нажал на «🌞 ×0.8», пошёл по циклу 0.5/0.6/.../1.5.
 * Это компромисс между UX и количеством exchange'ей кода: полный wizard
 * с числовым вводом был бы перебором для пет-проекта.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeasonalMenuService {

    /** Допустимые значения мультипликаторов в UI (шаг 0.1). */
    private static final BigDecimal[] MULTIPLIER_STEPS = {
            new BigDecimal("0.50"), new BigDecimal("0.60"), new BigDecimal("0.70"),
            new BigDecimal("0.80"), new BigDecimal("0.90"), new BigDecimal("1.00"),
            new BigDecimal("1.10"), new BigDecimal("1.20"), new BigDecimal("1.30"),
            new BigDecimal("1.40"), new BigDecimal("1.50")
    };

    /** Допустимые fixed-интервалы (1..60). */
    private static final int[] INTERVAL_STEPS = {1, 2, 3, 5, 7, 10, 14, 21, 28, 35, 45, 60};

    private final UserRepository userRepository;
    private final SeasonResolver seasonResolver;

    public void sendScreen(User user, Integer messageId, TelegramClient client) {
        renderScreen(user, messageId, client);
    }

    @Transactional
    public void toggleEnabled(User user, Integer messageId, TelegramClient client) {
        user.setSeasonalEnabled(!user.isSeasonalEnabled());
        userRepository.save(user);
        log.info("Seasonal toggle: user={}, enabled={}",
                user.getTelegramChatId(), user.isSeasonalEnabled());
        renderScreen(user, messageId, client);
    }

    @Transactional
    public void setMode(User user, SeasonalMode mode, Integer messageId, TelegramClient client) {
        user.setSeasonalMode(mode);
        userRepository.save(user);
        log.info("Seasonal mode: user={}, mode={}", user.getTelegramChatId(), mode);
        renderScreen(user, messageId, client);
    }

    /**
     * Циклически переключает multiplier для указанного сезона по шагам.
     */
    @Transactional
    public void cycleMultiplier(User user, Season season, Integer messageId,
                                TelegramClient client) {
        BigDecimal current = season.isSummer()
                ? user.getSummerMultiplier()
                : user.getWinterMultiplier();
        BigDecimal next = nextStep(MULTIPLIER_STEPS, current);
        if (season.isSummer()) {
            user.setSummerMultiplier(next);
        } else {
            user.setWinterMultiplier(next);
        }
        userRepository.save(user);
        log.info("Seasonal multiplier cycle: user={}, season={}, new={}",
                user.getTelegramChatId(), season, next);
        renderScreen(user, messageId, client);
    }

    /**
     * Циклически переключает fixed-интервал для указанного сезона.
     */
    @Transactional
    public void cycleInterval(User user, Season season, Integer messageId,
                              TelegramClient client) {
        Integer current = season.isSummer()
                ? user.getSummerIntervalOverrideDays()
                : user.getWinterIntervalOverrideDays();
        int next = nextStep(INTERVAL_STEPS, current == null ? 7 : current);
        if (season.isSummer()) {
            user.setSummerIntervalOverrideDays(next);
        } else {
            user.setWinterIntervalOverrideDays(next);
        }
        userRepository.save(user);
        log.info("Seasonal interval cycle: user={}, season={}, new={}",
                user.getTelegramChatId(), season, next);
        renderScreen(user, messageId, client);
    }

    /** Сброс fixed-override на null (значит «использовать базовый интервал»). */
    @Transactional
    public void clearInterval(User user, Season season, Integer messageId,
                              TelegramClient client) {
        if (season.isSummer()) {
            user.setSummerIntervalOverrideDays(null);
        } else {
            user.setWinterIntervalOverrideDays(null);
        }
        userRepository.save(user);
        renderScreen(user, messageId, client);
    }

    // =================================================================
    // private
    // =================================================================

    private void renderScreen(User user, Integer messageId, TelegramClient client) {
        String text = buildText(user);
        InlineKeyboardMarkup keyboard = buildKeyboard(user);

        if (messageId != null) {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(user.getTelegramChatId().toString())
                    .messageId(messageId)
                    .text(text)
                    .replyMarkup(keyboard)
                    .build();
            try {
                client.execute(edit);
                return;
            } catch (TelegramApiException e) {
                log.warn("Edit seasonal screen failed (id={}): {}, sending new",
                        messageId, e.getMessage());
            }
        }

        SendMessage send = SendMessage.builder()
                .chatId(user.getTelegramChatId().toString())
                .text(text)
                .replyMarkup(keyboard)
                .build();
        try {
            client.execute(send);
        } catch (TelegramApiException e) {
            log.error("Failed to send seasonal screen: {}", e.getMessage(), e);
        }
    }

    private String buildText(User user) {
        Season current = seasonResolver.currentSeason(user);
        StringBuilder sb = new StringBuilder("🍂 Сезонные интервалы\n\n");
        sb.append("Корректировка интервалов ухода под текущий сезон.\n\n");
        sb.append("Статус: ").append(user.isSeasonalEnabled() ? "✅ Включено" : "⏸ Выключено")
                .append("\n");
        sb.append("Сейчас сезон: ").append(current.displayName()).append("\n");
        sb.append("Режим: ").append(user.getSeasonalMode() == SeasonalMode.MULTIPLIER
                ? "по коэффициенту" : "фиксированные интервалы")
                .append("\n\n");

        if (user.getSeasonalMode() == SeasonalMode.MULTIPLIER) {
            sb.append("🌞 Лето: × ").append(user.getSummerMultiplier()).append("\n");
            sb.append("❄️ Зима: × ").append(user.getWinterMultiplier()).append("\n");
            sb.append("\nКоэффициент применяется к базовому интервалу растения. "
                    + "Например, базовый 10 дней × 0.8 = полив каждые 8 дней.");
        } else {
            sb.append("🌞 Лето: ").append(formatInterval(user.getSummerIntervalOverrideDays()))
                    .append("\n");
            sb.append("❄️ Зима: ").append(formatInterval(user.getWinterIntervalOverrideDays()))
                    .append("\n");
            sb.append("\nЕсли интервал не задан — используется базовый интервал растения.");
        }

        sb.append("\n\nГраницы сезонов:\n");
        sb.append("• Лето: ").append(formatMmdd(user.getSummerStartMmdd())).append("\n");
        sb.append("• Зима: ").append(formatMmdd(user.getWinterStartMmdd())).append("\n");
        sb.append("\nДиапазон фактического интервала: 1–60 дней.");

        return sb.toString();
    }

    private InlineKeyboardMarkup buildKeyboard(User user) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        // Toggle вкл/выкл
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text(user.isSeasonalEnabled() ? "⏸ Выключить" : "✅ Включить")
                        .callbackData("SEASON:TOGGLE")
                        .build())));

        // Mode picker — две кнопки, активный режим помечен
        boolean isMul = user.getSeasonalMode() == SeasonalMode.MULTIPLIER;
        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text((isMul ? "● " : "○ ") + "Коэффициент")
                        .callbackData("SEASON:MODE:MULTIPLIER")
                        .build(),
                InlineKeyboardButton.builder()
                        .text((!isMul ? "● " : "○ ") + "Фиксированный")
                        .callbackData("SEASON:MODE:FIXED")
                        .build())));

        // В зависимости от режима — разный набор кнопок настройки
        if (isMul) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🌞 × " + user.getSummerMultiplier())
                            .callbackData("SEASON:MUL:SUMMER")
                            .build())));
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("❄️ × " + user.getWinterMultiplier())
                            .callbackData("SEASON:MUL:WINTER")
                            .build())));
        } else {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("🌞 " + formatInterval(user.getSummerIntervalOverrideDays()))
                            .callbackData("SEASON:INT:SUMMER")
                            .build(),
                    InlineKeyboardButton.builder()
                            .text("✕")
                            .callbackData("SEASON:INT:SUMMER:CLEAR")
                            .build())));
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("❄️ " + formatInterval(user.getWinterIntervalOverrideDays()))
                            .callbackData("SEASON:INT:WINTER")
                            .build(),
                    InlineKeyboardButton.builder()
                            .text("✕")
                            .callbackData("SEASON:INT:WINTER:CLEAR")
                            .build())));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад в настройки")
                        .callbackData("MENU:SETTINGS")
                        .build())));

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder<?, ?> b = InlineKeyboardMarkup.builder();
        rows.forEach(b::keyboardRow);
        return b.build();
    }

    private static String formatInterval(Integer v) {
        if (v == null) return "не задан";
        return v + " дн.";
    }

    private static String formatMmdd(int mmdd) {
        int month = mmdd / 100;
        int day = mmdd % 100;
        return String.format("%02d.%02d", day, month);
    }

    /** Следующий шаг по циклу. Если current нет в списке — берём первый. */
    private static BigDecimal nextStep(BigDecimal[] steps, BigDecimal current) {
        if (current == null) return steps[0];
        for (int i = 0; i < steps.length; i++) {
            if (steps[i].compareTo(current) == 0) {
                return steps[(i + 1) % steps.length];
            }
        }
        return steps[0];
    }

    private static int nextStep(int[] steps, int current) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == current) {
                return steps[(i + 1) % steps.length];
            }
        }
        return steps[0];
    }
}
