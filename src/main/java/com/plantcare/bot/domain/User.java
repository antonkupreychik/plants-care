package com.plantcare.bot.domain;

import com.plantcare.bot.domain.base.BaseEntity;
import com.plantcare.bot.domain.enums.ConversationState;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

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

    @Column(name = "telegram_chat_id", nullable = false, unique = true)
    private Long telegramChatId;

    @Column(length = 255)
    private String username;

    @Column(nullable = false, length = 64)
    @Builder.Default
    private String timezone = "Europe/Minsk";

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

    // ===== Weather integration (issue #69) =====

    /** Учитывать погоду в рекомендациях; default false — opt-in через настройки. */
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
    private com.plantcare.bot.domain.enums.SeasonalMode seasonalMode =
            com.plantcare.bot.domain.enums.SeasonalMode.MULTIPLIER;

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