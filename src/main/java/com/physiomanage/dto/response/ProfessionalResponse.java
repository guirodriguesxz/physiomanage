package com.physiomanage.dto.response;

import com.physiomanage.entity.Professional;

import java.util.UUID;

public record ProfessionalResponse(
        UUID id,
        String name,
        String email,
        String specialty,
        String licenseNumber,
        boolean active
) {
    public static ProfessionalResponse from(Professional p) {
        return new ProfessionalResponse(
                p.getId(), p.getName(), p.getUser().getEmail(),
                p.getSpecialty(), p.getLicenseNumber(), p.isActive()
        );
    }
}
