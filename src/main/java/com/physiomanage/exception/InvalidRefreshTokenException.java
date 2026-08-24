package com.physiomanage.exception;

import org.springframework.http.HttpStatus;

public class InvalidRefreshTokenException extends BusinessException {
    public InvalidRefreshTokenException() {
        super("Refresh token inválido, expirado ou já utilizado", HttpStatus.UNAUTHORIZED);
    }
}
