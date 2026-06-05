package com.plantcare.core.beans;

import net.iakovlev.timeshape.TimeZoneEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрирует {@link TimeZoneEngine} как Spring-бин.
 *
 * <p>{@code TimeZoneEngine.initialize()} грузит гео-датасет всего мира (сотни МБ
 * в heap) и иммутабелен после построения. В проде это один экземпляр на один
 * контекст приложения. В тестах же поднимается множество {@code @SpringBootTest}
 * контекстов с разными ключами кэша (разные {@code Clock}-конфиги, MockMvc,
 * MockBean), и наивная реализация создавала бы СВОЙ движок на каждый контекст —
 * несколько сотен-МБ датасетов одновременно живут в heap → кумулятивный
 * {@code OutOfMemoryError} (issue #239).
 *
 * <p>Поэтому реальный движок инициализируется лениво ровно один раз на JVM и
 * переиспользуется всеми контекстами через статический holder. Поведение в проде
 * не меняется (тот же полноразмерный движок, единственный экземпляр), а тестовый
 * прогон перестаёт держать N копий датасета.
 */
@Configuration
public class TimeZoneEngineBean {

    @Bean
    public TimeZoneEngine timeZoneEngine() {
        return Holder.INSTANCE;
    }

    /**
     * Lazy-инициализация по идиоме holder-класса: датасет грузится при первом
     * обращении к {@link #INSTANCE}, потокобезопасно и без явной синхронизации.
     */
    private static final class Holder {
        private static final TimeZoneEngine INSTANCE = TimeZoneEngine.initialize();

        private Holder() {
        }
    }
}
