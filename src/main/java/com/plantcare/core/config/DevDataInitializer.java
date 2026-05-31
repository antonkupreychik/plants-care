package com.plantcare.core.config;

import com.plantcare.core.domain.Location;
import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.UserRepository;
import com.plantcare.core.service.BackdatedCareService;
import com.plantcare.core.service.LocationService;
import com.plantcare.core.service.PlantService;
import com.plantcare.core.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Сид тестовых данных для локальной мобильной разработки (issue #171).
 *
 * <p>Активен только в профиле {@code dev} — на {@code prod} (Railway) не подключается.
 * Создаёт одного dev-пользователя с парой локаций, растениями (с расписанием полива)
 * и историей ухода, чтобы свежий локальный бэкенд не был пустым.
 *
 * <p>Сидим только когда таблица {@code users} пуста: тогда первый вставленный
 * пользователь получает {@code id = 1}, под который маппит auth dev-bypass
 * ({@code plantcare.auth.dev-user-id}, см. {@code ApiSecurityConfig}). Если в БД
 * уже есть пользователи — ничего не делаем (идемпотентно, без дублей).
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataInitializer implements ApplicationRunner {

    /** Синтетический telegram chat id для dev-пользователя. */
    private static final long DEV_CHAT_ID = 999_000_001L;

    private final UserRepository userRepository;
    private final UserService userService;
    private final LocationService locationService;
    private final PlantService plantService;
    private final BackdatedCareService backdatedCareService;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("[dev-seed] users table not empty — пропускаю сид тестовых данных");
            return;
        }

        log.info("[dev-seed] пустая БД — создаю dev-пользователя и тестовые растения");

        User user = userService.findOrCreate(DEV_CHAT_ID, "dev");

        Location windowsill = locationService.createLocation(user, "Подоконник", "🪟");
        Location balcony = locationService.createLocation(user, "Балкон", "🌿");

        LocalDateTime now = LocalDateTime.now(clock);

        // Растение с просроченным поливом — попадёт в /today сразу.
        Plant monstera = plantService.createPlantWithWateringSchedule(
                user, null, "Monstera", 7, now.minusDays(1), windowsill.getId());

        // Растение с поливом через несколько дней.
        plantService.createPlantWithWateringSchedule(
                user, null, "Фикус", 10, now.plusDays(3), balcony.getId());

        // Немного истории ухода, чтобы стрик/история были не пустыми.
        LocalDate today = LocalDate.now(clock);
        backdatedCareService.recordBackdatedCare(monstera, TaskType.WATERING, today.minusDays(8));
        backdatedCareService.recordBackdatedCare(monstera, TaskType.WATERING, today.minusDays(1));

        log.info("[dev-seed] готово: user id={}, локаций=2, растений=2", user.getId());
    }
}
