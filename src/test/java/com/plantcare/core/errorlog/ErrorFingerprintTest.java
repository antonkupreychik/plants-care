package com.plantcare.core.errorlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorFingerprint — ключ схлопывания одинаковых ошибок (#97)")
class ErrorFingerprintTest {

    @Test
    @DisplayName("should_ignore_message_when_exception_and_frame_are_same")
    void should_ignore_message_when_exception_and_frame_are_same() {
        String first = ErrorFingerprint.of("java.lang.IllegalStateException",
                "com.plantcare.bot.Svc.run(Svc.java:42)", "com.plantcare.bot.Svc", "plant 1 not found");
        String second = ErrorFingerprint.of("java.lang.IllegalStateException",
                "com.plantcare.bot.Svc.run(Svc.java:42)", "com.plantcare.bot.Svc", "plant 999 not found");

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo("java.lang.IllegalStateException at com.plantcare.bot.Svc.run(Svc.java:42)");
    }

    @Test
    @DisplayName("should_split_groups_when_frame_differs")
    void should_split_groups_when_frame_differs() {
        String a = ErrorFingerprint.of("java.lang.IllegalStateException",
                "com.plantcare.bot.A.run(A.java:1)", "logger", "msg");
        String b = ErrorFingerprint.of("java.lang.IllegalStateException",
                "com.plantcare.bot.B.run(B.java:2)", "logger", "msg");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("should_fall_back_to_logger_and_message_when_no_throwable")
    void should_fall_back_to_logger_and_message_when_no_throwable() {
        String fingerprint = ErrorFingerprint.of(null, null, "com.plantcare.bot.Svc", "telegram timeout");

        assertThat(fingerprint).isEqualTo("com.plantcare.bot.Svc | telegram timeout");
    }

    @Test
    @DisplayName("should_mark_missing_stack_when_throwable_has_no_frames")
    void should_mark_missing_stack_when_throwable_has_no_frames() {
        String fingerprint = ErrorFingerprint.of("java.lang.RuntimeException", null, "logger", "msg");

        assertThat(fingerprint).isEqualTo("java.lang.RuntimeException at <no-stack>");
    }

    @Test
    @DisplayName("should_truncate_and_flatten_when_value_is_long_and_multiline")
    void should_truncate_and_flatten_when_value_is_long_and_multiline() {
        String fingerprint = ErrorFingerprint.of(null, null, "logger", "line1\nline2" + "x".repeat(1000));

        assertThat(fingerprint).hasSize(ErrorFingerprint.MAX_LENGTH);
        assertThat(fingerprint).doesNotContain("\n");
    }

    @Test
    @DisplayName("should_use_placeholder_when_logger_and_message_are_blank")
    void should_use_placeholder_when_logger_and_message_are_blank() {
        String fingerprint = ErrorFingerprint.of(null, null, "  ", null);

        assertThat(fingerprint).isEqualTo("unknown | unknown");
    }
}
