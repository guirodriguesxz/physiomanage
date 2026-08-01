package com.physiomanage.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;

import java.time.LocalDate;

public record PatientRequest(

        @NotBlank(message = "Nome é obrigatório")
        String name,

        @NotBlank(message = "CPF é obrigatório")
        String cpf,

        @PastOrPresent(message = "Data de nascimento não pode ser futura")
        LocalDate birthDate,

        String phone,

        String email,

        String insuranceName,

        String insuranceNumber
) {
}
