package com.physiomanage.exception;

import org.springframework.http.HttpStatus;

public class AppointmentNotCompletedException extends BusinessException {
    public AppointmentNotCompletedException() {
        super("Só é possível registrar evolução para consultas concluídas", HttpStatus.CONFLICT);
    }
}
