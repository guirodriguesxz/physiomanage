package com.physiomanage.dto.response;

import com.physiomanage.entity.Patient;

import java.time.LocalDate;
import java.util.UUID;

public record PatientResponse(
        UUID id,
        String name,
        String cpf,
        LocalDate birthDate,
        String phone,
        String email,
        String insuranceName,
        String insuranceNumber,
        boolean active
) {
    public static PatientResponse from(Patient p) {
        return new PatientResponse(
                p.getId(), p.getName(), p.getCpf(), p.getBirthDate(),
                p.getPhone(), p.getEmail(), p.getInsuranceName(),
                p.getInsuranceNumber(), p.isActive()
        );
    }
}
