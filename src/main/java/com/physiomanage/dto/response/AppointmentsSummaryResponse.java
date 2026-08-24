package com.physiomanage.dto.response;

import com.physiomanage.entity.AppointmentStatus;

import java.time.LocalDate;
import java.util.Map;

public record AppointmentsSummaryResponse(
        LocalDate from,
        LocalDate to,
        long total,
        Map<AppointmentStatus, Long> byStatus,
        double noShowRate,
        double cancellationRate
) {
}
