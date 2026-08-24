package com.physiomanage.security;

import com.physiomanage.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;

/**
 * Responsável por emitir e validar JWTs. O token carrega claims customizadas
 * (clinicId, role) para que os filtros de autorização e os services não
 * precisem consultar o banco novamente em toda requisição só para saber
 * "de qual clínica é esse usuário" — informação crítica para o isolamento
 * multi-tenant.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(user.getEmail())
                .claims(Map.of(
                        "userId", user.getId().toString(),
                        "clinicId", user.getClinic().getId().toString(),
                        "role", user.getRole().name()
                ))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractClinicId(String token) {
        return extractAllClaims(token).get("clinicId", String.class);
    }

    public String extractUserId(String token) {
        return extractAllClaims(token).get("userId", String.class);
    }

    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    /**
     * O parser do jjwt já valida a claim `exp` durante o parseSignedClaims
     * (chamado por extractEmail) e lança ExpiredJwtException pra token
     * vencido — então esse try/catch é o que garante que este método
     * cumpre seu próprio contrato de `boolean` que nunca lança, em vez de
     * depender de o único chamador hoje (JwtAuthenticationFilter) também
     * capturar JwtException por fora.
     */
    public boolean isTokenValid(String token, String expectedEmail) {
        try {
            return extractEmail(token).equals(expectedEmail);
        } catch (JwtException e) {
            return false;
        }
    }

    private <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
