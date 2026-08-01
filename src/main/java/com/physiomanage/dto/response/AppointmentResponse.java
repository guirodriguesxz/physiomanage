package com.physiomanage.dto.response;

import com.physiomanage.entity.Appointment;
import com.physiomanage.entity.AppointmentStatus;

import java.time.Instant;
import java.util.UUID;

public record AppointmentResponse(
        UUID id,
        UUID patientId,
        String patientName,
        UUID professionalId,
        String professionalName,
        Instant scheduledAt,
        Integer durationMinutes,
        AppointmentStatus status,
        String notes
) {
    public static AppointmentResponse from(Appointment a) {
        return new AppointmentResponse(
                a.getId(),
                a.getPatient().getId(), a.getPatient().getName(),
                a.getProfessional().getId(), a.getProfessional().getName(),
                a.getScheduledAt(), a.getDurationMinutes(), a.getStatus(), a.getNotes()
        );
    }
}
