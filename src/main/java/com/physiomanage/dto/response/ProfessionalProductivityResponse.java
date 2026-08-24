package com.physiomanage.dto.response;

import com.physiomanage.repository.AppointmentRepository;

import java.util.UUID;

public record ProfessionalProductivityResponse(
        UUID professionalId,
        String professionalName,
        long completed,
        long cancelled,
        long noShow,
        long total
) {
    public static ProfessionalProductivityResponse from(AppointmentRepository.ProfessionalProductivity projection) {
        return new ProfessionalProductivityResponse(
                projection.getProfessionalId(),
                projection.getProfessionalName(),
                projection.getCompleted(),
                projection.getCancelled(),
                projection.getNoShow(),
                projection.getTotal()
        );
    }
}
