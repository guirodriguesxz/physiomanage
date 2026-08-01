package com.physiomanage.exception;

import org.springframework.http.HttpStatus;

/**
 * Exceção base para regras de negócio violadas (ex: CPF duplicado,
 * conflito de horário). Carrega o HttpStatus apropriado para que o
 * GlobalExceptionHandler traduza automaticamente para a resposta HTTP.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
