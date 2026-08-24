package com.physiomanage.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Primeiro filtro da cadeia (ver SecurityConfig): gera (ou aceita, se já
 * veio de um proxy/gateway upstream) um ID de correlação por requisição e
 * põe no MDC, pra todo log dessa requisição — em qualquer classe — poder
 * ser filtrado/agrupado nos logs (LogstashEncoder inclui MDC automático
 * no JSON, ver logback-spring.xml). Devolvido também no header de
 * resposta, pra debugar um caso específico reportado pelo cliente.
 *
 * clinicId/userId/role entram no MDC à parte, em JwtAuthenticationFilter
 * (só ficam conhecidos depois que o JWT é validado).
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String requestId = resolveRequestId(request);
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        return (incoming != null && !incoming.isBlank()) ? incoming : UUID.randomUUID().toString();
    }
}
