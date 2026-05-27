package com.plantcare.api.auth.verifier;

import com.plantcare.api.config.AuthProperties;
import com.plantcare.api.auth.exception.AuthTokenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Верификация Google id-token по Google JWKS (issue #88). Помимо подписи и
 * стандартных проверок требует {@code iss} из допустимого списка,
 * {@code aud == clientId} и {@code email_verified = true}.
 */
@Slf4j
@Component
public class NimbusGoogleTokenVerifier implements GoogleTokenVerifier {

    private final JwtDecoder decoder;

    public NimbusGoogleTokenVerifier(AuthProperties authProperties) {
        AuthProperties.Google cfg = authProperties.getGoogle();
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(cfg.getJwksUri()).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new IssuerInListValidator(cfg.getIssuers()),
                new AudienceValidator(List.of(cfg.getClientId()))
        );
        nimbus.setJwtValidator(validator);
        this.decoder = nimbus;
    }

    @Override
    public ExternalIdentity verify(String idToken) {
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException e) {
            throw AuthTokenException.invalid("Google id token is invalid", e);
        }
        String subject = jwt.getClaimAsString(JwtClaimNames.SUB);
        if (subject == null) {
            throw AuthTokenException.invalid("Google token has no subject");
        }
        boolean emailVerified = NimbusAppleTokenVerifier.parseBooleanClaim(jwt, "email_verified");
        if (!emailVerified) {
            throw AuthTokenException.invalid("Google email is not verified");
        }
        String email = jwt.getClaimAsString("email");
        if (email == null) {
            throw AuthTokenException.invalid("Google token has no email");
        }
        return new ExternalIdentity(subject, email, true);
    }

    /** Принимает токены, у которых {@code iss} входит в заданный список. */
    static final class IssuerInListValidator implements OAuth2TokenValidator<Jwt> {
        private final List<String> allowedIssuers;

        IssuerInListValidator(List<String> allowedIssuers) {
            this.allowedIssuers = allowedIssuers;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            Object issuer = token.getClaim(JwtClaimNames.ISS);
            if (issuer != null && allowedIssuers.contains(String.valueOf(issuer))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Required issuer is missing or not allowed",
                    null));
        }
    }
}
