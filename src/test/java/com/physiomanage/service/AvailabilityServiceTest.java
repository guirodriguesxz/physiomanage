package com.physiomanage.service;

import com.physiomanage.entity.Appointment;
import com.physiomanage.entity.Professional;
import com.physiomanage.exception.ResourceNotFoundException;
import com.physiomanage.repository.AppointmentRepository;
import com.physiomanage.security.ClinicContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unitário (Mockito) da matemática de slots da Fase 4 — geração a partir
 * do horário fixo de funcionamento menos consultas ocupadas, e o
 * curto-circuito de cache hit. Comportamento do Redis de verdade (TTL,
 * invalidação em Appointment) continua só em AvailabilityIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    private static final int WORK_START_HOUR = 8;
    private static final int WORK_END_HOUR = 18;
    private static final int SLOT_MINUTES = 50;

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private ProfessionalService professionalService;
    @Mock
    private AvailabilityCache availabilityCache;

    private AvailabilityService availabilityService;

    private final UUID clinicId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 8, 25);

    @BeforeEach
    void setUp() {
        availabilityService = new AvailabilityService(
                appointmentRepository, professionalService, availabilityCache,
                WORK_START_HOUR, WORK_END_HOUR, SLOT_MINUTES
        );
        ClinicContext.set(clinicId, UUID.randomUUID(), "ADMIN");
    }

    @AfterEach
    void tearDown() {
        ClinicContext.clear();
    }

    @Test
    void shouldReturnAllTwelveSlots_whenNoBookingsAndCacheMiss() {
        when(professionalService.getById(professionalId)).thenReturn(activeProfessional());
        when(availabilityCache.get(any(), eq(professionalId), eq(date))).thenReturn(Optional.empty());
        when(appointmentRepository.findOverlapping(eq(professionalId), any(), any())).thenReturn(List.of());

        List<Instant> slots = availabilityService.getAvailability(professionalId, date);

        assertEquals(12, slots.size());
        assertEquals(slotStart(8, 0), slots.get(0));
        assertEquals(slotStart(17, 10), slots.get(slots.size() - 1));
        verify(availabilityCache).put(any(), eq(professionalId), eq(date), eq(slots));
    }

    @Test
    void shouldReturnCachedSlots_withoutTouchingRepositoryOrCachePut() {
        List<Instant> cached = List.of(slotStart(9, 0));
        when(professionalService.getById(professionalId)).thenReturn(activeProfessional());
        when(availabilityCache.get(any(), eq(professionalId), eq(date))).thenReturn(Optional.of(cached));

        List<Instant> slots = availabilityService.getAvailability(professionalId, date);

        assertEquals(cached, slots);
        verify(appointmentRepository, never()).findOverlapping(any(), any(), any());
        verify(availabilityCache, never()).put(any(), any(), any(), any());
    }

    @Test
    void shouldExcludeOnlyTheSlotThatOverlapsABooking() {
        // A grade de slots anda de 50 em 50min a partir de 08:00 — não
        // bate com horas "redondas" (08:00, 08:50, 09:40, ...). A consulta
        // ocupada precisa estar alinhada a um slot de verdade (nthSlot(1)
        // = 08:50-09:40), senão ela sobrepõe dois slots em vez de um.
        Appointment busy = new Appointment();
        busy.setScheduledAt(nthSlot(1));
        busy.setDurationMinutes(SLOT_MINUTES);

        when(professionalService.getById(professionalId)).thenReturn(activeProfessional());
        when(availabilityCache.get(any(), eq(professionalId), eq(date))).thenReturn(Optional.empty());
        when(appointmentRepository.findOverlapping(eq(professionalId), any(), any())).thenReturn(List.of(busy));

        List<Instant> slots = availabilityService.getAvailability(professionalId, date);

        assertEquals(11, slots.size());
        assertFalse(slots.contains(nthSlot(1)), "slot ocupado não deveria aparecer como livre");
        assertTrue(slots.contains(nthSlot(0)), "slot anterior ao ocupado deve continuar livre");
        assertTrue(slots.contains(nthSlot(2)), "slot seguinte ao ocupado deve continuar livre");
    }

    @Test
    void shouldThrowResourceNotFound_whenProfessionalIsInactive() {
        Professional inactive = activeProfessional();
        inactive.setActive(false);
        when(professionalService.getById(professionalId)).thenReturn(inactive);

        assertThrows(ResourceNotFoundException.class,
                () -> availabilityService.getAvailability(professionalId, date));
    }

    @Test
    void constructor_shouldRejectNonPositiveSlotMinutes() {
        assertThrows(IllegalStateException.class,
                () -> new AvailabilityService(appointmentRepository, professionalService, availabilityCache, 8, 18, 0));
        assertThrows(IllegalStateException.class,
                () -> new AvailabilityService(appointmentRepository, professionalService, availabilityCache, 8, 18, -10));
    }

    private Professional activeProfessional() {
        Professional professional = new Professional();
        professional.setId(professionalId);
        professional.setActive(true);
        return professional;
    }

    private Instant slotStart(int hour, int minute) {
        return date.atTime(hour, minute).atZone(ZoneOffset.UTC).toInstant();
    }

    /** Início do slot de índice N na grade real (08:00 + N * SLOT_MINUTES). */
    private Instant nthSlot(int index) {
        return slotStart(WORK_START_HOUR, 0).plus(Duration.ofMinutes((long) index * SLOT_MINUTES));
    }
}
