package com.physiomanage.exception;

import org.springframework.http.HttpStatus;

public class ScheduleConflictException extends BusinessException {
    public ScheduleConflictException() {
        super("O profissional já possui uma consulta agendada nesse horário", HttpStatus.CONFLICT);
    }
}
