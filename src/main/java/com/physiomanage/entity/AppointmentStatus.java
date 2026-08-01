package com.physiomanage.entity;

/**
 * Máquina de estados da consulta:
 * SCHEDULED -> CONFIRMED -> COMPLETED
 *                        -> CANCELLED
 *                        -> NO_SHOW
 * Transições inválidas (ex: COMPLETED -> SCHEDULED) devem ser bloqueadas
 * na camada de serviço (ver AppointmentService).
 */
public enum AppointmentStatus {
    SCHEDULED,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    NO_SHOW
}
