package com.physiomanage.dto.response;

import java.util.UUID;

public record AuthResponse(
        String token,
        String tokenType,
        String refreshToken,
        UUID userId,
        UUID clinicId,
        String role
) {
    public static AuthResponse of(String token, String refreshToken, UUID userId, UUID clinicId, String role) {
        return new AuthResponse(token, "Bearer", refreshToken, userId, clinicId, role);
    }
}
