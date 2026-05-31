package com.plantcare.admin.exception;

import jakarta.persistence.EntityNotFoundException;

/**
 * Факт не найден или не принадлежит указанному виду (issue #131).
 *
 * <p>Используется как защита от подмены {@code factId}: редактировать/удалять
 * можно только факт, привязанный к виду из URL.
 *
 * <p>Наследуется от {@link EntityNotFoundException}, чтобы попасть в общий
 * 404-хендлер {@code AdminControllerAdvice} (тело = сообщение, статус 404),
 * консистентно с прочими not-found ошибками админки.
 */
public class SpeciesFactNotFoundException extends EntityNotFoundException {

    public SpeciesFactNotFoundException(Long factId, Long speciesId) {
        super("Факт id=" + factId + " не найден для вида id=" + speciesId);
    }
}
