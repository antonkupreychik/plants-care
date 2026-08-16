package com.plantcare.bot.service;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.LocationInvite;
import com.plantcare.core.domain.User;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.LocationSharingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-тесты для LocationSharingMenuService")
class LocationSharingMenuServiceTest {

    @Mock private LocationService locationService;
    @Mock private LocationSharingService sharingService;
    @Mock private TelegramClient telegramClient;

    private LocationSharingMenuService service;
    private User owner;

    @BeforeEach
    void setUp() {
        service = new LocationSharingMenuService(locationService, sharingService, "plant_care_bot");
        owner = User.builder()
                .telegramChatId(555L)
                .timezone("UTC")
                .build();
    }

    private static Location location(long id, String name, String emoji) {
        Location location = Location.builder().name(name).emoji(emoji).build();
        ReflectionTestUtils.setField(location, "id", id);
        return location;
    }

    // ==================== buildDeepLink ====================

    @Test
    @DisplayName("buildDeepLink — собирает t.me-ссылку с payload-префиксом и токеном")
    void shouldBuildDeepLinkWithInvitePrefix() {
        String link = service.buildDeepLink("abc123");

        assertThat(link).isEqualTo("https://t.me/plant_care_bot?start=invite_abc123");
    }

    // ==================== sendInviteLink ====================

    @Test
    @DisplayName("sendInviteLink — отправляет владельцу сообщение с готовой ссылкой и названием комнаты")
    void shouldSendInviteLinkMessageToOwner() throws TelegramApiException {
        Location loc = location(3L, "Гостиная", "🛋");
        LocationInvite invite = new LocationInvite();
        invite.setToken("tok-777");
        when(locationService.getUserLocationOrThrow(owner.getId(), 3L)).thenReturn(loc);
        when(sharingService.createInvite(owner.getId(), 3L)).thenReturn(invite);

        service.sendInviteLink(owner, 3L, telegramClient);

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        SendMessage sent = captor.getValue();

        assertThat(sent.getChatId()).isEqualTo(owner.getTelegramChatId().toString());
        assertThat(sent.getText())
                .contains("Поделиться уходом за «🛋 Гостиная»")
                .contains("https://t.me/plant_care_bot?start=invite_tok-777")
                .contains("действует 7 дней и срабатывает один раз");
        assertThat(sent.getParseMode()).isEqualTo("Markdown");
    }

    @Test
    @DisplayName("sendInviteLink — TelegramApiException при отправке не пробрасывается наружу")
    void shouldSwallowTelegramExceptionOnInviteSend() throws TelegramApiException {
        Location loc = location(4L, "Кухня", null);
        LocationInvite invite = new LocationInvite();
        invite.setToken("tok-999");
        when(locationService.getUserLocationOrThrow(owner.getId(), 4L)).thenReturn(loc);
        when(sharingService.createInvite(owner.getId(), 4L)).thenReturn(invite);
        doThrow(new TelegramApiException("network down")).when(telegramClient).execute(any(SendMessage.class));

        service.sendInviteLink(owner, 4L, telegramClient);

        verify(telegramClient).execute(any(SendMessage.class));
    }
}
