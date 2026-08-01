package com.physiomanage.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Filtro executado em toda requisição: extrai o Bearer token, valida e,
 * se válido, popula tanto o SecurityContext do Spring (para @PreAuthorize)
 * quanto o ClinicContext (para isolamento multi-tenant nos services).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = authHeader.substring(7);

            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                String email = jwtService.extractEmail(token);

                if (email != null && jwtService.isTokenValid(token, email)) {
                    String role = jwtService.extractRole(token);
                    UUID clinicId = UUID.fromString(jwtService.extractClinicId(token));
                    UUID userId = UUID.fromString(jwtService.extractUserId(token));

                    var authToken = new UsernamePasswordAuthenticationToken(
                            email,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    ClinicContext.set(clinicId, userId, role);
                }
            }
        } catch (JwtException | IllegalArgumentException e) {
            // Token malformado/expirado/adulterado: segue sem autenticar em
            // vez de deixar a exceção escapar do filtro (o que antes virava
            // 500 do handler genérico em vez de 401/403 da regra de
            // autorização do Spring Security).
            log.debug("Token JWT inválido: {}", e.getMessage());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Garante que o ThreadLocal não vaze para a próxima requisição
            // que reaproveite essa mesma thread do pool do servlet container.
            ClinicContext.clear();
        }
    }
}
