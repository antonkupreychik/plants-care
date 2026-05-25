package com.plantcare.core.service;

import com.plantcare.core.domain.Plant;
import com.plantcare.core.domain.PlantAnniversarySent;
import com.plantcare.core.domain.User;
import com.plantcare.core.domain.enums.TaskType;
import com.plantcare.core.repository.CareHistoryRepository;
import com.plantcare.core.repository.PlantAnniversarySentRepository;
import com.plantcare.core.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Сервис «годовщин» растений (issue #117) — раз в год, в день, когда юзер
 * завёл растение, шедулер должен отправить ему пуш «🎂 Сегодня твоей {имя}
 * {N} лет».
 *
 * <p>Идемпотентность реализована таблицей {@code plant_anniversaries_sent}
 * (PK {@code (plant_id, anniversary_year)}). Год берётся в TZ юзера — это
 * исключает дубли на стыке суток UTC.
 *
 * <p>Telegram-вызов остаётся за рамками сервиса. Сервис подбирает кандидатов
 * в read-only транзакции и для каждого предоставляет {@link Anniversary}-DTO,
 * чтобы вызывающий шедулер отправил пуш вне транзакции. После успешной отправки
 * шедулер дёргает {@link #markSent} в отдельной транзакции.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlantAnniversaryService {

    private final PlantRepository plantRepository;
    private final PlantAnniversarySentRepository sentRepository;
    private final CareHistoryRepository careHistoryRepository;

    /**
     * Подобрать растения, у которых сегодня (в TZ юзера) годовщина и которым
     * сегодня годовщину ещё не слали. Сервис возвращает уже готовый DTO с
     * текстом, который надо отправить — Telegram-вызов делает caller, чтобы
     * не держать транзакцию открытой во время медленного HTTP.
     *
     * @param now момент проверки (UTC). Передаётся явно — для unit-тестов
     *            и для согласованности с {@code Clock}-бином в caller'е.
     */
    @Transactional(readOnly = true)
    public List<Anniversary> findDueAnniversaries(Instant now) {
        List<Plant> candidates = plantRepository.findActiveWithAcquiredDate();
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Anniversary> due = new ArrayList<>();
        for (Plant plant : candidates) {
            User user = plant.getUser();
            if (user == null || user.isBlocked()) {
                continue;
            }

            ZoneId zone = safeZone(user.getTimezone());
            LocalDate todayLocal = now.atZone(zone).toLocalDate();
            LocalDate acquired = plant.getAcquiredAt();

            if (!isAnniversaryDay(acquired, todayLocal)) {
                continue;
            }

            int yearsOld = Period.between(acquired, todayLocal).getYears();
            if (yearsOld < 1) {
                continue;
            }

            int anniversaryYear = todayLocal.getYear();
            if (sentRepository.existsByPlantIdAndAnniversaryYear(plant.getId(), anniversaryYear)) {
                continue;
            }

            long waterings = careHistoryRepository.countActiveByPlantIdAndTaskType(
                    plant.getId(), TaskType.WATERING);

            due.add(new Anniversary(
                    plant.getId(),
                    plant.getName(),
                    user.getId(),
                    user.getTelegramChatId(),
                    yearsOld,
                    waterings,
                    anniversaryYear
            ));
        }
        return due;
    }

    /**
     * Зафиксировать факт отправки годовщины — отдельная транзакция, чтобы
     * caller мог дёрнуть её сразу после успешного Telegram send.
     *
     * <p>На случай race (две параллельные отправки) ловим
     * {@link DataIntegrityViolationException} — UPSERT-семантика на уровне сервиса.
     */
    @Transactional
    public void markSent(Long plantId, int anniversaryYear, Instant sentAt) {
        try {
            sentRepository.save(new PlantAnniversarySent(plantId, anniversaryYear, sentAt));
        } catch (DataIntegrityViolationException e) {
            // Гонка — запись уже есть. Идемпотентно, не падаем.
            log.debug("Anniversary already marked for plant={} year={}", plantId, anniversaryYear);
        }
    }

    /**
     * DTO с готовыми данными для одного пуша годовщины. Сделан record — чтобы
     * шедулер мог использовать поля напрямую без лазания в entity, у которых
     * уже могла закрыться JPA-сессия.
     */
    public record Anniversary(
            Long plantId,
            String plantName,
            Long userId,
            Long telegramChatId,
            int yearsOld,
            long waterings,
            int anniversaryYear
    ) {
        /**
         * Готовый текст уведомления. Формат — по issue #117.
         */
        public String buildText() {
            return "🎂 Сегодня твоей " + plantName + " "
                    + yearsOld + " " + pluralizeYears(yearsOld) + "! "
                    + "За это время ты полил её "
                    + waterings + " " + pluralizeWaterings(waterings) + ". "
                    + "Так держать 🌱";
        }
    }

    private static String pluralizeYears(int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "год";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "года";
        return "лет";
    }

    private static String pluralizeWaterings(long n) {
        long mod10 = Math.abs(n) % 10;
        long mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return "раз";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "раза";
        return "раз";
    }

    /**
     * Совпадает ли сегодня (в TZ юзера) день/месяц с {@code acquired}.
     * Особый случай: растение, заведённое 29 февраля високосного года,
     * в обычные годы получает годовщину 28 февраля — как договорено
     * по аналогии с большинством юрисдикций для людских дней рождения.
     */
    static boolean isAnniversaryDay(LocalDate acquired, LocalDate today) {
        if (acquired.getMonthValue() == today.getMonthValue()
                && acquired.getDayOfMonth() == today.getDayOfMonth()) {
            return true;
        }
        return acquired.getMonthValue() == 2
                && acquired.getDayOfMonth() == 29
                && today.getMonthValue() == 2
                && today.getDayOfMonth() == 28
                && !today.isLeapYear();
    }

    private static ZoneId safeZone(String tz) {
        if (tz == null || tz.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }
}
