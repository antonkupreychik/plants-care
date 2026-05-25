package com.plantcare.admin.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Активен когда ADMIN_USERNAME / ADMIN_PASSWORD_BCRYPT_HASH не заданы.
 * Любой запрос в /admin/** → 503 «Admin panel is disabled».
 */
@RestController
@ConditionalOnExpression(
    "T(org.springframework.util.StringUtils).isEmpty('${admin.username:}') " +
    "or T(org.springframework.util.StringUtils).isEmpty('${admin.password-bcrypt-hash:}')"
)
public class DisabledAdminController {

    @RequestMapping(value = {"/admin", "/admin/**"})
    public ResponseEntity<String> disabled() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN).body("Admin panel is disabled");
    }
}
