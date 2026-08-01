package com.physiomanage.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resource) {
        super(resource + " não encontrado(a)", HttpStatus.NOT_FOUND);
    }
}
