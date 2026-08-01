package com.physiomanage.entity;

/**
 * Papéis de usuário dentro de uma clínica.
 * ADMIN        - dono/gestor da clínica, acesso total ao tenant
 * RECEPTION    - recepcionista, gerencia agenda e pacientes
 * PROFESSIONAL - fisioterapeuta, acessa sua agenda e prontuários
 * PATIENT      - paciente, acesso restrito aos próprios dados
 */
public enum Role {
    ADMIN,
    RECEPTION,
    PROFESSIONAL,
    PATIENT
}
