package com.physiomanage.dto.response;

import java.util.UUID;

public record AuthResponse(
        String token,
        String tokenType,
        UUID userId,
        UUID clinicId,
        String role
) {
    public static AuthResponse of(String token, UUID userId, UUID clinicId, String role) {
        return new AuthResponse(token, "Bearer", userId, clinicId, role);
    }
}
