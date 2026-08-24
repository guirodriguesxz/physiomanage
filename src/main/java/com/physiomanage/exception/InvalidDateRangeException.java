package com.physiomanage.exception;

import org.springframework.http.HttpStatus;

public class InvalidDateRangeException extends BusinessException {
    public InvalidDateRangeException() {
        super("Data inicial não pode ser depois da data final", HttpStatus.BAD_REQUEST);
    }
}
