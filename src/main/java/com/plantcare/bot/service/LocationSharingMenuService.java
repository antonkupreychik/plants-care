package com.plantcare.bot.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.LocationInvite;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.LocationSharingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Bot-слой совместного ухода (issue #77): кнопка «Поделиться доступом» и
 * отправка deep link. Бизнес-логика — в {@link LocationSharingService}.
 */
@Slf4j
@Service
public class LocationSharingMenuService {

    /** Префикс payload в deep link: {@code t.me/<bot>?start=invite_<token>}. */
    public static final String INVITE_PAYLOAD_PREFIX = "invite_";

    private final LocationService locationService;
    private final LocationSharingService sharingService;
    private final String botUsername;

    public LocationSharingMenuService(
            LocationService locationService,
            LocationSharingService sharingService,
            @Value("${telegram.bot.username}") String botUsername
    ) {
        this.locationService = locationService;
        this.sharingService = sharingService;
        this.botUsername = botUsername;
    }

    /**
     * Сгенерировать приглашение к локации и отправить владельцу готовую ссылку.
     */
    public void sendInviteLink(User owner, Long locationId, TelegramClient client) {
        Location location = locationService.getUserLocationOrThrow(owner.getId(), locationId);
        LocationInvite invite = sharingService.createInvite(owner.getId(), locationId);

        String link = buildDeepLink(invite.getToken());

        String text = """
                🤝 *Поделиться уходом за «%s»*

                Отправь эту одноразовую ссылку тому, кого хочешь пригласить:

                %s

                Он перейдёт по ней, и вы будете ухаживать за растениями вместе. Ссылка действует 7 дней и срабатывает один раз."""
                .formatted(location.getDisplayName(), link);

        SendMessage message = SendMessage.builder()
                .chatId(owner.getTelegramChatId().toString())
                .text(text)
                .parseMode("Markdown")
                .build();

        execute(client, message);
    }

    /**
     * Deep link вида {@code https://t.me/<bot>?start=invite_<token>}.
     */
    public String buildDeepLink(String token) {
        return "https://t.me/" + botUsername + "?start=" + INVITE_PAYLOAD_PREFIX + token;
    }

    private void execute(TelegramClient client, SendMessage message) {
        try {
            client.execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send sharing invite link", e);
        }
    }
}
