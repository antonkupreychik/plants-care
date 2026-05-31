package com.plantcare.core.service;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantProgressPhoto;
import com.plantcare.core.domain.enums.PhotoProgressFrequency;
import com.plantcare.core.repository.PlantProgressPhotoRepository;
import com.plantcare.core.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.Optional;

/**
 * Бизнес-логика фото-прогресса (issue #72): включение/выключение режима,
 * загрузка фото в таймлайн, выборки для UI «История» и «Сравнить».
 *
 * <p>Ownership проверяется в каждом методе через
 * {@link PlantRepository#findByUserIdAndIdAndArchivedAtIsNull} — растение
 * должно принадлежать юзеру и не быть архивным.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PhotoProgressService {

    /** Размер страницы для UI «История фото». */
    public static final int HISTORY_PAGE_SIZE = 10;

    /** Антиспам: не больше одного фото в этом окне (часов). */
    private static final int ANTI_SPAM_HOURS = 24;

    private final PlantRepository plantRepository;
    private final PlantProgressPhotoRepository photoRepository;

    /**
     * Установить частоту фото-прогресса.
     *
     * <p>При включении выставляет {@code nextPhotoDueAt = now + period} и сбрасывает
     * {@code lastPhotoPromptSentAt}. При выключении ({@link PhotoProgressFrequency#OFF})
     * чистит {@code nextPhotoDueAt}, чтобы шедулер растение не подхватывал.
     *
     * @return обновлённое растение
     * @throws IllegalArgumentException если растение не найдено у юзера
     */
    public Plant setFrequency(Long userId, Long plantId, PhotoProgressFrequency frequency) {
        Plant plant = requirePlant(userId, plantId);

        plant.setPhotoProgressFrequency(frequency);

        if (frequency.isEnabled()) {
            plant.setNextPhotoDueAt(LocalDateTime.now().plus(frequency.getPeriod()));
        } else {
            plant.setNextPhotoDueAt(null);
        }
        plant.setLastPhotoPromptSentAt(null);

        Plant saved = plantRepository.save(plant);
        log.info("Photo-progress frequency set: plant={} user={} frequency={} nextDueAt={}",
                plantId, userId, frequency, saved.getNextPhotoDueAt());
        return saved;
    }

    /**
     * Добавить фото в таймлайн и пересчитать {@code nextPhotoDueAt}.
     *
     * @throws IllegalArgumentException если растение не найдено у юзера
     * @throws IllegalStateException    если за последние {@link #ANTI_SPAM_HOURS}ч
     *                                  уже было фото у этого растения
     */
    public PlantProgressPhoto addPhoto(Long userId, Long plantId, String telegramFileId) {
        if (telegramFileId == null || telegramFileId.isBlank()) {
            throw new IllegalArgumentException("telegramFileId is required");
        }

        Plant plant = requirePlant(userId, plantId);
        LocalDateTime now = LocalDateTime.now();

        if (photoRepository.existsByPlantIdAndTakenAtAfter(
                plantId, now.minusHours(ANTI_SPAM_HOURS))) {
            throw new IllegalStateException("Уже есть фото за последние 24 часа");
        }

        PlantProgressPhoto photo = new PlantProgressPhoto();
        photo.setPlant(plant);
        photo.setUser(plant.getUser());
        photo.setTelegramFileId(telegramFileId);
        photo.setTakenAt(now);

        PlantProgressPhoto saved = photoRepository.save(photo);

        PhotoProgressFrequency frequency = plant.getPhotoProgressFrequency();
        if (frequency != null && frequency.isEnabled()) {
            Period period = frequency.getPeriod();
            plant.setNextPhotoDueAt(now.plus(period));
        }
        // Сброс анти-спам поля — новый цикл, prompt можно слать снова, когда подойдёт срок.
        plant.setLastPhotoPromptSentAt(null);
        plantRepository.save(plant);

        log.info("Progress photo added: plant={} user={} photoId={} nextDueAt={}",
                plantId, userId, saved.getId(), plant.getNextPhotoDueAt());
        return saved;
    }

    /**
     * «Пропустить» текущий prompt — сдвинуть {@code nextPhotoDueAt} на полный период,
     * чтобы юзер не получал повторных пушей до следующего цикла.
     */
    public Plant skipPrompt(Long userId, Long plantId) {
        Plant plant = requirePlant(userId, plantId);

        PhotoProgressFrequency frequency = plant.getPhotoProgressFrequency();
        if (frequency == null || !frequency.isEnabled()) {
            // Режим выключен — нечего скипать; возвращаем без изменений.
            return plant;
        }

        LocalDateTime now = LocalDateTime.now();
        plant.setNextPhotoDueAt(now.plus(frequency.getPeriod()));
        plant.setLastPhotoPromptSentAt(now);

        Plant saved = plantRepository.save(plant);
        log.info("Photo-progress prompt skipped: plant={} user={} nextDueAt={}",
                plantId, userId, saved.getNextPhotoDueAt());
        return saved;
    }

    /**
     * Зафиксировать факт отправки prompt'а — используется шедулером.
     *
     * <p>Помимо записи {@code lastPhotoPromptSentAt}, сдвигает
     * {@code nextPhotoDueAt} на полный период вперёд: иначе при игноре
     * пользователем дедуп через 24 часа снова разрешит отправку и
     * растение начнёт получать пуш каждые сутки. Если пользователь хочет
     * добавить фото — он сделает это вручную из карточки; следующий
     * автоматический пуш — только через полный период.
     */
    public void markPromptSent(Long plantId, LocalDateTime sentAt) {
        Plant plant = plantRepository.findById(plantId).orElse(null);
        if (plant == null) {
            log.warn("markPromptSent: plant {} not found", plantId);
            return;
        }

        plant.setLastPhotoPromptSentAt(sentAt);

        PhotoProgressFrequency frequency = plant.getPhotoProgressFrequency();
        if (frequency != null && frequency.isEnabled()) {
            plant.setNextPhotoDueAt(sentAt.plus(frequency.getPeriod()));
        }

        plantRepository.save(plant);
    }

    @Transactional(readOnly = true)
    public List<PlantProgressPhoto> getHistory(Long userId, Long plantId, int page) {
        requirePlant(userId, plantId);
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, HISTORY_PAGE_SIZE);
        return photoRepository.findAllByPlantIdOrderByTakenAtDesc(plantId, pageable);
    }

    @Transactional(readOnly = true)
    public long countHistory(Long userId, Long plantId) {
        requirePlant(userId, plantId);
        return photoRepository.countByPlantId(plantId);
    }

    /**
     * Последние N фото для UI «Выбрать две даты».
     */
    @Transactional(readOnly = true)
    public List<PlantProgressPhoto> getRecent(Long userId, Long plantId, int limit) {
        requirePlant(userId, plantId);
        return photoRepository.findRecent(plantId, PageRequest.of(0, Math.max(1, limit)));
    }

    /**
     * Сравнение «первое vs последнее». Пусто, если фото < 2.
     */
    @Transactional(readOnly = true)
    public Optional<PhotoPair> compareFirstVsLast(Long userId, Long plantId) {
        requirePlant(userId, plantId);

        if (photoRepository.countByPlantId(plantId) < 2) {
            return Optional.empty();
        }

        Optional<PlantProgressPhoto> first = photoRepository
                .findFirstByPlantIdOrderByTakenAtAsc(plantId);
        Optional<PlantProgressPhoto> last = photoRepository
                .findFirstByPlantIdOrderByTakenAtDesc(plantId);

        if (first.isEmpty() || last.isEmpty()
                || first.get().getId().equals(last.get().getId())) {
            return Optional.empty();
        }
        return Optional.of(new PhotoPair(first.get(), last.get()));
    }

    /**
     * Сравнение «месяц назад vs сейчас». Берёт «до» — ближайшее фото снизу
     * к {@code now - 1 month}; «после» — последнее.
     */
    @Transactional(readOnly = true)
    public Optional<PhotoPair> compareMonthVsNow(Long userId, Long plantId) {
        requirePlant(userId, plantId);

        Optional<PlantProgressPhoto> latest = photoRepository
                .findFirstByPlantIdOrderByTakenAtDesc(plantId);
        if (latest.isEmpty()) {
            return Optional.empty();
        }

        LocalDateTime monthAgo = LocalDateTime.now().minusMonths(1);
        List<PlantProgressPhoto> before = photoRepository.findClosestBefore(
                plantId, monthAgo, PageRequest.of(0, 1));

        if (before.isEmpty() || before.get(0).getId().equals(latest.get().getId())) {
            return Optional.empty();
        }
        return Optional.of(new PhotoPair(before.get(0), latest.get()));
    }

    /**
     * Сравнение по двум id фото — обе записи должны принадлежать этому растению юзера.
     */
    @Transactional(readOnly = true)
    public Optional<PhotoPair> compareByIds(Long userId, Long plantId, Long leftId, Long rightId) {
        requirePlant(userId, plantId);

        if (leftId == null || rightId == null || leftId.equals(rightId)) {
            return Optional.empty();
        }

        Optional<PlantProgressPhoto> left = photoRepository.findById(leftId);
        Optional<PlantProgressPhoto> right = photoRepository.findById(rightId);

        if (left.isEmpty() || right.isEmpty()) {
            return Optional.empty();
        }
        // Защита от подмены: оба фото должны быть из выбранного растения.
        if (!plantId.equals(left.get().getPlant().getId())
                || !plantId.equals(right.get().getPlant().getId())) {
            return Optional.empty();
        }

        PlantProgressPhoto leftPhoto = left.get();
        PlantProgressPhoto rightPhoto = right.get();
        PlantProgressPhoto before;
        PlantProgressPhoto after;
        if (leftPhoto.getTakenAt().isAfter(rightPhoto.getTakenAt())) {
            before = rightPhoto;
            after = leftPhoto;
        } else if (leftPhoto.getTakenAt().isBefore(rightPhoto.getTakenAt())) {
            before = leftPhoto;
            after = rightPhoto;
        } else {
            // Tie-breaker по id: меньший id считаем «до», больший — «после»,
            // чтобы порядок был детерминированным при равных takenAt.
            if (leftPhoto.getId() < rightPhoto.getId()) {
                before = leftPhoto;
                after = rightPhoto;
            } else {
                before = rightPhoto;
                after = leftPhoto;
            }
        }
        return Optional.of(new PhotoPair(before, after));
    }

    /**
     * Найти фото по id с проверкой принадлежности юзеру.
     */
    @Transactional(readOnly = true)
    public Optional<PlantProgressPhoto> getPhotoForUser(Long userId, Long photoId) {
        return photoRepository.findById(photoId)
                .filter(p -> p.getUser() != null && userId.equals(p.getUser().getId()));
    }

    private Plant requirePlant(Long userId, Long plantId) {
        return plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(userId, plantId)
                .orElseThrow(() -> new IllegalArgumentException("Plant not found"));
    }

    /** Пара «до / после» для UI сравнения. */
    public record PhotoPair(PlantProgressPhoto before, PlantProgressPhoto after) {
    }
}
