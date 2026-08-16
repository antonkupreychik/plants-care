package com.plantcare.bot.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Общая тестовая конфигурация с фиксированным {@link Clock}.
 *
 * <p>Импортируется несколькими интеграционными тестами вместо определения
 * дублирующих inner-классов {@code FixedClockConfig} в каждом тесте. Это
 * гарантирует, что все тесты с одинаковым FIXED_NOW получают <em>один</em>
 * кэшированный {@code ApplicationContext} Spring (а значит — один экземпляр
 * {@link net.iakovlev.timeshape.TimeZoneEngine}), а не N отдельных контекстов
 * с N отдельными движками (issue #239).
 *
 * <p>Выбранный момент {@code 2026-05-25T00:30:00Z} (00:30 UTC) специально
 * создаёт пограничный кейс для восточных TZ (Asia/Almaty UTC+5): локальная
 * дата уже «сегодня», тогда как UTC-полночь и локальная-полночь расходятся
 * на день — это проверяет TZ-кейсы на границе суток.
 */
@TestConfiguration
public class FixedClockTestConfig {

    /** Фиксированный «сейчас» для детерминированных тестов с Clock. */
    public static final Instant FIXED_NOW = Instant.parse("2026-05-25T00:30:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
}
