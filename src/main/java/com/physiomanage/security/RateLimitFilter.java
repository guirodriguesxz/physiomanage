package com.physiomanage.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Limita tentativas de POST /auth/login e /auth/register-clinic por IP —
 * mitigação de brute-force de senha e de spam de criação de tenant. Roda
 * antes do JwtAuthenticationFilter (ver SecurityConfig) porque essas
 * rotas nem usam JWT.
 *
 * Limita só por IP, não por CNPJ/e-mail do corpo da requisição — ler e
 * reempacotar o corpo antes do controller processar exigiria um
 * ContentCachingRequestWrapper, complexidade que não se paga aqui; IP já
 * é a estratégia padrão da maioria dos rate limiters de borda.
 *
 * Fail-open: se o Redis estiver fora do ar, loga e deixa passar. Rate
 * limit é defesa extra, não a única (senha já é BCrypt) — não pode
 * derrubar login/cadastro sozinho. Contraste com RefreshTokenService,
 * que é fail-closed porque é a única garantia de revogação que existe.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final int loginMaxAttempts;
    private final int loginWindowSeconds;
    private final int registerMaxAttempts;
    private final int registerWindowSeconds;

    public RateLimitFilter(
            RateLimiter rateLimiter,
            ObjectMapper objectMapper,
            @Value("${app.rate-limit.login.max-attempts}") int loginMaxAttempts,
            @Value("${app.rate-limit.login.window-seconds}") int loginWindowSeconds,
            @Value("${app.rate-limit.register-clinic.max-attempts}") int registerMaxAttempts,
            @Value("${app.rate-limit.register-clinic.window-seconds}") int registerWindowSeconds
    ) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.loginMaxAttempts = loginMaxAttempts;
        this.loginWindowSeconds = loginWindowSeconds;
        this.registerMaxAttempts = registerMaxAttempts;
        this.registerWindowSeconds = registerWindowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return limitFor(request) == null;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        Limit limit = limitFor(request);
        String key = "ratelimit:" + limit.bucket() + ":" + clientIp(request);

        boolean allowed;
        try {
            allowed = rateLimiter.tryConsume(key, limit.maxAttempts(), limit.windowSeconds());
        } catch (RuntimeException e) {
            log.warn("Falha ao checar rate limit, permitindo requisição", e);
            allowed = true;
        }

        if (!allowed) {
            writeTooManyRequests(response, limit.windowSeconds());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Limit limitFor(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        return switch (request.getRequestURI()) {
            case "/api/v1/auth/login" -> new Limit("login", loginMaxAttempts, loginWindowSeconds);
            case "/api/v1/auth/register-clinic" -> new Limit("register-clinic", registerMaxAttempts, registerWindowSeconds);
            default -> null;
        };
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response, int windowSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(windowSeconds));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        body.put("message", "Muitas tentativas, tente novamente em instantes");

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private record Limit(String bucket, int maxAttempts, int windowSeconds) {}
}
