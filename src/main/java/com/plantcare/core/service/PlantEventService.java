package com.plantcare.core.service;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantEvent;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.PlantEventType;
import com.plantcare.core.repository.OffsetLimitPageable;
import com.plantcare.core.repository.PlantEventRepository;
import com.plantcare.core.repository.PlantRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Журнал событий растения (issue #76): создание событий и постраничное чтение.
 *
 * <p>События — редкие, разовые: пересадка, обрезка, обработка от вредителей.
 * Это не регулярный уход (тот — в {@link CareHistoryService}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlantEventService {

    /** Размер страницы в журнале событий (по issue: 3–5; берём 5). */
    public static final int EVENTS_PAGE_SIZE = 5;

    /**
     * Окно дедупа для двойных нажатий (как в care_history, для консистентности).
     */
    private static final int DEDUP_SECONDS = 60;

    private final PlantEventRepository plantEventRepository;
    private final PlantRepository plantRepository;

    /**
     * Сохраняет новое событие текущей датой.
     *
     * @return созданное событие; {@code null} если растение не принадлежит юзеру
     *         или уже архивировано; {@link AddResult#duplicate()} = true при
     *         повторном клике в окне дедупа.
     */
    @Transactional
    public AddResult addEvent(User user, Long plantId, PlantEventType eventType) {
        Plant plant = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);

        if (plant == null) {
            return AddResult.notFound();
        }

        LocalDateTime now = LocalDateTime.now();

        // Дедуп: тот же тип события в окне 60 сек — игнорируем второе нажатие.
        Optional<PlantEvent> last = plantEventRepository
                .findFirstByPlantIdAndEventTypeOrderByEventDateDesc(plantId, eventType);
        if (last.isPresent() && last.get().getEventDate().plusSeconds(DEDUP_SECONDS).isAfter(now)) {
            return AddResult.duplicate(last.get());
        }

        PlantEvent event = PlantEvent.builder()
                .plant(plant)
                .eventType(eventType)
                .eventDate(now)
                .build();
        PlantEvent saved = plantEventRepository.save(event);

        log.info("Plant event saved: plant={}, type={}, eventId={}",
                plantId, eventType, saved.getId());

        return AddResult.created(saved);
    }

    /**
     * Возвращает страницу журнала. Если растение не принадлежит юзеру или
     * архивно — возвращает пустую страницу (защита от прямой подмены plantId
     * в callback_data).
     */
    @Transactional(readOnly = true)
    public Page<PlantEvent> getEvents(User user, Long plantId, int page) {
        boolean accessible = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .isPresent();

        if (!accessible) {
            return Page.empty();
        }

        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, EVENTS_PAGE_SIZE);
        return plantEventRepository.findByPlantIdOrderByEventDateDesc(plantId, pageable);
    }

    /**
     * Возвращает страницу журнала по произвольному {@code offset/limit} — для
     * REST API (issue #220). В отличие от {@link #getEvents(User, Long, int)},
     * который для Telegram-бота отдаёт пустую страницу при недоступном растении,
     * здесь недоступность — это {@link EntityNotFoundException} (контроллер
     * мапит в 404): чужое/архивное/несуществующее растение неотличимо для клиента.
     *
     * <p>Сортировка фиксирована — {@code event_date DESC}. {@code offset/limit}
     * предполагаются уже нормализованными вызывающим.
     */
    @Transactional(readOnly = true)
    public Page<PlantEvent> getEventsPage(User user, Long plantId, int offset, int limit) {
        boolean accessible = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .isPresent();

        if (!accessible) {
            throw new EntityNotFoundException(
                    "Plant not found: id=" + plantId + " for userId=" + user.getId());
        }

        // Сортировка уже зашита в имя метода (OrderByEventDateDesc); во избежание
        // дублирования ORDER BY передаём в Pageable unsorted.
        Pageable pageable = OffsetLimitPageable.of(offset, limit, Sort.unsorted());
        return plantEventRepository.findByPlantIdOrderByEventDateDesc(plantId, pageable);
    }

    /**
     * Результат добавления события — структура с явными состояниями вместо
     * исключений / null-маркеров.
     */
    public record AddResult(Status status, PlantEvent event) {
        public enum Status { CREATED, DUPLICATE, NOT_FOUND }

        public static AddResult created(PlantEvent event) {
            return new AddResult(Status.CREATED, event);
        }

        public static AddResult duplicate(PlantEvent event) {
            return new AddResult(Status.DUPLICATE, event);
        }

        public static AddResult notFound() {
            return new AddResult(Status.NOT_FOUND, null);
        }

        public boolean wasCreated() { return status == Status.CREATED; }
        public boolean wasDuplicate() { return status == Status.DUPLICATE; }
        public boolean wasNotFound() { return status == Status.NOT_FOUND; }
    }
}
