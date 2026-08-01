package com.physiomanage.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record AppointmentRequest(

        @NotNull(message = "Paciente é obrigatório")
        UUID patientId,

        @NotNull(message = "Profissional é obrigatório")
        UUID professionalId,

        @NotNull(message = "Data/hora é obrigatória")
        @Future(message = "Data/hora deve ser no futuro")
        Instant scheduledAt,

        @Positive(message = "Duração deve ser maior que zero")
        Integer durationMinutes,

        @Size(max = 500)
        String notes
) {
}
