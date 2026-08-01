package com.physiomanage.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TreatmentRecordUpdateRequest(

        @NotBlank(message = "Evolução é obrigatória")
        @Size(max = 4000)
        String evolution
) {
}
