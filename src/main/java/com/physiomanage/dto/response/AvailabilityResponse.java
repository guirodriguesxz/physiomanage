package com.physiomanage.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AvailabilityResponse(
        UUID professionalId,
        LocalDate date,
        List<Instant> availableSlots
) {
}
