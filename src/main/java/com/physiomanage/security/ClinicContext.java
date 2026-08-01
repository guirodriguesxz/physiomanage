package com.physiomanage.security;

import java.util.UUID;

/**
 * Guarda o clinicId (e outros dados do JWT) do usuário autenticado na
 * requisição atual, usando ThreadLocal. É a peça central do isolamento
 * multi-tenant: todo service de negócio consulta ClinicContext.getClinicId()
 * em vez de confiar em um clinicId vindo do corpo da requisição — assim,
 * um usuário da Clínica A nunca consegue, mesmo manipulando o payload,
 * acessar ou alterar dados da Clínica B.
 *
 * Populado pelo JwtAuthenticationFilter a cada requisição e limpo ao final
 * dela (evita vazamento entre threads reaproveitadas pelo servlet container).
 */
public class ClinicContext {

    private static final ThreadLocal<UUID> CURRENT_CLINIC = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_ROLE = new ThreadLocal<>();
    private static final ThreadLocal<UUID> CURRENT_USER = new ThreadLocal<>();

    private ClinicContext() {
    }

    public static void set(UUID clinicId, UUID userId, String role) {
        CURRENT_CLINIC.set(clinicId);
        CURRENT_USER.set(userId);
        CURRENT_ROLE.set(role);
    }

    public static UUID getClinicId() {
        UUID clinicId = CURRENT_CLINIC.get();
        if (clinicId == null) {
            throw new IllegalStateException("ClinicContext não populado - requisição sem autenticação válida");
        }
        return clinicId;
    }

    public static UUID getUserId() {
        return CURRENT_USER.get();
    }

    public static String getRole() {
        return CURRENT_ROLE.get();
    }

    public static void clear() {
        CURRENT_CLINIC.remove();
        CURRENT_USER.remove();
        CURRENT_ROLE.remove();
    }
}
