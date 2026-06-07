package com.plantcare.core.repository;

import com.plantcare.core.domain.UiView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Доступ к шаблонам композиции экранов SDUI (issue #284).
 */
@Repository
public interface UiViewRepository extends JpaRepository<UiView, Long> {

    /** Шаблон экрана по идентификатору (например, {@code home}). */
    Optional<UiView> findByScreen(String screen);
}
