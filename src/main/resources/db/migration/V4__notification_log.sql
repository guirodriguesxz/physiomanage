-- V4: registro do envio (simulado) de notificações assíncronas de
-- consulta. Alimentado pelo NotificationService via @Async, desacoplado
-- do tempo de resposta de POST /appointments.

CREATE TABLE notification_log (
    id             UUID PRIMARY KEY,
    clinic_id      UUID NOT NULL REFERENCES clinics(id),
    appointment_id UUID NOT NULL REFERENCES appointments(id),
    recipient      VARCHAR(150),
    message        VARCHAR(500) NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMP NOT NULL,
    updated_at     TIMESTAMP NOT NULL
);

CREATE INDEX idx_notification_log_clinic ON notification_log(clinic_id);
CREATE INDEX idx_notification_log_appointment ON notification_log(appointment_id);
