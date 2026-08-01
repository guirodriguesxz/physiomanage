package com.physiomanage.dto.response;

import com.physiomanage.entity.TreatmentRecord;

import java.time.Instant;
import java.util.UUID;

public record TreatmentRecordResponse(
        UUID id,
        UUID appointmentId,
        UUID patientId,
        String patientName,
        UUID professionalId,
        String professionalName,
        String evolution,
        Instant createdAt,
        Instant updatedAt
) {
    public static TreatmentRecordResponse from(TreatmentRecord r) {
        return new TreatmentRecordResponse(
                r.getId(),
                r.getAppointment().getId(),
                r.getPatient().getId(), r.getPatient().getName(),
                r.getProfessional().getId(), r.getProfessional().getName(),
                r.getEvolution(), r.getCreatedAt(), r.getUpdatedAt()
        );
    }
}
