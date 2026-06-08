package com.plantcare.core.repository;

import com.plantcare.core.domain.Photo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, Long> {

    /**
     * Активное (не soft-deleted) фото по id для авторизованного владельца.
     * Используется на GET/DELETE: чужое или удалённое не отдаём.
     */
    Optional<Photo> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);
}
