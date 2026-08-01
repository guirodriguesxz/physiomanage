package com.physiomanage.exception;

import com.physiomanage.entity.AppointmentStatus;
import org.springframework.http.HttpStatus;

public class InvalidStatusTransitionException extends BusinessException {
    public InvalidStatusTransitionException(AppointmentStatus from, AppointmentStatus to) {
        super("Não é possível mudar o status de " + from + " para " + to, HttpStatus.CONFLICT);
    }
}
