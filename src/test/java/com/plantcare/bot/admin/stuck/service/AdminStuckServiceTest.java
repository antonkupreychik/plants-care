package com.plantcare.bot.admin.stuck.service;

import com.plantcare.bot.admin.stuck.dto.StuckUserItemDto;
import com.plantcare.bot.admin.stuck.repository.AdminStuckRepository;
import com.plantcare.bot.admin.users.service.AdminUserActionService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminStuckService — /admin/stuck (issue #59)")
class AdminStuckServiceTest {

    @Mock private AdminStuckRepository repository;
    @Mock private AdminUserActionService userActionService;

    @InjectMocks
    private AdminStuckService service;

    @BeforeEach
    void setUp() {
        when(repository.findStuck(any(), any())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("listStuck")
    class ListStuck {

        @Test
        @DisplayName("Считает cutoff как now() − STUCK_AFTER_HOURS")
        void shouldComputeCutoffFromConstant() {
            ArgumentCaptor<LocalDateTime> cutoffCap = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> nowCap = ArgumentCaptor.forClass(LocalDateTime.class);

            service.listStuck();

            verify(repository).findStuck(cutoffCap.capture(), nowCap.capture());

            LocalDateTime cutoff = cutoffCap.getValue();
            LocalDateTime now = nowCap.getValue();
            // now − cutoff должно равняться STUCK_AFTER_HOURS (24h)
            long hours = java.time.Duration.between(cutoff, now).toHours();
            assertThat(hours).isEqualTo(AdminStuckService.STUCK_AFTER_HOURS);
        }

        @Test
        @DisplayName("Возвращает что отдал репо, без модификаций")
        void shouldPassThroughRepoResult() {
            StuckUserItemDto item = new StuckUserItemDto(
                    1L, 100L, "alice", "AWAITING_PLANT_NAME", "{}",
                    LocalDateTime.now().minusHours(48), 48L
            );
            when(repository.findStuck(any(), any())).thenReturn(List.of(item));

            List<StuckUserItemDto> result = service.listStuck();

            assertThat(result).containsExactly(item);
        }
    }

    @Nested
    @DisplayName("resetBulk")
    class ResetBulk {

        @Test
        @DisplayName("Пустой список → (0, 0), userActionService не зовётся")
        void shouldHandleEmptyList() {
            AdminStuckService.BulkResetResult result = service.resetBulk(List.of(), "admin");

            assertThat(result.success()).isEqualTo(0);
            assertThat(result.failed()).isEqualTo(0);
            verify(userActionService, never()).resetState(anyLong(), anyString());
        }

        @Test
        @DisplayName("null список → (0, 0), userActionService не зовётся")
        void shouldHandleNullList() {
            AdminStuckService.BulkResetResult result = service.resetBulk(null, "admin");

            assertThat(result.success()).isEqualTo(0);
            assertThat(result.failed()).isEqualTo(0);
            verify(userActionService, never()).resetState(anyLong(), anyString());
        }

        @Test
        @DisplayName("Все успешные → (n, 0), вызов на каждый userId")
        void shouldResetAllSuccessfully() {
            AdminStuckService.BulkResetResult result =
                    service.resetBulk(List.of(1L, 2L, 3L), "admin");

            assertThat(result.success()).isEqualTo(3);
            assertThat(result.failed()).isEqualTo(0);
            verify(userActionService).resetState(eq(1L), eq("admin"));
            verify(userActionService).resetState(eq(2L), eq("admin"));
            verify(userActionService).resetState(eq(3L), eq("admin"));
        }

        @Test
        @DisplayName("Часть упала с exception → остальные всё равно сбрасываются, "
                + "счёт success/failed корректный")
        void shouldContinueOnPartialFailure() {
            // userId=2 падает, 1 и 3 проходят
            doThrow(new RuntimeException("not found"))
                    .when(userActionService).resetState(eq(2L), anyString());

            AdminStuckService.BulkResetResult result =
                    service.resetBulk(List.of(1L, 2L, 3L), "admin");

            assertThat(result.success()).isEqualTo(2);
            assertThat(result.failed()).isEqualTo(1);
            assertThat(result.total()).isEqualTo(3);
            // Гарантия что после ошибки не прервались
            verify(userActionService).resetState(eq(3L), anyString());
        }

        @Test
        @DisplayName("null-id'ы внутри списка пропускаются (защита от мусора)")
        void shouldSkipNullIds() {
            java.util.List<Long> ids = new java.util.ArrayList<>();
            ids.add(1L);
            ids.add(null);
            ids.add(2L);

            AdminStuckService.BulkResetResult result = service.resetBulk(ids, "admin");

            assertThat(result.success()).isEqualTo(2);
            assertThat(result.failed()).isEqualTo(0);
            verify(userActionService, times(2)).resetState(anyLong(), anyString());
        }
    }
}
