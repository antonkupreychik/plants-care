package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.SeasonalMode;
import com.plantcare.core.service.AccountDeletionService;
import com.plantcare.core.service.UserProfileService;
import com.plantcare.core.service.UserProfileService.Profile;
import com.plantcare.core.service.UserProfileService.ProfileUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link MeController} (issue #182 + расширение #180) — JSON-форма
 * {@code GET /api/v1/me} (включая новые поля профиля и linkage-booleans), allow-list
 * (наружу не утекают чувствительные колонки), PATCH-семантика частичного апдейта
 * (включая seasonal/weather тогглы), маппинг ошибок (400 на невалидный IANA-tz, на
 * совпадение тихих часов, на нарушение {@code @Pattern}, на плохой enum), 401 без
 * аутентификации.
 *
 * <p>{@link UserProfileService} замокан — здесь проверяется только веб-слой:
 * сериализация {@link Profile} → {@code MeResponse}, парсинг/маппинг тела PATCH в
 * {@link ProfileUpdate} и Bean Validation сгенерированного DTO до бизнес-логики.
 * Фильтры выключены, импортируется {@link ApiExceptionHandler},
 * {@link CurrentUserProvider} застаблен на текущего пользователя.
 */
@WebMvcTest(MeController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class MeControllerTest {

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.of(2026, 1, 15, 8, 30, 0, 0, ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService userProfileService;

    @MockitoBean
    private AccountDeletionService accountDeletionService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private com.plantcare.core.seasonal.service.SeasonalSettingsService seasonalSettingsService;

    @BeforeEach
    void stubCurrentUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(currentUserProvider.currentUser()).thenReturn(user);
        when(currentUserProvider.currentUserId()).thenReturn(7L);
    }

    // ------------------------------------------------------------------ GET happy

    @Test
    void should_return_full_extended_profile_shape_with_linkage_booleans_when_getting_me() throws Exception {
        // arrange — AC #1: расширенная форма #180 — id/email/emailVerified/createdAt,
        // seasonalMode-строка, featureFlags-map и linkage booleans
        Profile profile = new Profile(
                42L, "user@example.com", true, CREATED_AT,
                "Антон", null, 12, 3, 348L, 0,
                LocalTime.of(22, 0), LocalTime.of(8, 0),
                "Europe/Moscow", "ru",
                true, SeasonalMode.FIXED, true,
                Map.of("sharing", "true"),
                true, false, true, true,
                false,
                "https://plants-care.example.com/calendar/abc123.ics");
        when(userProfileService.getProfile(any(User.class))).thenReturn(profile);

        // act + assert
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.name").value("Антон"))
                .andExpect(jsonPath("$.avatar").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.plantsTotal").value(12))
                .andExpect(jsonPath("$.tasksToday").value(3))
                .andExpect(jsonPath("$.totalCareEvents").value(348))
                .andExpect(jsonPath("$.notificationsUnread").value(0))
                .andExpect(jsonPath("$.quietHoursStart").value("22:00"))
                .andExpect(jsonPath("$.quietHoursEnd").value("08:00"))
                .andExpect(jsonPath("$.timezone").value("Europe/Moscow"))
                .andExpect(jsonPath("$.locale").value("ru"))
                .andExpect(jsonPath("$.seasonalEnabled").value(true))
                .andExpect(jsonPath("$.seasonalMode").value("FIXED"))
                .andExpect(jsonPath("$.weatherEnabled").value(true))
                .andExpect(jsonPath("$.featureFlags.sharing").value("true"))
                .andExpect(jsonPath("$.appleLinked").value(true))
                .andExpect(jsonPath("$.googleLinked").value(false))
                .andExpect(jsonPath("$.emailLinked").value(true))
                .andExpect(jsonPath("$.telegramLinked").value(true))
                .andExpect(jsonPath("$.calendarSubscriptionUrl")
                        .value("https://plants-care.example.com/calendar/abc123.ics"));
    }

    @Test
    void should_return_null_calendar_subscription_url_when_token_not_yet_generated() throws Exception {
        // arrange — edge (issue #208): calendarSubscriptionUrl null, если токен ещё не создан
        Profile profile = new Profile(
                42L, "user@example.com", true, CREATED_AT,
                "Антон", null, 0, 0, 0L, 0,
                LocalTime.of(22, 0), LocalTime.of(8, 0),
                "Europe/Moscow", "ru",
                false, SeasonalMode.MULTIPLIER, false,
                Map.of(),
                false, false, true, true,
                false,
                null);
        when(userProfileService.getProfile(any(User.class))).thenReturn(profile);

        // act + assert
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarSubscriptionUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void should_not_leak_sensitive_columns_in_me_response_when_getting_me() throws Exception {
        // arrange — AC #2 (CRITICAL, leak-regression guard): наружу НЕ должны утекать
        // appleSubject/googleSubject/telegramChatId/calendarToken/stateData/
        // conversationState/isBlocked/weatherLat/weatherLon. Профиль их даже не несёт —
        // assert на отсутствие JSON-путей ловит регрессию, если кто-то добавит их в MeResponse.
        Profile profile = new Profile(
                42L, "user@example.com", true, CREATED_AT,
                "Антон", null, 1, 0, 0L, 0,
                LocalTime.of(22, 0), LocalTime.of(8, 0),
                "Europe/Moscow", "ru",
                false, SeasonalMode.MULTIPLIER, false,
                Map.of(),
                true, false, true, true,
                false,
                null);
        when(userProfileService.getProfile(any(User.class))).thenReturn(profile);

        // act + assert
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appleSubject").doesNotExist())
                .andExpect(jsonPath("$.googleSubject").doesNotExist())
                .andExpect(jsonPath("$.telegramChatId").doesNotExist())
                .andExpect(jsonPath("$.calendarToken").doesNotExist())
                .andExpect(jsonPath("$.stateData").doesNotExist())
                .andExpect(jsonPath("$.conversationState").doesNotExist())
                .andExpect(jsonPath("$.isBlocked").doesNotExist())
                .andExpect(jsonPath("$.blocked").doesNotExist())
                .andExpect(jsonPath("$.weatherLat").doesNotExist())
                .andExpect(jsonPath("$.weatherLon").doesNotExist());
    }

    @Test
    void should_serialize_null_email_when_telegram_only_user() throws Exception {
        // arrange — edge: чисто Telegram-юзер, email == null, emailLinked == false
        Profile profile = new Profile(
                42L, null, false, CREATED_AT,
                "Аноним", null, 0, 0, 0L, 0,
                LocalTime.of(22, 0), LocalTime.of(9, 0),
                "UTC", "ru",
                false, SeasonalMode.MULTIPLIER, false,
                Map.of(),
                false, false, false, true,
                false,
                null);
        when(userProfileService.getProfile(any(User.class))).thenReturn(profile);

        // act + assert
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.emailLinked").value(false))
                .andExpect(jsonPath("$.telegramLinked").value(true));
    }

    @Test
    void should_pass_current_user_not_request_to_service_when_getting_me() throws Exception {
        // arrange — скоуп берётся из CurrentUserProvider
        when(userProfileService.getProfile(any(User.class))).thenReturn(sampleProfile());

        // act
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isOk());

        // assert — сервис вызван с пользователем из провайдера (id=7)
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userProfileService).getProfile(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(7L);
    }

    // ------------------------------------------------------------------ PATCH partial

    @Test
    void should_send_only_locale_and_leave_other_fields_null_when_patching_locale_only() throws Exception {
        // arrange — AC #3: тело только с locale; omitted-поля НЕ должны занулять остальное
        stubUpdateEcho();

        String body = """
                {"locale": "en"}
                """;

        // act
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // assert — в сервис ушёл ProfileUpdate с заполненным только locale
        ProfileUpdate sent = captureUpdate();
        assertThat(sent.locale()).isEqualTo("en");
        assertThat(sent.quietHoursStart()).isNull();
        assertThat(sent.quietHoursEnd()).isNull();
        assertThat(sent.timezone()).isNull();
        assertThat(sent.seasonalEnabled()).isNull();
        assertThat(sent.seasonalMode()).isNull();
        assertThat(sent.weatherEnabled()).isNull();
    }

    @Test
    void should_send_only_quiet_hours_and_leave_tz_locale_null_when_patching_quiet_hours_only() throws Exception {
        // arrange — AC #3: только тихие часы; tz/locale/seasonal/weather не трогаем
        stubUpdateEcho();

        String body = """
                {"quietHoursStart": "23:00", "quietHoursEnd": "07:30"}
                """;

        // act
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // assert — только quietHours заполнены, как LocalTime; tz/locale остались null
        ProfileUpdate sent = captureUpdate();
        assertThat(sent.quietHoursStart()).isEqualTo(LocalTime.of(23, 0));
        assertThat(sent.quietHoursEnd()).isEqualTo(LocalTime.of(7, 30));
        assertThat(sent.timezone()).isNull();
        assertThat(sent.locale()).isNull();
    }

    @Test
    void should_send_only_seasonal_and_weather_toggles_when_patching_them() throws Exception {
        // arrange — AC #3: {seasonalEnabled, seasonalMode, weatherEnabled} обновляют только себя,
        // остальные поля апдейта остаются null (partial semantics)
        Profile updated = new Profile(
                42L, "user@example.com", true, CREATED_AT,
                "Антон", null, 1, 0, 0L, 0,
                LocalTime.of(22, 0), LocalTime.of(8, 0),
                "Europe/Moscow", "ru",
                true, SeasonalMode.FIXED, true,
                Map.of(),
                false, false, true, true,
                false,
                null);
        when(userProfileService.updateProfile(any(User.class), any(ProfileUpdate.class)))
                .thenReturn(updated);

        String body = """
                {"seasonalEnabled": true, "seasonalMode": "FIXED", "weatherEnabled": true}
                """;

        // act + assert — ответ отражает новые тогглы
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasonalEnabled").value(true))
                .andExpect(jsonPath("$.seasonalMode").value("FIXED"))
                .andExpect(jsonPath("$.weatherEnabled").value(true));

        // assert — в сервис ушли только тогглы, остальное null
        ProfileUpdate sent = captureUpdate();
        assertThat(sent.seasonalEnabled()).isTrue();
        assertThat(sent.seasonalMode()).isEqualTo(SeasonalMode.FIXED);
        assertThat(sent.weatherEnabled()).isTrue();
        assertThat(sent.quietHoursStart()).isNull();
        assertThat(sent.quietHoursEnd()).isNull();
        assertThat(sent.timezone()).isNull();
        assertThat(sent.locale()).isNull();
    }

    @Test
    void should_send_empty_update_when_patch_body_is_empty_object() throws Exception {
        // arrange — AC #3 граница: пустое тело = ничего не менять, все поля null
        stubUpdateEcho();

        // act
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // assert
        ProfileUpdate sent = captureUpdate();
        assertThat(sent.quietHoursStart()).isNull();
        assertThat(sent.quietHoursEnd()).isNull();
        assertThat(sent.timezone()).isNull();
        assertThat(sent.locale()).isNull();
        assertThat(sent.seasonalEnabled()).isNull();
        assertThat(sent.seasonalMode()).isNull();
        assertThat(sent.weatherEnabled()).isNull();
    }

    // ------------------------------------------------------------------ PATCH timezone

    @Test
    void should_pass_iana_timezone_through_and_reflect_in_response_when_patching_timezone() throws Exception {
        // arrange — AC: валидный IANA tz прокидывается в сервис и возвращается в ответе
        Profile updated = new Profile(
                42L, "user@example.com", true, CREATED_AT,
                "Антон", null, 1, 0, 0L, 0,
                LocalTime.of(22, 0), LocalTime.of(9, 0),
                "Asia/Almaty", "ru",
                false, SeasonalMode.MULTIPLIER, false,
                Map.of(),
                false, false, true, true,
                false,
                null);
        when(userProfileService.updateProfile(any(User.class), any(ProfileUpdate.class)))
                .thenReturn(updated);

        String body = """
                {"timezone": "Asia/Almaty"}
                """;

        // act + assert — ответ отражает новую зону
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Almaty"));

        // assert — именно эта зона ушла в сервис
        assertThat(captureUpdate().timezone()).isEqualTo("Asia/Almaty");
    }

    // ------------------------------------------------------------------ PATCH failures

    @Test
    void should_return_400_bad_request_when_timezone_is_not_valid_iana() throws Exception {
        // arrange — сервис кидает IllegalArgumentException на невалидный IANA-id
        when(userProfileService.updateProfile(any(User.class), any(ProfileUpdate.class)))
                .thenThrow(new IllegalArgumentException("Invalid IANA timezone: Mars/Phobos"));

        String body = """
                {"timezone": "Mars/Phobos"}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void should_return_400_bad_request_when_quiet_hours_start_equals_end() throws Exception {
        // arrange — AC #4(a): сервис кидает IllegalArgumentException при равных тихих часах
        when(userProfileService.updateProfile(any(User.class), any(ProfileUpdate.class)))
                .thenThrow(new IllegalArgumentException(
                        "quietHoursStart must not equal quietHoursEnd: 22:00"));

        String body = """
                {"quietHoursStart": "22:00", "quietHoursEnd": "22:00"}
                """;

        // act + assert — оба поля прошли @Pattern, бизнес-валидация в сервисе → 400 BAD_REQUEST
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void should_return_400_validation_error_when_quiet_hours_format_is_invalid() throws Exception {
        // arrange — "25:99" нарушает @Pattern(^([01]\d|2[0-3]):[0-5]\d$) на DTO,
        // отбивается Bean Validation ДО сервиса
        String body = """
                {"quietHoursStart": "25:99"}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(userProfileService, never()).updateProfile(any(User.class), any(ProfileUpdate.class));
    }

    @Test
    void should_return_400_validation_error_when_quiet_hours_end_has_bad_separator() throws Exception {
        // arrange — "08-00" (дефис вместо двоеточия) тоже не проходит @Pattern
        String body = """
                {"quietHoursEnd": "08-00"}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(userProfileService, never()).updateProfile(any(User.class), any(ProfileUpdate.class));
    }

    @Test
    void should_return_400_when_locale_not_ru_or_en() throws Exception {
        // arrange — на DTO стоит @Pattern(^(ru|en)$), поэтому "de"
        // отбивается Bean Validation ДО сервиса — тот же путь, что у тихих часов
        String body = """
                {"locale": "de"}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(userProfileService, never()).updateProfile(any(User.class), any(ProfileUpdate.class));
    }

    @Test
    void should_return_400_when_seasonal_mode_not_multiplier_or_fixed() throws Exception {
        // arrange — AC #5: на DTO теперь стоит @Pattern(^(MULTIPLIER|FIXED)$) (как у locale),
        // поэтому "WEEKLY" (вне допустимых режимов) отбивается Bean Validation ДО сервиса —
        // тот же путь 400 VALIDATION_ERROR, что у плохого locale и тихих часов.
        String body = """
                {"seasonalMode": "WEEKLY"}
                """;

        // act + assert
        mockMvc.perform(patch("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(userProfileService, never()).updateProfile(any(User.class), any(ProfileUpdate.class));
    }

    @Test
    void should_return_401_when_no_authenticated_user_on_get() throws Exception {
        // arrange — нет JWT в SecurityContext
        when(currentUserProvider.currentUser())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        // act + assert
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    // ------------------------------------------------------------------ DELETE

    @Test
    void should_return_204_and_call_deletion_service_when_deleting_account() throws Exception {
        // arrange
        doNothing().when(accountDeletionService).deleteAccount(anyLong());

        // act + assert
        mockMvc.perform(delete("/api/v1/me"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(accountDeletionService).deleteAccount(captor.capture());
        assertThat(captor.getValue()).isEqualTo(7L);
    }

    @Test
    void should_return_204_and_use_user_id_not_full_user_when_deleting_account() throws Exception {
        // arrange — deleteMe использует currentUserId(), а не currentUser() (идемпотентность:
        // пользователь может уже не существовать → currentUser() бросил бы 404)
        doNothing().when(accountDeletionService).deleteAccount(anyLong());

        // act + assert
        mockMvc.perform(delete("/api/v1/me"))
                .andExpect(status().isNoContent());

        // currentUser() НЕ вызывается при DELETE (только currentUserId())
        verify(currentUserProvider, never()).currentUser();
        verify(currentUserProvider).currentUserId();
    }

    @Test
    void should_return_401_when_no_authenticated_user_on_delete() throws Exception {
        // arrange — нет JWT в SecurityContext
        when(currentUserProvider.currentUserId())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        // act + assert
        mockMvc.perform(delete("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    // ===================== helpers =====================

    /** Возвращает «как есть» собранный профиль, чтобы PATCH вернул 200 и не падал на null. */
    private void stubUpdateEcho() {
        when(userProfileService.updateProfile(any(User.class), any(ProfileUpdate.class)))
                .thenReturn(sampleProfile());
    }

    private static Profile sampleProfile() {
        return new Profile(
                42L, "user@example.com", true, CREATED_AT,
                "Антон", null, 0, 0, 0L, 0,
                LocalTime.of(22, 0), LocalTime.of(9, 0),
                "Europe/Moscow", "ru",
                false, SeasonalMode.MULTIPLIER, false,
                Map.of(),
                false, false, true, true,
                false,
                null);
    }

    private ProfileUpdate captureUpdate() {
        ArgumentCaptor<ProfileUpdate> captor = ArgumentCaptor.forClass(ProfileUpdate.class);
        verify(userProfileService).updateProfile(any(User.class), captor.capture());
        return captor.getValue();
    }
}
