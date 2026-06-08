package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Photo;
import com.plantcare.core.domain.PlantProgressPhoto;
import com.plantcare.core.service.PhotoProgressService;
import com.plantcare.core.service.PhotoProgressService.PhotoPair;
import com.plantcare.core.service.PhotoService;
import com.plantcare.core.service.PhotoTooLargeException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link PhotoProgressController} (issue #253).
 *
 * <p>POST upload (+413 на превышении размера), GET history, GET compare,
 * PATCH frequency.
 */
@WebMvcTest(PhotoProgressController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PhotoProgressControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long PLANT_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PhotoProgressService photoProgressService;

    @MockitoBean
    private PhotoService photoService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(USER_ID);
    }

    private static MockMultipartFile imageFile() {
        return new MockMultipartFile("file", "plant.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});
    }

    private static Photo photo(long id) {
        Photo photo = new Photo("photos/abc", "image/jpeg", 4L, USER_ID, java.time.Instant.now());
        ReflectionTestUtils.setField(photo, "id", id);
        return photo;
    }

    private static PlantProgressPhoto progressPhoto(long id, Photo photo, String caption) {
        PlantProgressPhoto p = new PlantProgressPhoto();
        ReflectionTestUtils.setField(p, "id", id);
        p.setPhoto(photo);
        p.setCaption(caption);
        p.setTakenAt(LocalDateTime.of(2026, 6, 1, 9, 30));
        return p;
    }

    // ------------------------------------------------------------------ POST

    @Test
    void should_return_201_with_item_when_upload_succeeds() throws Exception {
        // arrange
        Photo photo = photo(7L);
        when(photoService.upload(eq(USER_ID), any(byte[].class), eq("image/jpeg"))).thenReturn(photo);
        when(photoProgressService.addPhotoFromStorage(eq(USER_ID), eq(PLANT_ID), eq(photo), eq("Новый лист")))
                .thenReturn(progressPhoto(25L, photo, "Новый лист"));
        when(photoService.presignedUrl(USER_ID, 7L))
                .thenReturn(new URL("https://s3.example.com/photos/abc?sig=1"));

        // act + assert
        mockMvc.perform(multipart("/api/v1/plants/42/photo-progress")
                        .file(imageFile())
                        .param("caption", "Новый лист"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(25))
                .andExpect(jsonPath("$.photoId").value(7))
                .andExpect(jsonPath("$.url").value("https://s3.example.com/photos/abc?sig=1"))
                .andExpect(jsonPath("$.caption").value("Новый лист"));

        verify(photoService).upload(eq(USER_ID), any(byte[].class), eq("image/jpeg"));
        verify(photoProgressService).addPhotoFromStorage(eq(USER_ID), eq(PLANT_ID), eq(photo), eq("Новый лист"));
    }

    @Test
    void should_return_413_when_file_too_large() throws Exception {
        // arrange
        when(photoService.upload(eq(USER_ID), any(byte[].class), eq("image/jpeg")))
                .thenThrow(new PhotoTooLargeException(9_000_000L, 5_242_880L));

        // act + assert
        mockMvc.perform(multipart("/api/v1/plants/42/photo-progress").file(imageFile()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void should_return_409_when_anti_spam_window_hit() throws Exception {
        // arrange
        Photo photo = photo(7L);
        when(photoService.upload(eq(USER_ID), any(byte[].class), eq("image/jpeg"))).thenReturn(photo);
        when(photoProgressService.addPhotoFromStorage(eq(USER_ID), eq(PLANT_ID), eq(photo), any()))
                .thenThrow(new IllegalStateException("Уже есть фото за последние 24 часа"));

        // act + assert
        mockMvc.perform(multipart("/api/v1/plants/42/photo-progress").file(imageFile()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    // ------------------------------------------------------------------ GET history

    @Test
    void should_return_history_page_with_urls_and_dates() throws Exception {
        // arrange
        Photo p1 = photo(7L);
        Photo p2 = photo(8L);
        when(photoProgressService.countHistory(USER_ID, PLANT_ID)).thenReturn(2L);
        when(photoProgressService.getRecent(eq(USER_ID), eq(PLANT_ID), anyInt()))
                .thenReturn(List.of(progressPhoto(25L, p2, "свежее"), progressPhoto(24L, p1, "старое")));
        when(photoService.presignedUrl(USER_ID, 7L)).thenReturn(new URL("https://s3.example.com/photos/abc?sig=1"));
        when(photoService.presignedUrl(USER_ID, 8L)).thenReturn(new URL("https://s3.example.com/photos/def?sig=2"));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/42/photo-progress").param("limit", "10").param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(25))
                .andExpect(jsonPath("$.items[0].url").value("https://s3.example.com/photos/def?sig=2"));
    }

    @Test
    void should_return_400_when_limit_out_of_range() throws Exception {
        // act + assert
        mockMvc.perform(get("/api/v1/plants/42/photo-progress").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ GET compare

    @Test
    void should_return_before_after_pair_for_compare() throws Exception {
        // arrange
        Photo before = photo(7L);
        Photo after = photo(8L);
        when(photoProgressService.compareByIds(USER_ID, PLANT_ID, 24L, 25L))
                .thenReturn(Optional.of(new PhotoPair(
                        progressPhoto(24L, before, "до"), progressPhoto(25L, after, "после"))));
        when(photoService.presignedUrl(USER_ID, 7L)).thenReturn(new URL("https://s3.example.com/photos/abc?sig=1"));
        when(photoService.presignedUrl(USER_ID, 8L)).thenReturn(new URL("https://s3.example.com/photos/def?sig=2"));

        // act + assert
        mockMvc.perform(get("/api/v1/plants/42/photo-progress/compare").param("from", "24").param("to", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.before.id").value(24))
                .andExpect(jsonPath("$.after.id").value(25))
                .andExpect(jsonPath("$.before.url").value("https://s3.example.com/photos/abc?sig=1"))
                .andExpect(jsonPath("$.after.url").value("https://s3.example.com/photos/def?sig=2"));
    }

    @Test
    void should_return_404_when_compare_pair_not_found() throws Exception {
        // arrange
        when(photoProgressService.compareByIds(USER_ID, PLANT_ID, 1L, 2L)).thenReturn(Optional.empty());

        // act + assert
        mockMvc.perform(get("/api/v1/plants/42/photo-progress/compare").param("from", "1").param("to", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ PATCH frequency

    @Test
    void should_set_frequency_and_echo_it() throws Exception {
        // arrange
        com.plantcare.core.domain.Plant plant = com.plantcare.core.domain.Plant.builder()
                .photoProgressFrequency(com.plantcare.core.domain.enums.PhotoProgressFrequency.P2W)
                .build();
        when(photoProgressService.setFrequency(
                eq(USER_ID), eq(PLANT_ID),
                eq(com.plantcare.core.domain.enums.PhotoProgressFrequency.P2W)))
                .thenReturn(plant);

        // act + assert
        mockMvc.perform(patch("/api/v1/plants/42/photo-progress/frequency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frequency\":\"P2W\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frequency").value("P2W"));

        verify(photoProgressService).setFrequency(
                eq(USER_ID), eq(PLANT_ID),
                eq(com.plantcare.core.domain.enums.PhotoProgressFrequency.P2W));
    }

    @Test
    void should_return_422_when_frequency_unknown() throws Exception {
        // act + assert
        mockMvc.perform(patch("/api/v1/plants/42/photo-progress/frequency")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"frequency\":\"WEEKLY\"}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
