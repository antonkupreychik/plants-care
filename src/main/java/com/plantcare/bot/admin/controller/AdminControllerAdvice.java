package com.plantcare.bot.admin.controller;

import com.plantcare.bot.admin.exception.DuplicateSpeciesNameException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Обрабатывает ошибки только в админ-контроллерах (через basePackages).
 * При HTMX-PATCH вернёт читаемый текст и 400, который HTMX покажет в alert.
 */
@ControllerAdvice(basePackages = "com.plantcare.bot.admin.controller")
public class AdminControllerAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(DuplicateSpeciesNameException.class)
    public ResponseEntity<String> handleDuplicate(DuplicateSpeciesNameException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }
}
