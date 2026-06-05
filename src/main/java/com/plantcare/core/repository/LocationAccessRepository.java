package com.plantcare.core.repository;

import com.plantcare.core.domain.LocationAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LocationAccessRepository extends JpaRepository<LocationAccess, Long> {

    Optional<LocationAccess> findBySubjectIdAndObjectId(Long subjectUserId, Long objectLocationId);

    boolean existsBySubjectIdAndObjectId(Long subjectUserId, Long objectLocationId);

    List<LocationAccess> findAllByObjectId(Long objectLocationId);

    List<LocationAccess> findAllBySubjectId(Long subjectUserId);

    /**
     * Все subject-пользователи (caretaker'ы) с доступом к локации. Используется
     * воркером шедулера для веерной рассылки уведомлений (issue #77): по
     * object_location_id собираются все subject_user_id.
     */
    @Query("""
            SELECT la.subject.id
            FROM LocationAccess la
            WHERE la.object.id = :locationId
            """)
    List<Long> findSubjectUserIdsByLocationId(@Param("locationId") Long locationId);

    /**
     * Telegram chat id всех caretaker'ов локации, у кого юзер не заблокирован и
     * у кого chat id вообще есть (issue #77). Используется шедулером для веерной
     * рассылки уведомлений — возвращает сразу chat id, без второго прохода по users.
     */
    @Query("""
            SELECT la.subject.telegramChatId
            FROM LocationAccess la
            WHERE la.object.id = :locationId
              AND la.subject.telegramChatId IS NOT NULL
              AND la.subject.blocked = false
            """)
    List<Long> findCaretakerChatIdsByLocationId(@Param("locationId") Long locationId);
}
