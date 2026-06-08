package com.plantcare.api.v1;

import com.plantcare.api.generated.PhotosApi;
import com.plantcare.api.generated.model.PhotoUploadResponse;
import com.plantcare.api.CurrentUserProvider;
import com.plantcare.core.domain.Photo;
import com.plantcare.core.service.InvalidPhotoException;
import com.plantcare.core.service.PhotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URL;

/**
 * REST API хранилища фото в S3-совместимом бакете (issue #90, Slice A).
 *
 * <p>Документация и mapping живут в сгенерированном {@link PhotosApi}
 * (см. openapi.yaml → resources/photos.yaml). Хендлер тонкий: текущего
 * пользователя берёт из {@link CurrentUserProvider}, всю логику делегирует
 * {@link PhotoService}.
 *
 * <ul>
 *   <li>{@code POST /photos} — multipart upload, 201 с {@code { id, url }}.</li>
 *   <li>{@code GET /photos/{id}} — 302 на presigned URL объекта.</li>
 *   <li>{@code DELETE /photos/{id}} — soft-delete, 204.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PhotoController implements PhotosApi {

    private final PhotoService photoService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    public PhotoUploadResponse uploadPhoto(MultipartFile file) {
        Long userId = currentUserProvider.currentUserId();

        byte[] bytes = readBytes(file);
        String contentType = file.getContentType();

        Photo photo = photoService.upload(userId, bytes, contentType);
        URL url = photoService.presignedUrl(userId, photo.getId());

        return new PhotoUploadResponse(photo.getId(), URI.create(url.toString()));
    }

    @Override
    public void getPhoto(Long id) {
        Long userId = currentUserProvider.currentUserId();
        URL url = photoService.presignedUrl(userId, id);

        // Метод сгенерирован как void с @ResponseStatus(FOUND); ставим Location
        // на текущем ответе. Сам редирект (302) проставляет @ResponseStatus.
        HttpServletResponse response = currentResponse();
        response.setHeader("Location", url.toString());
    }

    @Override
    public void deletePhoto(Long id) {
        Long userId = currentUserProvider.currentUserId();
        photoService.softDelete(userId, id);
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidPhotoException("Empty file");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidPhotoException("Cannot read uploaded file");
        }
    }

    private static HttpServletResponse currentResponse() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        HttpServletResponse response = attributes.getResponse();
        if (response == null) {
            throw new IllegalStateException("No current HttpServletResponse");
        }
        return response;
    }
}
