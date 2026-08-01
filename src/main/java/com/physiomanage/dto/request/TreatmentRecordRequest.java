package com.physiomanage.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TreatmentRecordRequest(

        @NotNull(message = "Consulta é obrigatória")
        UUID appointmentId,

        @NotBlank(message = "Evolução é obrigatória")
        @Size(max = 4000)
        String evolution
) {
}
