package com.plantcare.core.repository;

import com.plantcare.core.domain.Photo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, Long> {

    /**
     * Активное (не soft-deleted) фото по id для авторизованного владельца.
     * Используется на GET/DELETE: чужое или удалённое не отдаём.
     */
    Optional<Photo> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * Массовый soft-delete всех активных фото юзера — GDPR-запрос «удалить всё
     * моё» из админки (issue #101). Физического удаления из бакета здесь нет:
     * его сделает отложенная чистка по истечении retention.
     *
     * <p>Идемпотентно: повторный вызов затронет 0 строк, так как условие
     * {@code deletedAt IS NULL} уже не выполняется. Момент первого удаления
     * не перезаписывается — отсчёт retention не сбрасывается.
     *
     * @return сколько фото переведено в soft-deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Photo p set p.deletedAt = :now where p.userId = :userId and p.deletedAt is null")
    int softDeleteAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Кандидаты на физическую чистку: soft-deleted раньше {@code cutoff} и ещё
     * не вычищенные из бакета (issue #101).
     *
     * <p>Сортировка по {@code deletedAt} — сначала самые старые, чтобы при
     * ограниченном батче очередь двигалась, а не топталась на одних и тех же.
     */
    @Query("""
            select p from Photo p
            where p.deletedAt is not null
              and p.deletedAt < :cutoff
              and p.purgedAt is null
            order by p.deletedAt asc
            """)
    List<Photo> findPurgeCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Отмечает объект физически вычищенным из бакета (issue #101).
     *
     * <p>Отдельный {@code @Modifying}-апдейт, а не мутация entity, намеренно:
     * вызывается ПОСЛЕ обращения к S3, вне какой-либо открытой транзакции к БД
     * (правило CLAUDE.md — внешние API не зовём внутри транзакции). Каждый
     * вызов — своя короткая транзакция.
     *
     * <p>Условие {@code purgedAt IS NULL} делает операцию идемпотентной:
     * повторный прогон вернёт 0 и не перезапишет момент первой чистки.
     *
     * @return 1, если строка обновлена; 0, если уже была вычищена
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Photo p set p.purgedAt = :now where p.id = :id and p.purgedAt is null")
    int markPurged(@Param("id") Long id, @Param("now") Instant now);
}
