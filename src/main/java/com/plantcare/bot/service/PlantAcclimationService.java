package com.plantcare.bot.service;

import com.plantcare.bot.domain.Plant;
import com.plantcare.bot.domain.User;
import com.plantcare.bot.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Управление режимом акклиматизации новых растений (issue #75).
 *
 * <p>В режиме акклиматизации (первые ~21 день после добавления) бот ведёт
 * растение мягче: пуш полива становится опросным («сухо/влажно/не знаю»
 * вместо «полил/отложить/пропустить»), периодически приходят check-in
 * вопросы «как выглядит растение?».
 *
 * <p>Сами расписания и интервалы режим НЕ меняет — только UX уведомлений.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlantAcclimationService {

    /** Длительность режима в днях (v1: фикс, без выбора пользователем). */
    public static final int ACCLIMATION_DAYS = 21;

    /** Период между check-in вопросами в днях. */
    public static final int CHECKIN_INTERVAL_DAYS = 3;

    private final PlantRepository plantRepository;

    /**
     * Включить режим акклиматизации на {@link #ACCLIMATION_DAYS} дней.
     * Первый check-in планируем через {@link #CHECKIN_INTERVAL_DAYS} дней —
     * чтобы не спамить сразу после создания.
     *
     * @return обновлённое растение; {@code null}, если оно не принадлежит юзеру
     *         или архивировано.
     */
    @Transactional
    public Plant enable(User user, Long plantId) {
        Plant plant = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        plant.setAcclimationUntil(now.plusDays(ACCLIMATION_DAYS));
        plant.setAcclimationCheckinNextAt(now.plusDays(CHECKIN_INTERVAL_DAYS));
        Plant saved = plantRepository.save(plant);
        log.info("Acclimation enabled: plant={}, until={}", plantId, saved.getAcclimationUntil());
        return saved;
    }

    /**
     * Немедленно выключить режим. Идемпотентно — повторный вызов не падает,
     * просто ничего не делает.
     */
    @Transactional
    public Plant disable(User user, Long plantId) {
        Plant plant = plantRepository
                .findByUserIdAndIdAndArchivedAtIsNull(user.getId(), plantId)
                .orElse(null);
        if (plant == null) {
            return null;
        }
        plant.setAcclimationUntil(null);
        plant.setAcclimationCheckinNextAt(null);
        Plant saved = plantRepository.save(plant);
        log.info("Acclimation disabled: plant={}", plantId);
        return saved;
    }

    /**
     * Запланировать следующий check-in. Вызывается после успешной отправки
     * текущего check-in'а. Если acclimation_until уже в прошлом — не планируем
     * (на этой итерации hourly-тик и завершит режим).
     */
    @Transactional
    public void scheduleNextCheckin(Plant plant) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.plusDays(CHECKIN_INTERVAL_DAYS);
        // Не планируем check-in за пределами окна акклиматизации.
        if (plant.getAcclimationUntil() != null && next.isAfter(plant.getAcclimationUntil())) {
            plant.setAcclimationCheckinNextAt(null);
        } else {
            plant.setAcclimationCheckinNextAt(next);
        }
        plantRepository.save(plant);
    }

    /** Растения, у которых пора завершить режим (acclimation_until ≤ now). */
    @Transactional(readOnly = true)
    public List<Plant> findPlantsToFinish(LocalDateTime now) {
        return plantRepository.findFinishedAcclimation(now);
    }

    /** Растения, у которых пора задать check-in вопрос. */
    @Transactional(readOnly = true)
    public List<Plant> findPlantsForCheckin(LocalDateTime now) {
        return plantRepository.findPendingAcclimationCheckin(now);
    }

    /** Очистить acclimation-поля после отправки «акклиматизация завершена». */
    @Transactional
    public void finish(Plant plant) {
        plant.setAcclimationUntil(null);
        plant.setAcclimationCheckinNextAt(null);
        plantRepository.save(plant);
        log.info("Acclimation finished: plant={}", plant.getId());
    }

    /**
     * Прочитать растение по plantId БЕЗ привязки к user (для scheduler'а).
     */
    @Transactional(readOnly = true)
    public Optional<Plant> findById(Long plantId) {
        return plantRepository.findById(plantId);
    }
}
