package com.physiomanage.exception;

import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends BusinessException {
    public InvalidCredentialsException() {
        super("E-mail, senha ou clínica inválidos", HttpStatus.UNAUTHORIZED);
    }
}
