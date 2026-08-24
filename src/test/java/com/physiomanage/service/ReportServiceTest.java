package com.physiomanage.service;

import com.physiomanage.dto.response.AppointmentsSummaryResponse;
import com.physiomanage.dto.response.ProfessionalProductivityResponse;
import com.physiomanage.entity.AppointmentStatus;
import com.physiomanage.exception.InvalidDateRangeException;
import com.physiomanage.repository.AppointmentRepository;
import com.physiomanage.security.ClinicContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unitário (Mockito) da agregação da Fase 7 — as queries JPQL em si (GROUP
 * BY, SUM/CASE WHEN, ORDER BY alias) já foram validadas contra Postgres
 * real; aqui cobrimos o preenchimento de status zerados, o cálculo de taxas
 * e a validação de intervalo, que é lógica pura do service.
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    private ReportService reportService;

    private final UUID clinicId = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 8, 1);
    private final LocalDate to = LocalDate.of(2026, 8, 31);

    @BeforeEach
    void setUp() {
        reportService = new ReportService(appointmentRepository);
        ClinicContext.set(clinicId, UUID.randomUUID(), "ADMIN");
    }

    @AfterEach
    void tearDown() {
        ClinicContext.clear();
    }

    @Test
    void summary_shouldFillMissingStatusesWithZero_andComputeRates() {
        // Cada statusCount() chama when(...) por baixo dos panos — precisa
        // terminar de resolver essas chamadas ANTES de iniciar o
        // when(...).thenReturn(...) de fora, senão o Mockito acusa
        // "unfinished stubbing" (when() aninhado dentro de um when() em
        // andamento).
        List<AppointmentRepository.StatusCount> rows = List.of(
                statusCount(AppointmentStatus.COMPLETED, 6),
                statusCount(AppointmentStatus.NO_SHOW, 2),
                statusCount(AppointmentStatus.CANCELLED, 2)
        );
        when(appointmentRepository.countByStatusInRange(any(), any(), any())).thenReturn(rows);

        AppointmentsSummaryResponse summary = reportService.summary(from, to);

        assertEquals(10, summary.total());
        assertEquals(6L, summary.byStatus().get(AppointmentStatus.COMPLETED));
        assertEquals(0L, summary.byStatus().get(AppointmentStatus.SCHEDULED));
        assertEquals(0L, summary.byStatus().get(AppointmentStatus.CONFIRMED));
        assertEquals(0.2, summary.noShowRate());
        assertEquals(0.2, summary.cancellationRate());
    }

    @Test
    void summary_shouldReturnZeroRates_whenNoAppointmentsInRange() {
        when(appointmentRepository.countByStatusInRange(any(), any(), any())).thenReturn(List.of());

        AppointmentsSummaryResponse summary = reportService.summary(from, to);

        assertEquals(0, summary.total());
        assertEquals(0.0, summary.noShowRate());
        assertEquals(0.0, summary.cancellationRate());
    }

    @Test
    void summary_shouldThrowInvalidDateRange_whenFromIsAfterTo() {
        assertThrows(InvalidDateRangeException.class, () -> reportService.summary(to, from));
    }

    @Test
    void professionalsProductivity_shouldMapProjectionsToResponseDtos() {
        UUID professionalId = UUID.randomUUID();
        AppointmentRepository.ProfessionalProductivity projection = mock(AppointmentRepository.ProfessionalProductivity.class);
        when(projection.getProfessionalId()).thenReturn(professionalId);
        when(projection.getProfessionalName()).thenReturn("Dra. Ana Lima");
        when(projection.getCompleted()).thenReturn(5L);
        when(projection.getCancelled()).thenReturn(1L);
        when(projection.getNoShow()).thenReturn(0L);
        when(projection.getTotal()).thenReturn(6L);
        when(appointmentRepository.productivityByProfessional(any(), any(), any())).thenReturn(List.of(projection));

        List<ProfessionalProductivityResponse> result = reportService.professionalsProductivity(from, to);

        assertEquals(1, result.size());
        ProfessionalProductivityResponse dto = result.get(0);
        assertEquals(professionalId, dto.professionalId());
        assertEquals("Dra. Ana Lima", dto.professionalName());
        assertEquals(5L, dto.completed());
        assertEquals(6L, dto.total());
    }

    @Test
    void professionalsProductivity_shouldThrowInvalidDateRange_whenFromIsAfterTo() {
        assertThrows(InvalidDateRangeException.class, () -> reportService.professionalsProductivity(to, from));
    }

    private AppointmentRepository.StatusCount statusCount(AppointmentStatus status, long count) {
        AppointmentRepository.StatusCount row = mock(AppointmentRepository.StatusCount.class);
        when(row.getStatus()).thenReturn(status);
        when(row.getCount()).thenReturn(count);
        return row;
    }
}
