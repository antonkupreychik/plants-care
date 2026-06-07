package com.plantcare.core.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Issue #279, эпик #277 фаза 1: конфигурация ShedLock JDBC-провайдера.
 *
 * <p>Используется Postgres, который уже является hard-dependency проекта,
 * поэтому надёжнее Redis: падение Redis не останавливает уведомления.
 * Таблица {@code shedlock} создаётся миграцией V46.
 */
@Configuration
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime() // use DB time to avoid clock skew between instances
                        .build()
        );
    }
}
