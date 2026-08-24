package com.physiomanage.security;

import com.physiomanage.exception.InvalidRefreshTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Emite, valida e revoga refresh tokens opacos (não-JWT) no Redis. Só o
 * hash SHA-256 do token é armazenado — nunca o valor bruto — pelo mesmo
 * motivo de senha ir com BCrypt: um dump/leitura do Redis não deve
 * entregar tokens válidos.
 *
 * Diferente do AvailabilityCache (fail-open), aqui a operação é
 * fail-closed: se o Redis estiver fora do ar, emitir/validar/revogar
 * token deve falhar alto (500) em vez de fingir sucesso — é a única
 * forma real de logout/revogação que o sistema tem, então "seguir em
 * frente sem persistir" quebraria a garantia de segurança.
 *
 * Rotação a cada uso: validateAndConsume() sempre invalida o token lido,
 * mesmo quando válido — refresh emite um novo. Isso limita o dano de um
 * refresh token vazado a uma única troca (e permite detectar reuso: um
 * token consumido que aparece de novo é sinal de token comprometido).
 */
@Service
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RefreshTokenService(
            StringRedisTemplate redisTemplate,
            @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs
    ) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMillis(refreshExpirationMs);
    }

    public String issue(UUID userId) {
        String rawToken = generateRawToken();
        redisTemplate.opsForValue().set(KEY_PREFIX + hash(rawToken), userId.toString(), ttl);
        return rawToken;
    }

    public UUID validateAndConsume(String rawToken) {
        String key = KEY_PREFIX + hash(rawToken);
        String userId = redisTemplate.opsForValue().getAndDelete(key);
        return Optional.ofNullable(userId)
                .map(UUID::fromString)
                .orElseThrow(InvalidRefreshTokenException::new);
    }

    public void revoke(String rawToken) {
        redisTemplate.delete(KEY_PREFIX + hash(rawToken));
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }
}
