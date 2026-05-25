package com.plantcare.bot.service;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.PlantRepository;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Меню "Мои растения" — точка входа в детальный просмотр растения (issue #26).
 *
 * Сценарии навигации:
 *   - MENU:ALL_PLANTS — пользователь жмёт в главном меню «📋 Все растения» → список
 *     присылается новым сообщением.
 *   - PLANT:LIST — пользователь возвращается из карточки → список рендерится тем
 *     же EditMessageText, что и карточка (не плодим сообщения, см. ТЗ).
 *   - Клик по растению из списка → карточка рендерится в том же сообщении через
 *     EditMessageText.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlantMenuService {

    private final PlantRepository plantRepository;
    private final HealthScoreService healthScoreService;

    /**
     * Показать список растений пользователя.
     *
     * @param user      пользователь
     * @param messageId если не null — список рендерится EditMessageText в этом
     *                  сообщении (для возврата из карточки в том же месте чата).
     *                  Если null — отправляется новым сообщением.
     * @param client    telegram client
     */
    @Transactional(readOnly = true)
    public void sendMyPlantsList(User user, Integer messageId, TelegramClient client) {
        List<Plant> plants = plantRepository
                .findAllByUserIdAndArchivedAtIsNullOrderByNameAsc(user.getId());

        // Инициализируем lazy-локации, потому что они нам нужны в подписи для каждой
        // строки списка. Делаем это здесь, пока сессия открыта.
        plants.forEach(p -> {
            if (p.getLocation() != null) {
                p.getLocation().getName();
                p.getLocation().getEmoji();
            }
        });

        // issue #117: счётчик архива для кнопки «📦 Архив (N)».
        long archivedCount = plantRepository.countByUserIdAndArchivedAtIsNotNull(user.getId());

        String text = buildListText(plants);
        InlineKeyboardMarkup keyboard = buildListKeyboard(plants, archivedCount);

        sendOrEdit(user.getTelegramChatId(), messageId, text, keyboard, client);
    }

    private String buildListText(List<Plant> plants) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Мои растения*\n\n");

        if (plants.isEmpty()) {
            sb.append("Пока пусто. Добавь первое растение из главного меню.");
            return sb.toString();
        }

        sb.append("Всего: ").append(plants.size()).append("\n");

        // issue #138: сводка по зонам health-score. Растения с «мало данных»
        // (<3 действий) в сводку не попадают — у них балл ещё не считается.
        appendHealthSummary(sb, plants);

        sb.append("\nОткрой карточку, чтобы увидеть статус ухода и быстро отметить полив.");
        return sb.toString();
    }

    /**
     * Сводка «🟢 N · 🟡 N · 🔴 N» по активным растениям коллекции (issue #138).
     * Печатается только если есть хотя бы одно растение с достаточными данными.
     */
    private void appendHealthSummary(StringBuilder sb, List<Plant> plants) {
        int green = 0;
        int yellow = 0;
        int red = 0;
        for (Plant plant : plants) {
            HealthScoreService.HealthScore health = healthScoreService.computeForPlant(plant);
            if (health.insufficientData()) {
                continue;
            }
            switch (health.zone()) {
                case GREEN -> green++;
                case YELLOW -> yellow++;
                case RED -> red++;
            }
        }
        if (green + yellow + red == 0) {
            return;
        }
        sb.append("🟢 ").append(green)
                .append(" · 🟡 ").append(yellow)
                .append(" · 🔴 ").append(red)
                .append("\n");
    }

    private InlineKeyboardMarkup buildListKeyboard(List<Plant> plants, long archivedCount) {
        List<InlineKeyboardRow> rows = new ArrayList<>();

        for (Plant plant : plants) {
            String label = "🌿 " + plant.getName();
            if (plant.getLocation() != null) {
                String emoji = plant.getLocation().getEmoji();
                String locTag = (emoji != null && !emoji.isBlank())
                        ? " " + emoji
                        : " · " + plant.getLocation().getName();
                label = label + locTag;
            }

            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text(label)
                            .callbackData("PLANT:VIEW:" + plant.getId())
                            .build()
            )));
        }

        // issue #117: кнопка «📦 Архив (N)» — только если есть архивные растения.
        if (archivedCount > 0) {
            rows.add(new InlineKeyboardRow(List.of(
                    InlineKeyboardButton.builder()
                            .text("📦 Архив (" + archivedCount + ")")
                            .callbackData("ARCHIVE:LIST")
                            .build()
            )));
        }

        rows.add(new InlineKeyboardRow(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ В главное меню")
                        .callbackData("MENU:BACK")
                        .build()
        )));

        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void sendOrEdit(
            Long chatId,
            Integer messageId,
            String text,
            InlineKeyboardMarkup keyboard,
            TelegramClient client
    ) {
        if (messageId != null) {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(chatId.toString())
                    .messageId(messageId)
                    .text(text)
                    .parseMode("Markdown")
                    .replyMarkup(keyboard)
                    .build();
            try {
                client.execute(edit);
                return;
            } catch (TelegramApiException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("message is not modified")) {
                    return;
                }
                log.warn("Failed to edit plants list (chat={}, msg={}): {}",
                        chatId, messageId, e.getMessage());
                // Fallback: пришлём новым сообщением.
            }
        }

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send plants list (chat={}): {}", chatId, e.getMessage());
        }
    }
}
