-- V3: prontuário/evolução clínica. Um TreatmentRecord é 1:1 com um
-- Appointment (UNIQUE em appointment_id) — só existe evolução clínica
-- para uma consulta que de fato aconteceu.
-- patient_id/professional_id são denormalizados a partir do appointment
-- no momento da criação, mesmo raciocínio do clinic_id em appointments:
-- evita joins extras nas listagens por paciente.

CREATE TABLE treatment_records (
    id              UUID PRIMARY KEY,
    clinic_id       UUID NOT NULL REFERENCES clinics(id),
    appointment_id  UUID NOT NULL UNIQUE REFERENCES appointments(id),
    patient_id      UUID NOT NULL REFERENCES patients(id),
    professional_id UUID NOT NULL REFERENCES professionals(id),
    evolution       TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL
);

CREATE INDEX idx_treatment_records_clinic ON treatment_records(clinic_id);
CREATE INDEX idx_treatment_records_patient ON treatment_records(patient_id);
