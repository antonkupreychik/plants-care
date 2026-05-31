package com.plantcare.api.auth.verifier;

import com.plantcare.api.config.AuthProperties;
import com.plantcare.api.auth.exception.AuthTokenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Верификация Apple identity-token по Apple JWKS (issue #88).
 * {@link NimbusJwtDecoder#withJwkSetUri} кеширует ключи Apple внутри себя.
 */
@Slf4j
@Component
public class NimbusAppleTokenVerifier implements AppleTokenVerifier {

    private final JwtDecoder decoder;

    public NimbusAppleTokenVerifier(AuthProperties authProperties) {
        AuthProperties.Apple cfg = authProperties.getApple();
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(cfg.getJwksUri()).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(cfg.getIssuer()),
                new AudienceValidator(List.of(cfg.getBundleId()))
        );
        nimbus.setJwtValidator(validator);
        this.decoder = nimbus;
    }

    @Override
    public ExternalIdentity verify(String identityToken) {
        Jwt jwt;
        try {
            jwt = decoder.decode(identityToken);
        } catch (JwtException e) {
            throw AuthTokenException.invalid("Apple identity token is invalid", e);
        }
        String subject = jwt.getClaimAsString(JwtClaimNames.SUB);
        if (subject == null) {
            throw AuthTokenException.invalid("Apple token has no subject");
        }
        String email = jwt.getClaimAsString("email");
        // Apple отдаёт email_verified как строку "true"/"false" или boolean.
        boolean emailVerified = parseBooleanClaim(jwt, "email_verified");
        return new ExternalIdentity(subject, email, emailVerified);
    }

    static boolean parseBooleanClaim(Jwt jwt, String name) {
        Object raw = jwt.getClaim(name);
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && "true".equalsIgnoreCase(String.valueOf(raw));
    }
}
