package com.plantcare.core.domain;

import com.plantcare.core.domain.base.BaseEntity;
import com.plantcare.core.domain.enums.ConversationState;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    /**
     * Telegram chat id. Стал nullable в V31 (issue #88): пользователь может
     * зарегистрироваться через мобильное приложение (email/Apple/Google) без
     * Telegram. Уникальность среди не-NULL значений — через inline UNIQUE-индекс.
     */
    @Column(name = "telegram_chat_id", unique = true)
    private Long telegramChatId;

    @Column(length = 255)
    private String username;

    // ===== Mobile auth (issue #88, ADR-011) =====

    /** Email для входа по magic link / связки с OAuth. NULL для чисто Telegram-юзеров. */
    @Column(name = "email", length = 320)
    private String email;

    /** TRUE после подтверждения email (magic link / verified OAuth-провайдером). */
    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    /** Стабильный subject (sub) из Sign in with Apple. NULL если Apple не привязан. */
    @Column(name = "apple_subject", length = 255)
    private String appleSubject;

    /** Стабильный subject (sub) из Google ID token. NULL если Google не привязан. */
    @Column(name = "google_subject", length = 255)
    private String googleSubject;

    /**
     * Эпоха валидности refresh-токенов (issue #178, V37). Refresh с
     * {@code iat < tokens_valid_from} считается невалидным (logout-all).
     * NULL = эпоха не задана, проверка пропускается (backward-compat).
     * Тип Instant — сравнивается с JWT {@code iat}.
     */
    @Column(name = "tokens_valid_from")
    private Instant tokensValidFrom;

    @Column(nullable = false, length = 64)
    @Builder.Default
    private String timezone = "Europe/Minsk";

    /** Язык интерфейса/уведомлений мобильного клиента: {@code ru} | {@code en} (issue #182, V34). */
    @Column(nullable = false, length = 8)
    @Builder.Default
    private String locale = "ru";

    @Column(name = "quiet_hours_start", nullable = false)
    @Builder.Default
    private LocalTime quietHoursStart = LocalTime.of(22, 0);

    @Column(name = "quiet_hours_end", nullable = false)
    @Builder.Default
    private LocalTime quietHoursEnd = LocalTime.of(9, 0);

    @Column(name = "paused_until")
    private LocalDateTime pausedUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "conversation_state", nullable = false, length = 64)
    @Builder.Default
    private ConversationState conversationState = ConversationState.IDLE;

    @Type(JsonType.class)
    @Column(name = "state_data", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> stateData = new HashMap<>();

    /**
     * Per-user feature flags (issue #78). Хранятся как JSONB-объект:
     * {@code { "sharing": "true", "experiment_a": "variant_b" }}.
     * Проверка делается через {@link #hasFeature(String)} — это убирает
     * вопросы о null-map'ах и приведении типов в местах вызова.
     */
    @Type(JsonType.class)
    @Column(name = "feature_flags", columnDefinition = "jsonb", nullable = false)
    @Builder.Default
    private Map<String, Object> featureFlags = new HashMap<>();

    @Column(name = "is_blocked", nullable = false)
    @Builder.Default
    private boolean blocked = false;

    /**
     * Issue #79: opaque token for public .ics calendar URL.
     * NULL until first calendar export request — generated lazily by {@link com.plantcare.core.service.CalendarService}.
     * Uniqueness enforced by partial unique index {@code idx_users_calendar_token} (see V14 migration).
     */
    @Column(name = "calendar_token", length = 64, unique = true)
    private String calendarToken;

    // ===== Weather integration (issue #69) =====

    /** Учитывать погоду в рекомендациях; default false — opt-in через настройки. */
    // ===== Мультидомность (issue #70) =====

    /**
     * «Текущая» локация пользователя в Telegram-боте. Задачи «сегодня» и
     * навигация работают в контексте именно этой локации. NULL означает, что
     * активная локация ещё не выбрана — в этом случае показываем все локации.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "active_location_id")
    private Location activeLocation;

    @Column(name = "weather_enabled", nullable = false)
    @Builder.Default
    private boolean weatherEnabled = false;

    /** Широта точки, по которой запрашиваем Open-Meteo. */
    @Column(name = "weather_lat")
    private Double weatherLat;

    /** Долгота. */
    @Column(name = "weather_lon")
    private Double weatherLon;

    /** Когда последний раз ходили в Open-Meteo (для 60-мин кеша). */
    @Column(name = "weather_last_fetch_at")
    private LocalDateTime weatherLastFetchAt;

    /** Последнее значение RH (relative humidity, %), нужно для рендера из кеша. */
    @Column(name = "weather_last_rh")
    private Integer weatherLastRh;

    // ===== Seasonal intervals (issue #67) — глобальные настройки юзера =====

    /** Учитывать сезоны при расчёте next_due_at; default false — opt-in. */
    @Column(name = "seasonal_enabled", nullable = false)
    @Builder.Default
    private boolean seasonalEnabled = false;

    /** Режим: MULTIPLIER (коэф) или FIXED (фиксированные интервалы). */
    @Enumerated(EnumType.STRING)
    @Column(name = "seasonal_mode", nullable = false, length = 16)
    @Builder.Default
    private com.plantcare.core.domain.enums.SeasonalMode seasonalMode =
            com.plantcare.core.domain.enums.SeasonalMode.MULTIPLIER;

    /** Границы сезонов хранятся как MMDD: 401 = 1 апреля, 1001 = 1 октября. */
    @Column(name = "summer_start_mmdd", nullable = false)
    @Builder.Default
    private int summerStartMmdd = 401;

    @Column(name = "winter_start_mmdd", nullable = false)
    @Builder.Default
    private int winterStartMmdd = 1001;

    /** Коэффициент для лета (NUMERIC(3,2) в БД). По умолчанию 0.80. */
    @Column(name = "summer_multiplier", nullable = false, precision = 3, scale = 2)
    @Builder.Default
    private java.math.BigDecimal summerMultiplier = new java.math.BigDecimal("0.80");

    /** Коэффициент для зимы. По умолчанию 1.20. */
    @Column(name = "winter_multiplier", nullable = false, precision = 3, scale = 2)
    @Builder.Default
    private java.math.BigDecimal winterMultiplier = new java.math.BigDecimal("1.20");

    /** Фиксированный интервал на лето (для режима FIXED), null → fallback на базовый. */
    @Column(name = "summer_interval_override_days")
    private Integer summerIntervalOverrideDays;

    /** Фиксированный интервал на зиму (для режима FIXED), null → fallback на базовый. */
    @Column(name = "winter_interval_override_days")
    private Integer winterIntervalOverrideDays;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void resetConversation() {
        this.conversationState = ConversationState.IDLE;
        this.stateData = new HashMap<>();
    }

    public boolean isPaused() {
        return pausedUntil != null && pausedUntil.isAfter(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Включён ли указанный feature flag для юзера. Значение интерпретируется как
     * boolean по правилу «строка "true"» — это совпадает с описанием из issue
     * ({@code additionalData["featureCode"] == "true"}). Любое другое значение
     * (отсутствие ключа, "false", произвольный variant string) → {@code false}.
     */
    public boolean hasFeature(String flagCode) {
        if (flagCode == null || featureFlags == null) return false;
        Object v = featureFlags.get(flagCode);
        return v != null && "true".equals(String.valueOf(v));
    }

    /** Есть ли точка для запроса погоды (обе координаты заполнены). */
    public boolean hasWeatherLocation() {
        return weatherLat != null && weatherLon != null;
    }

    /** Погода реально используется в рекомендациях — toggle вкл + локация задана. */
    public boolean isWeatherUsable() {
        return weatherEnabled && hasWeatherLocation();
    }
}