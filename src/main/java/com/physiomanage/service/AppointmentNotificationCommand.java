package com.physiomanage.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope que atravessa a fronteira da thread @Async — carrega dados já
 * lidos das entidades (não as entidades em si), porque proxies lazy do
 * Hibernate presos à transação/thread de origem não podem ser acessados
 * com segurança pela thread do executor assíncrono.
 */
public record AppointmentNotificationCommand(
        UUID clinicId,
        UUID appointmentId,
        String patientEmail,
        String professionalName,
        Instant scheduledAt
) {
}
