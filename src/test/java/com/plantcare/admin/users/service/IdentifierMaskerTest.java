package com.plantcare.admin.users.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdentifierMasker — маскирование чувствительных идентификаторов (issue #93)")
class IdentifierMaskerTest {

    @Test
    @DisplayName("should_keep_two_chars_and_domain_when_masking_email")
    void should_keep_two_chars_and_domain_when_masking_email() {
        String masked = IdentifierMasker.maskEmail("alexander@example.com");

        assertThat(masked).isEqualTo("al" + IdentifierMasker.HIDDEN + "@example.com");
        assertThat(masked).doesNotContain("alexander");
    }

    @Test
    @DisplayName("should_hide_local_part_entirely_when_it_is_shorter_than_three_chars")
    void should_hide_local_part_entirely_when_it_is_shorter_than_three_chars() {
        assertThat(IdentifierMasker.maskEmail("ab@example.com"))
                .isEqualTo(IdentifierMasker.HIDDEN + "@example.com");
    }

    @Test
    @DisplayName("should_hide_everything_when_value_is_not_an_email")
    void should_hide_everything_when_value_is_not_an_email() {
        assertThat(IdentifierMasker.maskEmail("no-at-sign")).isEqualTo(IdentifierMasker.HIDDEN);
        assertThat(IdentifierMasker.maskEmail("@example.com")).isEqualTo(IdentifierMasker.HIDDEN);
        assertThat(IdentifierMasker.maskEmail("user@")).isEqualTo(IdentifierMasker.HIDDEN);
    }

    @Test
    @DisplayName("should_return_null_when_email_is_null_or_blank")
    void should_return_null_when_email_is_null_or_blank() {
        assertThat(IdentifierMasker.maskEmail(null)).isNull();
        assertThat(IdentifierMasker.maskEmail("   ")).isNull();
    }

    @Test
    @DisplayName("should_keep_only_edges_when_masking_provider_subject")
    void should_keep_only_edges_when_masking_provider_subject() {
        String subject = "001234.abcdef0123456789abcdef.5678";

        String masked = IdentifierMasker.maskSubject(subject);

        assertThat(masked).isEqualTo("0012" + IdentifierMasker.HIDDEN + "5678");
        assertThat(masked).doesNotContain("abcdef");
    }

    @Test
    @DisplayName("should_hide_short_subject_entirely_when_masking_would_leak_almost_everything")
    void should_hide_short_subject_entirely_when_masking_would_leak_almost_everything() {
        assertThat(IdentifierMasker.maskSubject("12345678")).isEqualTo(IdentifierMasker.HIDDEN);
        assertThat(IdentifierMasker.maskSubject("123456789"))
                .isEqualTo("1234" + IdentifierMasker.HIDDEN + "6789");
    }

    @Test
    @DisplayName("should_keep_last_four_digits_when_masking_chat_id")
    void should_keep_last_four_digits_when_masking_chat_id() {
        assertThat(IdentifierMasker.maskChatId(123456789L))
                .isEqualTo(IdentifierMasker.HIDDEN + "6789");
    }

    @Test
    @DisplayName("should_hide_short_or_negative_chat_id_without_overflow")
    void should_hide_short_or_negative_chat_id_without_overflow() {
        assertThat(IdentifierMasker.maskChatId(42L)).isEqualTo(IdentifierMasker.HIDDEN);
        assertThat(IdentifierMasker.maskChatId(Long.MIN_VALUE))
                .isEqualTo(IdentifierMasker.HIDDEN + "5808");
        assertThat(IdentifierMasker.maskChatId(null)).isNull();
    }
}
