package com.physiomanage.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Copia o MDC (requestId, clinicId, userId, role) da thread da requisição
 * para a thread do pool @Async. Sem isso, MDC é ThreadLocal e qualquer log
 * emitido dentro de NotificationService sairia sem a correlação da
 * requisição que o originou.
 *
 * O contexto anterior da thread do pool é restaurado no finally porque as
 * threads são reaproveitadas: sem isso, a próxima tarefa herdaria o MDC
 * de uma requisição que já terminou.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> requestContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(requestContext);
            try {
                runnable.run();
            } finally {
                setContext(previous);
            }
        };
    }

    private static void setContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
