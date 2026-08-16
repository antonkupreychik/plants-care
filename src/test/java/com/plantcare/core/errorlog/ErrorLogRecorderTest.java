package com.plantcare.core.errorlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Юнит-тесты буфера журнала ошибок (issue #97). Репозиторий замокан — здесь проверяется
 * поведение очереди: неблокирующий приём, батчи и AC «без замедления API».
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorLogRecorder — неблокирующая очередь и батч-инсерт (#97)")
class ErrorLogRecorderTest {

    @Mock
    private ErrorLogRepository repository;

    @Test
    @DisplayName("should_write_queued_entries_when_flushed")
    void should_write_queued_entries_when_flushed() {
        ErrorLogRecorder recorder = recorder(props(10, 10));
        recorder.record(entry("first"));
        recorder.record(entry("second"));

        int written = recorder.flush();

        assertThat(written).isEqualTo(2);
        assertThat(recorder.queueSize()).isZero();
        ArgumentCaptor<List<ErrorLogEntry>> captor = ArgumentCaptor.captor();
        verify(repository).insertBatch(captor.capture());
        assertThat(captor.getValue()).extracting(ErrorLogEntry::message)
                .containsExactly("first", "second");
    }

    @Test
    @DisplayName("should_split_into_batches_when_queue_exceeds_batch_size")
    void should_split_into_batches_when_queue_exceeds_batch_size() {
        ErrorLogRecorder recorder = recorder(props(10, 2));
        for (int i = 0; i < 5; i++) {
            recorder.record(entry("e" + i));
        }

        int written = recorder.flush();

        assertThat(written).isEqualTo(5);
        verify(repository, times(3)).insertBatch(anyList());
    }

    @Test
    @DisplayName("should_drop_events_when_queue_is_full")
    void should_drop_events_when_queue_is_full() {
        ErrorLogRecorder recorder = recorder(props(2, 10));

        assertThat(recorder.record(entry("a"))).isTrue();
        assertThat(recorder.record(entry("b"))).isTrue();
        boolean thirdAccepted = recorder.record(entry("c"));

        assertThat(thirdAccepted).isFalse();
        assertThat(recorder.droppedCount()).isEqualTo(1);
        assertThat(recorder.queueSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("should_do_nothing_when_queue_is_empty")
    void should_do_nothing_when_queue_is_empty() {
        ErrorLogRecorder recorder = recorder(props(10, 10));

        int written = recorder.flush();

        assertThat(written).isZero();
        verify(repository, never()).insertBatch(anyList());
    }

    @Test
    @DisplayName("should_ignore_null_entry_when_recording")
    void should_ignore_null_entry_when_recording() {
        ErrorLogRecorder recorder = recorder(props(10, 10));

        assertThat(recorder.record(null)).isFalse();
        assertThat(recorder.queueSize()).isZero();
    }

    /**
     * Падение БД не должно останавливать фоновый поток: {@code scheduleWithFixedDelay}
     * после исключения больше не запускается, поэтому {@code stop()} обязан проглотить
     * ошибку последнего слива.
     */
    @Test
    @DisplayName("should_not_propagate_error_when_insert_fails_on_shutdown")
    void should_not_propagate_error_when_insert_fails_on_shutdown() {
        ErrorLogRecorder recorder = recorder(props(10, 10));
        doThrow(new IllegalStateException("db is down")).when(repository).insertBatch(anyList());
        recorder.record(entry("boom"));

        recorder.stop();

        verify(repository).insertBatch(anyList());
    }

    private ErrorLogRecorder recorder(ErrorLogProperties properties) {
        return new ErrorLogRecorder(repository, properties);
    }

    private static ErrorLogProperties props(int queueCapacity, int batchSize) {
        return new ErrorLogProperties(null, queueCapacity, batchSize,
                Duration.ofSeconds(30), null, null, null);
    }

    private static ErrorLogEntry entry(String message) {
        return new ErrorLogEntry(Instant.now(), "ERROR", "logger", message,
                null, "fp", null, null, null, null, "main");
    }
}
