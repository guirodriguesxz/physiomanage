package com.physiomanage.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload de cadastro inicial: cria a Clinic e o primeiro User (ADMIN)
 * em uma única operação transacional (ver AuthService.registerClinic).
 */
public record RegisterClinicRequest(

        @NotBlank(message = "Nome da clínica é obrigatório")
        @Size(max = 150)
        String clinicName,

        @NotBlank(message = "CNPJ é obrigatório")
        @Size(min = 14, max = 20)
        String cnpj,

        @NotBlank(message = "Nome do administrador é obrigatório")
        String adminName,

        @NotBlank
        @Email(message = "E-mail inválido")
        String adminEmail,

        @NotBlank
        @Size(min = 8, message = "Senha deve ter no mínimo 8 caracteres")
        String adminPassword
) {
}
