package com.plantcare.bot.service;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.PlantEvent;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.PlantEventType;
import com.plantcare.bot.repository.PlantEventRepository;
import com.plantcare.bot.repository.PlantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlantEventService — журнал событий (issue #76)")
class PlantEventServiceTest {

    @Mock private PlantEventRepository plantEventRepository;
    @Mock private PlantRepository plantRepository;

    @InjectMocks
    private PlantEventService service;

    private User user;
    private Plant plant;

    @BeforeEach
    void setUp() {
        user = User.builder().telegramChatId(100L).timezone("UTC").build();
        plant = Plant.builder().user(user).name("Монстера").build();

        // Stub user id (BaseEntity setter недоступен → через рефлексию)
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        org.springframework.test.util.ReflectionTestUtils.setField(plant, "id", 42L);

        // По умолчанию плант доступен пользователю.
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 42L))
                .thenReturn(Optional.of(plant));
        // По умолчанию история пустая.
        when(plantEventRepository.findFirstByPlantIdAndEventTypeOrderByEventDateDesc(any(), any()))
                .thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("addEvent")
    class AddEvent {

        @Test
        @DisplayName("Создаёт PlantEvent с правильным типом и event_date=now")
        void shouldCreateEvent() {
            when(plantEventRepository.save(any(PlantEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            LocalDateTime before = LocalDateTime.now();
            PlantEventService.AddResult result =
                    service.addEvent(user, 42L, PlantEventType.PRUNING);

            assertThat(result.wasCreated()).isTrue();
            ArgumentCaptor<PlantEvent> cap = ArgumentCaptor.forClass(PlantEvent.class);
            verify(plantEventRepository).save(cap.capture());

            PlantEvent saved = cap.getValue();
            assertThat(saved.getPlant()).isEqualTo(plant);
            assertThat(saved.getEventType()).isEqualTo(PlantEventType.PRUNING);
            assertThat(saved.getEventDate()).isAfterOrEqualTo(before);
            assertThat(saved.getComment()).isNull();
        }

        @Test
        @DisplayName("Растение не принадлежит юзеру → NOT_FOUND, save не зовётся")
        void shouldReturnNotFoundForForeignPlant() {
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 42L))
                    .thenReturn(Optional.empty());

            PlantEventService.AddResult result =
                    service.addEvent(user, 42L, PlantEventType.TRANSPLANT);

            assertThat(result.wasNotFound()).isTrue();
            verify(plantEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("Повторный клик того же типа в окне 60 сек → DUPLICATE, save не зовётся")
        void shouldDedupRapidRepeatedClicks() {
            PlantEvent recent = PlantEvent.builder()
                    .plant(plant)
                    .eventType(PlantEventType.PRUNING)
                    .eventDate(LocalDateTime.now().minusSeconds(20))
                    .build();
            when(plantEventRepository.findFirstByPlantIdAndEventTypeOrderByEventDateDesc(
                    eq(42L), eq(PlantEventType.PRUNING)))
                    .thenReturn(Optional.of(recent));

            PlantEventService.AddResult result =
                    service.addEvent(user, 42L, PlantEventType.PRUNING);

            assertThat(result.wasDuplicate()).isTrue();
            verify(plantEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("Тот же тип спустя > 60 сек → новая запись")
        void shouldAllowSameTypeAfterDedupWindow() {
            PlantEvent old = PlantEvent.builder()
                    .plant(plant)
                    .eventType(PlantEventType.PRUNING)
                    .eventDate(LocalDateTime.now().minusMinutes(5))
                    .build();
            when(plantEventRepository.findFirstByPlantIdAndEventTypeOrderByEventDateDesc(
                    eq(42L), eq(PlantEventType.PRUNING)))
                    .thenReturn(Optional.of(old));
            when(plantEventRepository.save(any(PlantEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            PlantEventService.AddResult result =
                    service.addEvent(user, 42L, PlantEventType.PRUNING);

            assertThat(result.wasCreated()).isTrue();
            verify(plantEventRepository).save(any());
        }

        @Test
        @DisplayName("Дедуп работает по типу — другой тип в окне 60 сек создаёт новую запись")
        void shouldNotDedupAcrossDifferentTypes() {
            PlantEvent recentPruning = PlantEvent.builder()
                    .plant(plant)
                    .eventType(PlantEventType.PRUNING)
                    .eventDate(LocalDateTime.now().minusSeconds(10))
                    .build();
            // findFirst для TRANSPLANT — ничего не найдено
            when(plantEventRepository.findFirstByPlantIdAndEventTypeOrderByEventDateDesc(
                    eq(42L), eq(PlantEventType.PRUNING)))
                    .thenReturn(Optional.of(recentPruning));
            when(plantEventRepository.findFirstByPlantIdAndEventTypeOrderByEventDateDesc(
                    eq(42L), eq(PlantEventType.TRANSPLANT)))
                    .thenReturn(Optional.empty());
            when(plantEventRepository.save(any(PlantEvent.class))).thenAnswer(inv -> inv.getArgument(0));

            PlantEventService.AddResult result =
                    service.addEvent(user, 42L, PlantEventType.TRANSPLANT);

            assertThat(result.wasCreated()).isTrue();
            verify(plantEventRepository).save(any());
        }
    }

    @Nested
    @DisplayName("getEvents")
    class GetEvents {

        @Test
        @DisplayName("Возвращает страницу с PAGE_SIZE=5, сортировка DESC по event_date")
        void shouldReturnPagedEvents() {
            PlantEvent e1 = PlantEvent.builder().plant(plant).eventType(PlantEventType.PRUNING)
                    .eventDate(LocalDateTime.now()).build();
            PlantEvent e2 = PlantEvent.builder().plant(plant).eventType(PlantEventType.TRANSPLANT)
                    .eventDate(LocalDateTime.now().minusDays(1)).build();
            Page<PlantEvent> mockPage = new PageImpl<>(List.of(e1, e2), PageRequest.of(0, 5), 2);

            when(plantEventRepository.findByPlantIdOrderByEventDateDesc(eq(42L), any(Pageable.class)))
                    .thenReturn(mockPage);

            Page<PlantEvent> result = service.getEvents(user, 42L, 0);

            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).getEventType()).isEqualTo(PlantEventType.PRUNING);

            ArgumentCaptor<Pageable> pCap = ArgumentCaptor.forClass(Pageable.class);
            verify(plantEventRepository).findByPlantIdOrderByEventDateDesc(eq(42L), pCap.capture());
            assertThat(pCap.getValue().getPageSize()).isEqualTo(PlantEventService.EVENTS_PAGE_SIZE);
            assertThat(pCap.getValue().getPageNumber()).isEqualTo(0);
        }

        @Test
        @DisplayName("Отрицательная страница нормализуется в 0 (а не падает)")
        void shouldNormalizeNegativePage() {
            when(plantEventRepository.findByPlantIdOrderByEventDateDesc(eq(42L), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.getEvents(user, 42L, -7);

            ArgumentCaptor<Pageable> pCap = ArgumentCaptor.forClass(Pageable.class);
            verify(plantEventRepository).findByPlantIdOrderByEventDateDesc(eq(42L), pCap.capture());
            assertThat(pCap.getValue().getPageNumber()).isEqualTo(0);
        }

        @Test
        @DisplayName("Чужое/архивированное растение → пустая страница, к репо не ходим")
        void shouldReturnEmptyForInaccessiblePlant() {
            when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 42L))
                    .thenReturn(Optional.empty());

            Page<PlantEvent> result = service.getEvents(user, 42L, 0);

            assertThat(result.isEmpty()).isTrue();
            verify(plantEventRepository, never())
                    .findByPlantIdOrderByEventDateDesc(any(), any(Pageable.class));
        }
    }
}
