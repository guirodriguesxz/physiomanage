package com.physiomanage.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Concentra o acesso ao cache Redis de disponibilidade: formato da chave
 * (clinicId:professionalId:date) e leitura/escrita/invalidação, usado por
 * AvailabilityService (que lê/escreve) e AppointmentService (que invalida
 * quando uma consulta muda o que está ocupado).
 *
 * Acesso manual em vez de @Cacheable/@CacheEvict porque o
 * AppointmentService.reschedule precisa invalidar a chave da data ANTIGA
 * da consulta, que só é conhecida depois de carregar a entidade do banco
 * — SpEL de anotação só enxerga os parâmetros do método, não valores
 * carregados no meio da execução.
 *
 * Toda operação é "fail-open": se o Redis estiver fora do ar, loga e
 * segue em frente. Cache é otimização, não pode derrubar o agendamento.
 */
@Component
public class AvailabilityCache {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityCache.class);
    private static final String CACHE_NAME = "availability";

    private final CacheManager cacheManager;

    public AvailabilityCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public Optional<List<Instant>> get(UUID clinicId, UUID professionalId, LocalDate date) {
        try {
            Cache cache = cache();
            if (cache == null) {
                return Optional.empty();
            }
            CachedAvailability cached = cache.get(key(clinicId, professionalId, date), CachedAvailability.class);
            return cached == null ? Optional.empty() : Optional.of(cached.slots());
        } catch (RuntimeException e) {
            log.warn("Falha ao ler cache de disponibilidade, recalculando", e);
            return Optional.empty();
        }
    }

    public void put(UUID clinicId, UUID professionalId, LocalDate date, List<Instant> slots) {
        try {
            Cache cache = cache();
            if (cache != null) {
                cache.put(key(clinicId, professionalId, date), new CachedAvailability(slots));
            }
        } catch (RuntimeException e) {
            log.warn("Falha ao gravar cache de disponibilidade", e);
        }
    }

    public void evict(UUID clinicId, UUID professionalId, LocalDate date) {
        try {
            Cache cache = cache();
            if (cache != null) {
                cache.evict(key(clinicId, professionalId, date));
            }
        } catch (RuntimeException e) {
            log.warn("Falha ao invalidar cache de disponibilidade", e);
        }
    }

    private Cache cache() {
        return cacheManager.getCache(CACHE_NAME);
    }

    private String key(UUID clinicId, UUID professionalId, LocalDate date) {
        return clinicId + ":" + professionalId + ":" + date;
    }
}
