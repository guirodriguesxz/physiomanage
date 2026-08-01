package com.physiomanage.dto.response;

import com.physiomanage.entity.NotificationLog;
import com.physiomanage.entity.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

public record NotificationLogResponse(
        UUID id,
        UUID appointmentId,
        String recipient,
        String message,
        NotificationStatus status,
        Instant createdAt
) {
    public static NotificationLogResponse from(NotificationLog n) {
        return new NotificationLogResponse(
                n.getId(), n.getAppointment().getId(), n.getRecipient(), n.getMessage(), n.getStatus(), n.getCreatedAt()
        );
    }
}
