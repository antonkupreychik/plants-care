package com.plantcare.api.v1;

import com.plantcare.api.ApiExceptionHandler;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Photo;
import com.plantcare.core.service.PhotoService;
import com.plantcare.core.service.PhotoTooLargeException;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URL;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @WebMvcTest} для {@link PhotoController} (issue #90, Slice A).
 *
 * <p>POST /api/v1/photos: 201 + {@code { id, url }}; превышение размера → 413.
 * GET /api/v1/photos/{id}: 302 c presigned URL в Location; 404 на чужое/удалённое.
 * DELETE /api/v1/photos/{id}: 204; 404 на чужое/удалённое.
 */
@WebMvcTest(PhotoController.class)
@Import(ApiExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class PhotoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PhotoService photoService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUserId()).thenReturn(1L);
    }

    private static MockMultipartFile imageFile() {
        return new MockMultipartFile("file", "plant.jpg", "image/jpeg", new byte[]{1, 2, 3, 4});
    }

    // ------------------------------------------------------------------ POST /api/v1/photos

    @Test
    void should_return_201_with_id_and_url_when_upload_succeeds() throws Exception {
        // arrange
        Photo photo = mock(Photo.class);
        when(photo.getId()).thenReturn(42L);
        when(photoService.upload(eq(1L), any(byte[].class), eq("image/jpeg"))).thenReturn(photo);
        when(photoService.presignedUrl(1L, 42L))
                .thenReturn(new URL("https://s3.example.com/test-bucket/photos/abc?sig=1"));

        // act + assert
        mockMvc.perform(multipart("/api/v1/photos").file(imageFile()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.url").value("https://s3.example.com/test-bucket/photos/abc?sig=1"));

        verify(photoService).upload(eq(1L), any(byte[].class), eq("image/jpeg"));
    }

    @Test
    void should_return_413_when_file_too_large() throws Exception {
        // arrange
        when(photoService.upload(eq(1L), any(byte[].class), eq("image/jpeg")))
                .thenThrow(new PhotoTooLargeException(9_000_000L, 5_242_880L));

        // act + assert
        mockMvc.perform(multipart("/api/v1/photos").file(imageFile()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"));
    }

    // ------------------------------------------------------------------ GET /api/v1/photos/{id}

    @Test
    void should_return_302_redirect_to_presigned_url() throws Exception {
        // arrange
        when(photoService.presignedUrl(1L, 42L))
                .thenReturn(new URL("https://s3.example.com/test-bucket/photos/abc?sig=1"));

        // act + assert
        mockMvc.perform(get("/api/v1/photos/42"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://s3.example.com/test-bucket/photos/abc?sig=1"));
    }

    @Test
    void should_return_404_when_getting_missing_or_foreign_photo() throws Exception {
        // arrange
        when(photoService.presignedUrl(1L, 99L))
                .thenThrow(new EntityNotFoundException("Photo not found: 99"));

        // act + assert
        mockMvc.perform(get("/api/v1/photos/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ DELETE /api/v1/photos/{id}

    @Test
    void should_return_204_when_soft_deleting_own_photo() throws Exception {
        // arrange
        doNothing().when(photoService).softDelete(1L, 42L);

        // act + assert
        mockMvc.perform(delete("/api/v1/photos/42"))
                .andExpect(status().isNoContent());

        verify(photoService).softDelete(1L, 42L);
    }

    @Test
    void should_return_404_when_deleting_missing_or_already_deleted_photo() throws Exception {
        // arrange
        doThrow(new EntityNotFoundException("Photo not found: 99"))
                .when(photoService).softDelete(1L, 99L);

        // act + assert
        mockMvc.perform(delete("/api/v1/photos/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }
}
