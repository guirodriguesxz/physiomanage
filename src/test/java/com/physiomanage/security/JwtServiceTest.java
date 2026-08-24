package com.physiomanage.security;

import com.physiomanage.entity.Clinic;
import com.physiomanage.entity.Role;
import com.physiomanage.entity.User;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unitário de emissão/validação de JWT — sem Spring context, só a
 * lib jjwt. Uso da API stateless via HTTP continua coberto por
 * JwtAuthenticationFilter em cada teste de integração.
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-key-for-jwt-unit-tests-must-be-at-least-256-bits-long";

    @Test
    void generateToken_shouldRoundTripAllClaims() {
        JwtService jwtService = new JwtService(SECRET, 60_000);
        User user = userWith(Role.ADMIN);

        String token = jwtService.generateToken(user);

        assertEquals(user.getEmail(), jwtService.extractEmail(token));
        assertEquals(user.getId().toString(), jwtService.extractUserId(token));
        assertEquals(user.getClinic().getId().toString(), jwtService.extractClinicId(token));
        assertEquals("ADMIN", jwtService.extractRole(token));
    }

    @Test
    void isTokenValid_shouldReturnTrue_forFreshTokenWithMatchingEmail() {
        JwtService jwtService = new JwtService(SECRET, 60_000);
        User user = userWith(Role.PROFESSIONAL);

        String token = jwtService.generateToken(user);

        assertTrue(jwtService.isTokenValid(token, user.getEmail()));
    }

    @Test
    void isTokenValid_shouldReturnFalse_whenEmailDoesNotMatch() {
        JwtService jwtService = new JwtService(SECRET, 60_000);
        User user = userWith(Role.PROFESSIONAL);

        String token = jwtService.generateToken(user);

        assertFalse(jwtService.isTokenValid(token, "outra-pessoa@example.com"));
    }

    @Test
    void isTokenValid_shouldReturnFalse_whenTokenAlreadyExpired() {
        // expirationMs negativo -> expiry fica no passado assim que o
        // token é gerado, sem depender de Thread.sleep pra ficar
        // determinístico. Exercita o try/catch de isTokenValid: o parser
        // do jjwt lança ExpiredJwtException durante o parse (não dá pra
        // checar "expirou?" depois de parsear com sucesso), então sem
        // aquele catch esse assertFalse veria uma exceção não tratada.
        JwtService jwtService = new JwtService(SECRET, -1_000);
        User user = userWith(Role.ADMIN);

        String token = jwtService.generateToken(user);

        assertFalse(jwtService.isTokenValid(token, user.getEmail()));
    }

    private User userWith(Role role) {
        Clinic clinic = new Clinic();
        clinic.setId(UUID.randomUUID());

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setClinic(clinic);
        user.setEmail("usuario@example.com");
        user.setRole(role);
        return user;
    }
}
