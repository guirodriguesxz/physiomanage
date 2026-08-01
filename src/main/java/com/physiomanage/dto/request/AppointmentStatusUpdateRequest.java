package com.physiomanage.dto.request;

import com.physiomanage.entity.AppointmentStatus;
import jakarta.validation.constraints.NotNull;

public record AppointmentStatusUpdateRequest(

        @NotNull(message = "Status é obrigatório")
        AppointmentStatus status
) {
}
