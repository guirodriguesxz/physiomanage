package com.physiomanage.service;

import com.physiomanage.dto.response.AppointmentsSummaryResponse;
import com.physiomanage.dto.response.ProfessionalProductivityResponse;
import com.physiomanage.entity.AppointmentStatus;
import com.physiomanage.exception.InvalidDateRangeException;
import com.physiomanage.repository.AppointmentRepository;
import com.physiomanage.security.ClinicContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Relatórios agregados da Fase 7 — só ADMIN acessa (ver ReportController).
 * `to` é inclusivo (intervalo real usado na query é [from, to+1dia), mesma
 * convenção de "dia inteiro" já usada em AvailabilityService).
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final AppointmentRepository appointmentRepository;

    @Transactional(readOnly = true)
    public AppointmentsSummaryResponse summary(LocalDate from, LocalDate to) {
        validateRange(from, to);
        UUID clinicId = ClinicContext.getClinicId();

        Map<AppointmentStatus, Long> byStatus = new EnumMap<>(AppointmentStatus.class);
        for (AppointmentStatus status : AppointmentStatus.values()) {
            byStatus.put(status, 0L);
        }
        for (AppointmentRepository.StatusCount row : appointmentRepository.countByStatusInRange(clinicId, startOf(from), endOf(to))) {
            byStatus.put(row.getStatus(), row.getCount());
        }

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        double noShowRate = rate(byStatus.get(AppointmentStatus.NO_SHOW), total);
        double cancellationRate = rate(byStatus.get(AppointmentStatus.CANCELLED), total);

        return new AppointmentsSummaryResponse(from, to, total, byStatus, noShowRate, cancellationRate);
    }

    @Transactional(readOnly = true)
    public List<ProfessionalProductivityResponse> professionalsProductivity(LocalDate from, LocalDate to) {
        validateRange(from, to);
        UUID clinicId = ClinicContext.getClinicId();

        return appointmentRepository.productivityByProfessional(clinicId, startOf(from), endOf(to)).stream()
                .map(ProfessionalProductivityResponse::from)
                .toList();
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidDateRangeException();
        }
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant endOf(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private double rate(long count, long total) {
        return total == 0 ? 0.0 : (double) count / total;
    }
}
