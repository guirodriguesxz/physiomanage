package com.physiomanage.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cria simultaneamente o User (login) e o Professional (dados
 * profissionais) — assim como no cadastro de clínica, é uma operação
 * conjunta porque um Professional sem User não teria como logar.
 */
public record ProfessionalRequest(

        @NotBlank
        String name,

        @NotBlank
        @Email
        String email,

        @NotBlank
        @Size(min = 8, message = "Senha deve ter no mínimo 8 caracteres")
        String password,

        @NotBlank
        String specialty,

        @NotBlank
        String licenseNumber
) {
}
