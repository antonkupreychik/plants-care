package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.auth.exception.AuthTokenException;
import com.plantcare.core.domain.User;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Слайс-тест {@link MeController} (issue #182) — JSON-форма {@code GET /api/v1/me},
 * PATCH-семантика частичного апдейта, маппинг ошибок (400 на невалидный IANA-tz и
 * на нарушение {@code @Pattern} тихих часов), 401 без аутентификации.
 *
 * <p>{@link UserProfileService} замокан — здесь проверяется только веб-слой:
 * сериализация {@link Profile} → {@code MeResponse}, парсинг/маппинг тела PATCH в
 * {@link ProfileUpdate} и Bean Validation сгенерированного DTO до бизнес-логики.
 * Зеркалит {@link ReportsControllerTest}/{@link ShoppingControllerTest}: фильтры
 * выключены, импортируется {@link ApiExceptionHandler}, {@link CurrentUserProvider}
 * застаблен на текущего пользователя.
 */
@WebMvcTest(MeController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService userProfileService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void stubCurrentUser() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(currentUserProvider.currentUser()).thenReturn(user);
    }

    // ------------------------------------------------------------------ GET happy

    @Test
    void should_return_full_profile_shape_with_hhmm_quiet_hours_when_getting_me() throws Exception {
        // arrange — AC #1: профиль + счётчики + настройки; quietHours форматируются в HH:mm
        Profile profile = new Profile(
                "Антон", null, 12, 3, 0,
                LocalTime.of(22, 0), LocalTime.of(8, 0),
                "Europe/Moscow", "ru");
        when(userProfileService.getProfile(any(User.class))).thenReturn(profile);

        // act + assert
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Антон"))
                .andExpect(jsonPath("$.avatar").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.plantsTotal").value(12))
                .andExpect(jsonPath("$.tasksToday").value(3))
                .andExpect(jsonPath("$.notificationsUnread").value(0))
                .andExpect(jsonPath("$.quietHoursStart").value("22:00"))
                .andExpect(jsonPath("$.quietHoursEnd").value("08:00"))
                .andExpect(jsonPath("$.timezone").value("Europe/Moscow"))
                .andExpect(jsonPath("$.locale").value("ru"));
    }

    @Test
    void should_pass_current_user_not_request_to_service_when_getting_me() throws Exception {
        // arrange — скоуп берётся из CurrentUserProvider
        Profile profile = new Profile("Аноним", null, 0, 0, 0,
                LocalTime.of(22, 0), LocalTime.of(9, 0), "UTC", "ru");
        when(userProfileService.getProfile(any(User.class))).thenReturn(profile);

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
        // arrange — AC #2: тело только с locale; omitted-поля НЕ должны занулять остальное
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
    }

    @Test
    void should_send_only_quiet_hours_and_leave_tz_locale_null_when_patching_quiet_hours_only() throws Exception {
        // arrange — AC #2: только тихие часы; tz/locale не трогаем
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
    void should_send_empty_update_when_patch_body_is_empty_object() throws Exception {
        // arrange — AC #2 граница: пустое тело = ничего не менять, все поля null
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
    }

    // ------------------------------------------------------------------ PATCH timezone

    @Test
    void should_pass_iana_timezone_through_and_reflect_in_response_when_patching_timezone() throws Exception {
        // arrange — AC #3: валидный IANA tz прокидывается в сервис и возвращается в ответе
        Profile updated = new Profile("Антон", null, 1, 0, 0,
                LocalTime.of(22, 0), LocalTime.of(9, 0), "Asia/Almaty", "ru");
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
        // arrange — AC #5: сервис кидает IllegalArgumentException на невалидный IANA-id
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
    void should_return_400_validation_error_when_quiet_hours_format_is_invalid() throws Exception {
        // arrange — AC #6: "25:99" нарушает @Pattern(^([01]\d|2[0-3]):[0-5]\d$) на DTO,
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
        // arrange — AC #6: "08-00" (дефис вместо двоеточия) тоже не проходит @Pattern
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
        // arrange — AC #6 (locale): на DTO стоит @Pattern(^(ru|en)$), поэтому "de"
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
    void should_return_401_when_no_authenticated_user_on_get() throws Exception {
        // arrange — нет JWT в SecurityContext
        when(currentUserProvider.currentUser())
                .thenThrow(AuthTokenException.invalid("No authenticated user in security context"));

        // act + assert
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    // ===================== helpers =====================

    /** Возвращает «как есть» собранный профиль, чтобы PATCH вернул 200 и не падал на null. */
    private void stubUpdateEcho() {
        Profile echo = new Profile("Антон", null, 0, 0, 0,
                LocalTime.of(22, 0), LocalTime.of(9, 0), "Europe/Moscow", "ru");
        when(userProfileService.updateProfile(any(User.class), any(ProfileUpdate.class)))
                .thenReturn(echo);
    }

    private ProfileUpdate captureUpdate() {
        ArgumentCaptor<ProfileUpdate> captor = ArgumentCaptor.forClass(ProfileUpdate.class);
        verify(userProfileService).updateProfile(any(User.class), captor.capture());
        return captor.getValue();
    }
}
