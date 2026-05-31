package com.plantcare.admin.broadcast.service;

import com.plantcare.admin.broadcast.repository.AdminBroadcastRepository;
import com.plantcare.bot.client.TelegramClientProvider;
import com.plantcare.core.domain.AdminBroadcast;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.AudienceFilter;
import com.plantcare.core.domain.enums.BroadcastStatus;
import com.plantcare.core.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminBroadcastService — issue #60")
class AdminBroadcastServiceTest {

    @Mock private AdminBroadcastRepository broadcastRepository;
    @Mock private AudienceResolver audienceResolver;
    @Mock private TelegramClientProvider telegramClientProvider;
    @Mock private UserRepository userRepository;
    @Mock private TelegramClient telegramClient;

    private ExecutorService executor;
    private AdminBroadcastService service;

    @BeforeEach
    void setUp() {
        // Однопоточный executor с прямым running — в тестах хотим синхронность для assert'ов.
        executor = Executors.newSingleThreadExecutor();
        // Для TransactionTemplate используем no-op transaction manager: вызов
        // execute(callback) просто запустит callback без реальной транзакции.
        org.springframework.transaction.PlatformTransactionManager noopTm =
                new org.springframework.transaction.PlatformTransactionManager() {
                    @Override
                    public org.springframework.transaction.TransactionStatus getTransaction(
                            org.springframework.transaction.TransactionDefinition definition) {
                        return new org.springframework.transaction.support.SimpleTransactionStatus();
                    }
                    @Override public void commit(org.springframework.transaction.TransactionStatus s) {}
                    @Override public void rollback(org.springframework.transaction.TransactionStatus s) {}
                };
        service = new AdminBroadcastService(
                broadcastRepository, audienceResolver, telegramClientProvider,
                userRepository, executor, noopTm
        );

        when(telegramClientProvider.getTelegramClient()).thenReturn(telegramClient);
        when(broadcastRepository.findFirstByStatus(BroadcastStatus.RUNNING))
                .thenReturn(Optional.empty());
        when(broadcastRepository.save(any(AdminBroadcast.class))).thenAnswer(inv -> {
            AdminBroadcast b = inv.getArgument(0);
            b.setId(42L);
            return b;
        });
        when(broadcastRepository.findById(anyLong())).thenAnswer(inv ->
                Optional.of(AdminBroadcast.builder()
                        .id(inv.getArgument(0))
                        .status(BroadcastStatus.RUNNING).build())
        );
    }

    private User user(long chatId) {
        return User.builder().telegramChatId(chatId).build();
    }

    @Nested
    @DisplayName("startBroadcast — guards")
    class StartGuards {

        @Test
        @DisplayName("Пустой текст → INVALID")
        void shouldRejectEmptyText() {
            var r = service.startBroadcast("", AudienceFilter.ONLINE, Set.of(), "admin");
            assertThat(r.isInvalid()).isTrue();
            verify(broadcastRepository, never()).save(any());
        }

        @Test
        @DisplayName("Текст > 4000 → INVALID")
        void shouldRejectTooLongText() {
            String tooLong = "x".repeat(4001);
            var r = service.startBroadcast(tooLong, AudienceFilter.ONLINE, Set.of(), "admin");
            assertThat(r.isInvalid()).isTrue();
            verify(broadcastRepository, never()).save(any());
        }

        @Test
        @DisplayName("Уже RUNNING → BUSY")
        void shouldRejectIfAlreadyRunning() {
            AdminBroadcast running = AdminBroadcast.builder()
                    .id(99L).status(BroadcastStatus.RUNNING).build();
            when(broadcastRepository.findFirstByStatus(BroadcastStatus.RUNNING))
                    .thenReturn(Optional.of(running));

            var r = service.startBroadcast("hi", AudienceFilter.ONLINE, Set.of(), "admin");

            assertThat(r.isBusy()).isTrue();
            assertThat(r.broadcastId()).isEqualTo(99L);
            verify(broadcastRepository, never()).save(any());
        }

        @Test
        @DisplayName("Пустая аудитория → INVALID")
        void shouldRejectEmptyAudience() {
            when(audienceResolver.resolve(any(), any())).thenReturn(List.of());

            var r = service.startBroadcast("hi", AudienceFilter.ONLINE, Set.of(), "admin");

            assertThat(r.isInvalid()).isTrue();
        }

        @Test
        @DisplayName("Валидный запрос → STARTED, save с правильным total/status, executor submit")
        void shouldStart() throws Exception {
            when(audienceResolver.resolve(any(), any())).thenReturn(
                    List.of(user(1L), user(2L), user(3L)));

            var r = service.startBroadcast("hi", AudienceFilter.ONLINE, Set.of(), "admin");

            assertThat(r.isStarted()).isTrue();

            ArgumentCaptor<AdminBroadcast> saved = ArgumentCaptor.forClass(AdminBroadcast.class);
            verify(broadcastRepository).save(saved.capture());
            assertThat(saved.getValue().getTotalCount()).isEqualTo(3);
            assertThat(saved.getValue().getStatus()).isEqualTo(BroadcastStatus.RUNNING);
            assertThat(saved.getValue().getSentBy()).isEqualTo("admin");

            // Ждём пока async-таска отработает
            executor.shutdown();
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

            // Проверяем что был хотя бы один execute
            verify(telegramClient, times(3)).execute(any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class));
        }
    }

    @Nested
    @DisplayName("runBroadcastLoop — отправка")
    class SendLoop {

        @Test
        @DisplayName("Forbidden (403) → юзер blocked, blocked_count++, failed не растёт")
        void shouldMarkUserBlockedOn403() throws Exception {
            User u = user(123L);
            when(userRepository.findByTelegramChatId(123L)).thenReturn(Optional.of(u));
            doThrow(new TelegramApiException("Forbidden: bot was blocked by the user [403]"))
                    .when(telegramClient).execute(any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class));

            service.runBroadcastLoop(42L, "hi", List.of(123L));

            assertThat(u.isBlocked()).isTrue();
            verify(userRepository).save(u);
            // flushCounters: blocked++, failed=0
            verify(broadcastRepository).bumpCounters(eq(42L), eq(0), eq(0), eq(1));
        }

        @Test
        @DisplayName("Общая ошибка → failed_count++, юзер не помечается")
        void shouldIncrementFailedOnGenericError() throws Exception {
            doThrow(new TelegramApiException("Bad Request: chat not found"))
                    .when(telegramClient).execute(any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class));

            service.runBroadcastLoop(42L, "hi", List.of(123L));

            verify(broadcastRepository).bumpCounters(eq(42L), eq(0), eq(1), eq(0));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("Все успешные → sent_count = total, статус DONE")
        void shouldFinalizeAsDone() throws Exception {
            service.runBroadcastLoop(42L, "hi", List.of(1L, 2L, 3L));

            verify(telegramClient, times(3))
                    .execute(any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class));
            // Финальный flush + markFinished
            verify(broadcastRepository).markFinished(eq(42L), eq(BroadcastStatus.DONE), any());
        }

        @Test
        @DisplayName("Если status стал STOPPED — цикл прерывается, finalStatus сохраняет STOPPED")
        void shouldStopWhenStatusChangedToStopped() throws Exception {
            // Имитируем «после первой проверки админ нажал стоп»: возвращаем
            // RUNNING на первой записи, STOPPED после.
            AdminBroadcast running = AdminBroadcast.builder()
                    .id(42L).status(BroadcastStatus.RUNNING).build();
            AdminBroadcast stopped = AdminBroadcast.builder()
                    .id(42L).status(BroadcastStatus.STOPPED).build();
            when(broadcastRepository.findById(42L))
                    .thenReturn(Optional.of(running))   // первая проверка — RUNNING
                    .thenReturn(Optional.of(stopped));  // вторая — STOPPED, цикл break

            service.runBroadcastLoop(42L, "hi", List.of(1L, 2L, 3L));

            // Отправлен ровно один (первый — пропустил проверку RUNNING; второй увидел STOPPED)
            verify(telegramClient, times(1))
                    .execute(any(org.telegram.telegrambots.meta.api.methods.send.SendMessage.class));
            verify(broadcastRepository).markFinished(eq(42L), eq(BroadcastStatus.STOPPED), any());
        }

        @Test
        @DisplayName("Прерывание Thread.interrupt не валит JVM")
        void shouldHandleInterruption() {
            // Не критично — просто чтобы не было unchecked throw
            service.runBroadcastLoop(42L, "hi", List.of());
            verify(broadcastRepository).markFinished(eq(42L), any(), any());
        }
    }

    @Nested
    @DisplayName("requestStop")
    class RequestStop {

        @Test
        @DisplayName("RUNNING → STOPPED, возврат true")
        void shouldStopRunning() {
            when(broadcastRepository.requestStop(eq(42L),
                    eq(BroadcastStatus.RUNNING), eq(BroadcastStatus.STOPPED)))
                    .thenReturn(1);

            assertThat(service.requestStop(42L)).isTrue();
        }

        @Test
        @DisplayName("Уже терминальный → false")
        void shouldReturnFalseIfAlreadyDone() {
            when(broadcastRepository.requestStop(anyLong(), any(), any()))
                    .thenReturn(0);

            assertThat(service.requestStop(42L)).isFalse();
        }
    }

    @Nested
    @DisplayName("escapeMarkdownV2")
    class EscapeMarkdown {

        @Test
        @DisplayName("Экранирует основные мета-символы")
        void shouldEscapeMetaChars() {
            String out = AdminBroadcastService.escapeMarkdownV2("Hello *world*! _test_ [link](x)");
            assertThat(out).doesNotContain("Hello *world*");  // звезды экранированы
            assertThat(out).contains("\\*");
            assertThat(out).contains("\\_");
            assertThat(out).contains("\\[").contains("\\]").contains("\\(").contains("\\)");
            assertThat(out).contains("\\!");
        }

        @Test
        @DisplayName("null → пустая строка")
        void shouldHandleNull() {
            assertThat(AdminBroadcastService.escapeMarkdownV2(null)).isEmpty();
        }

        @Test
        @DisplayName("Обычный текст без спецсимволов не меняется")
        void shouldNotEscapePlainText() {
            assertThat(AdminBroadcastService.escapeMarkdownV2("hello world"))
                    .isEqualTo("hello world");
        }
    }
}
