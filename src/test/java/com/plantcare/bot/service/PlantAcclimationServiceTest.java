package com.plantcare.bot.service;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.PlantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlantAcclimationService — режим акклиматизации (issue #75)")
class PlantAcclimationServiceTest {

    @Mock private PlantRepository plantRepository;

    @InjectMocks
    private PlantAcclimationService service;

    private User user;
    private Plant plant;

    @BeforeEach
    void setUp() {
        user = User.builder().telegramChatId(100L).build();
        plant = Plant.builder().user(user).name("Монстера").build();
        ReflectionTestUtils.setField(user, "id", 1L);
        ReflectionTestUtils.setField(plant, "id", 42L);

        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 42L))
                .thenReturn(Optional.of(plant));
        when(plantRepository.save(any(Plant.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("enable() ставит acclimation_until = now + 21 день и первый check-in через 3 дня")
    void shouldEnableAcclimationFor21Days() {
        LocalDateTime before = LocalDateTime.now();
        Plant result = service.enable(user, 42L);

        assertThat(result).isNotNull();
        assertThat(result.getAcclimationUntil()).isAfter(before.plusDays(20).plusHours(23));
        assertThat(result.getAcclimationUntil()).isBefore(before.plusDays(21).plusHours(1));
        assertThat(result.getAcclimationCheckinNextAt()).isAfter(before.plusDays(2).plusHours(23));
        assertThat(result.getAcclimationCheckinNextAt()).isBefore(before.plusDays(3).plusHours(1));
        assertThat(result.isInAcclimation(LocalDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("enable() для чужого/архивного растения → null, save не зовётся")
    void shouldRejectInaccessiblePlant() {
        when(plantRepository.findByUserIdAndIdAndArchivedAtIsNull(1L, 42L))
                .thenReturn(Optional.empty());

        Plant result = service.enable(user, 42L);

        assertThat(result).isNull();
        verify(plantRepository, never()).save(any());
    }

    @Test
    @DisplayName("disable() очищает acclimation_until и acclimation_checkin_next_at")
    void shouldDisableClearsFields() {
        plant.setAcclimationUntil(LocalDateTime.now().plusDays(10));
        plant.setAcclimationCheckinNextAt(LocalDateTime.now().plusDays(2));

        Plant result = service.disable(user, 42L);

        assertThat(result.getAcclimationUntil()).isNull();
        assertThat(result.getAcclimationCheckinNextAt()).isNull();
        assertThat(result.isInAcclimation(LocalDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("finish() очищает оба поля (вызывается scheduler'ом)")
    void shouldFinishClearsFields() {
        plant.setAcclimationUntil(LocalDateTime.now().minusMinutes(5));  // только что истёк
        plant.setAcclimationCheckinNextAt(LocalDateTime.now().plusDays(2));

        service.finish(plant);

        assertThat(plant.getAcclimationUntil()).isNull();
        assertThat(plant.getAcclimationCheckinNextAt()).isNull();
        verify(plantRepository).save(plant);
    }

    @Test
    @DisplayName("scheduleNextCheckin() ставит +3 дня, если ещё в пределах acclimation_until")
    void shouldScheduleNextCheckinWithinWindow() {
        plant.setAcclimationUntil(LocalDateTime.now().plusDays(10));

        service.scheduleNextCheckin(plant);

        assertThat(plant.getAcclimationCheckinNextAt())
                .isAfter(LocalDateTime.now().plusDays(2).plusHours(23));
    }

    @Test
    @DisplayName("scheduleNextCheckin() ставит NULL, если следующий тик уходит за пределы периода")
    void shouldNotScheduleCheckinPastAcclimationEnd() {
        // До конца акклиматизации остался 1 день, +3 — за окном
        plant.setAcclimationUntil(LocalDateTime.now().plusDays(1));

        service.scheduleNextCheckin(plant);

        assertThat(plant.getAcclimationCheckinNextAt()).isNull();
    }

    @Test
    @DisplayName("isInAcclimation: acclimation_until=null → false")
    void isInAcclimationHandlesNull() {
        plant.setAcclimationUntil(null);
        assertThat(plant.isInAcclimation(LocalDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("isInAcclimation: acclimation_until в прошлом → false")
    void isInAcclimationHandlesExpired() {
        plant.setAcclimationUntil(LocalDateTime.now().minusDays(1));
        assertThat(plant.isInAcclimation(LocalDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("isInAcclimation: acclimation_until в будущем → true")
    void isInAcclimationHandlesActive() {
        plant.setAcclimationUntil(LocalDateTime.now().plusDays(10));
        assertThat(plant.isInAcclimation(LocalDateTime.now())).isTrue();
    }
}
