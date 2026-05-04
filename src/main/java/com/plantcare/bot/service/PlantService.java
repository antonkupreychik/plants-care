package com.plantcare.bot.service;

import com.plantcare.bot.domain.CareSchedule;
import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.Species;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.domain.enums.TaskType;
import com.plantcare.bot.repository.CareScheduleRepository;
import com.plantcare.bot.repository.PlantRepository;
import com.plantcare.bot.repository.SpeciesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlantService {

    private final PlantRepository plantRepository;
    private final CareScheduleRepository careScheduleRepository;
    private final SpeciesRepository speciesRepository;

    /**
     * Получить топ популярных видов для отображения на первом экране
     */
    @Transactional(readOnly = true)
    public List<Species> getPopularSpecies(int limit) {
        return speciesRepository.findAllByOrderByPopularityDesc(
                org.springframework.data.domain.Limit.of(limit)
        );
    }

    /**
     * Поиск видов по названию или тегам
     */
    @Transactional(readOnly = true)
    public List<Species> searchSpecies(String query, int limit) {
        return speciesRepository.searchByQuery(query, limit);
    }

    /**
     * Получить вид по ID
     */
    @Transactional(readOnly = true)
    public Optional<Species> getSpeciesById(Long speciesId) {
        return speciesRepository.findById(speciesId);
    }

    /**
     * Создать новое растение с расписанием полива
     *
     * @param user юзер-владелец
     * @param speciesId ID вида (может быть null для "своего" растения)
     * @param name имя растения ("Монстера в гостиной")
     * @param intervalDays интервал полива в днях
     * @param nextDueAt когда следующий полив (рассчитано на фронте)
     * @return сохранённое растение с расписанием
     */
    @Transactional
    public Plant createPlantWithWateringSchedule(
            User user,
            Long speciesId,
            String name,
            Integer intervalDays,
            LocalDateTime nextDueAt
    ) {
        // Создаём растение
        Plant plant = Plant.builder()
                .user(user)
                .species(speciesId != null ? speciesRepository.findById(speciesId).orElse(null) : null)
                .name(name)
                .room(null)  // На MVP room_id = NULL
                .build();

        plant = plantRepository.save(plant);
        log.info("Created plant '{}' (id={}) for user {}", name, plant.getId(), user.getTelegramChatId());

        // Создаём расписание полива
        CareSchedule wateringSchedule = CareSchedule.builder()
                .plant(plant)
                .taskType(TaskType.WATERING)
                .intervalDays(intervalDays)
                .nextDueAt(nextDueAt)
                .active(true)
                .build();

        careScheduleRepository.save(wateringSchedule);
        log.info("Created watering schedule for plant {} (nextDueAt={})", plant.getId(), nextDueAt);

        plant.addSchedule(wateringSchedule);
        return plant;
    }

    /**
     * Валидация имени растения
     */
    public static boolean isValidPlantName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        return !trimmed.isEmpty() && trimmed.length() <= 100;
    }

    /**
     * Валидация интервала полива
     */
    public static boolean isValidInterval(int days) {
        return days >= 1 && days <= 365;
    }
}