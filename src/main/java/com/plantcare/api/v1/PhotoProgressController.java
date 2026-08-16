package com.plantcare.api.v1;

import com.plantcare.api.CurrentUserProvider;
import com.plantcare.api.generated.PhotoProgressApi;
import com.plantcare.api.generated.model.PhotoProgressCompareResponse;
import com.plantcare.api.generated.model.PhotoProgressFrequencyRequest;
import com.plantcare.api.generated.model.PhotoProgressFrequencyResponse;
import com.plantcare.api.generated.model.PhotoProgressFrequencyValue;
import com.plantcare.api.generated.model.PhotoProgressHistoryResponse;
import com.plantcare.api.generated.model.PhotoProgressItemDto;
import com.plantcare.core.domain.Photo;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantProgressPhoto;
import com.plantcare.core.domain.enums.PhotoProgressFrequency;
import com.plantcare.core.service.InvalidPhotoException;
import com.plantcare.core.service.PhotoProgressService;
import com.plantcare.core.service.PhotoProgressService.PhotoPair;
import com.plantcare.core.service.PhotoService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.ZoneOffset;
import java.util.List;

/**
 * REST API фото-дневника растения (issue #253): добавление снимков прогресса,
 * история, сравнение «до/после» и частота напоминаний о фото.
 *
 * <p>Контроллер тонкий: загрузку бинаря делегирует {@link PhotoService} (S3,
 * issue #90), бизнес-логику таймлайна — {@link PhotoProgressService}. Текущего
 * пользователя берёт из {@link CurrentUserProvider}. Документация и mapping —
 * в сгенерированном {@link PhotoProgressApi} (см. openapi.yaml →
 * resources/plant-photo-progress.yaml).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PhotoProgressController implements PhotoProgressApi {

    private final PhotoProgressService photoProgressService;
    private final PhotoService photoService;
    private final CurrentUserProvider currentUserProvider;

    @Override
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoProgressItemDto addPhotoProgress(Long id, MultipartFile file, String caption) {
        Long userId = currentUserProvider.currentUserId();

        byte[] bytes = readBytes(file);
        String contentType = file.getContentType();

        // Бинарь — в S3 (валидация типа/размера внутри PhotoService), затем ссылка
        // в таймлайн через PhotoProgressService (ownership/анти-спам внутри).
        Photo photo = photoService.upload(userId, bytes, contentType);
        PlantProgressPhoto saved =
                photoProgressService.addPhotoFromStorage(userId, id, photo, caption);

        log.info("POST /api/v1/plants/{}/photo-progress: userId={} progressPhotoId={} photoId={}",
                id, userId, saved.getId(), photo.getId());

        return toDto(userId, saved);
    }

    @Override
    public PhotoProgressHistoryResponse getPhotoProgressHistory(Long id, Integer limit, Integer offset) {
        Long userId = currentUserProvider.currentUserId();

        // limit валидирован [1,100] на входе (@Min/@Max). Сервис страницей по
        // HISTORY_PAGE_SIZE не умеет произвольный limit — берём окно вручную через
        // offset/limit поверх полного таймлайна растения.
        int safeOffset = Math.max(0, offset);

        long total = photoProgressService.countHistory(userId, id);
        List<PlantProgressPhoto> all = photoProgressService.getRecent(userId, id, safeOffset + limit);

        List<PhotoProgressItemDto> items = all.stream()
                .skip(safeOffset)
                .limit(limit)
                .map(p -> toDto(userId, p))
                .toList();

        log.info("GET /api/v1/plants/{}/photo-progress: userId={} limit={} offset={} total={}",
                id, userId, limit, safeOffset, total);

        return new PhotoProgressHistoryResponse(items, total, limit, safeOffset);
    }

    @Override
    public PhotoProgressCompareResponse comparePhotoProgress(Long id, Long from, Long to) {
        Long userId = currentUserProvider.currentUserId();

        PhotoPair pair = photoProgressService.compareByIds(userId, id, from, to)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Photo pair not found for plant " + id + ": from=" + from + " to=" + to));

        log.info("GET /api/v1/plants/{}/photo-progress/compare: userId={} from={} to={}",
                id, userId, from, to);

        return new PhotoProgressCompareResponse(
                toDto(userId, pair.before()),
                toDto(userId, pair.after()));
    }

    @Override
    public PhotoProgressFrequencyResponse setPhotoProgressFrequency(
            Long id, PhotoProgressFrequencyRequest request) {
        Long userId = currentUserProvider.currentUserId();

        // Значения enum в спеке и домене совпадают (OFF/P2W/P1M); маппим по value.
        PhotoProgressFrequency frequency =
                PhotoProgressFrequency.valueOf(request.getFrequency().getValue());

        Plant plant = photoProgressService.setFrequency(userId, id, frequency);

        log.info("PATCH /api/v1/plants/{}/photo-progress/frequency: userId={} frequency={}",
                id, userId, frequency);

        return new PhotoProgressFrequencyResponse(
                PhotoProgressFrequencyValue.fromValue(plant.getPhotoProgressFrequency().name()));
    }

    /**
     * Снимок таймлайна → DTO. Для S3-источника подтягивает свежий presigned URL
     * через {@link PhotoService} (ownership уже проверена сервисом). Бот-фото
     * (Telegram file_id) отдаём без url/photoId — мобайл его пока не скачивает.
     */
    private PhotoProgressItemDto toDto(Long userId, PlantProgressPhoto progressPhoto) {
        PhotoProgressItemDto dto = new PhotoProgressItemDto(
                progressPhoto.getId(),
                progressPhoto.getTakenAt().atOffset(ZoneOffset.UTC))
                .caption(progressPhoto.getCaption());

        Photo photo = progressPhoto.getPhoto();
        if (photo != null) {
            dto.photoId(photo.getId());
            URL presigned = photoService.presignedUrl(userId, photo.getId());
            // format:uri → сгенерён URI; presigned — URL, конвертим через строку (#310).
            dto.url(URI.create(presigned.toString()));
        }
        return dto;
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
}
