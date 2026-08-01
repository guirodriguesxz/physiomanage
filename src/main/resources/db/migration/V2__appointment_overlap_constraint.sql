-- V2: impede duplo-agendamento em nível de banco. A checagem de conflito
-- em AppointmentService (findOverlapping) é um SELECT seguido de INSERT
-- sem lock — duas requisições concorrentes para o mesmo profissional
-- podem passar pela checagem antes de qualquer uma persistir. Esta
-- constraint é a rede de segurança que garante a regra mesmo sob
-- concorrência, independente da aplicação.

CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE appointments
    ADD COLUMN time_range tsrange GENERATED ALWAYS AS (
        tsrange(scheduled_at, scheduled_at + make_interval(mins => duration_minutes), '[)')
    ) STORED;

ALTER TABLE appointments
    ADD CONSTRAINT no_overlapping_appointments
    EXCLUDE USING gist (
        professional_id WITH =,
        time_range WITH &&
    ) WHERE (status NOT IN ('CANCELLED', 'NO_SHOW'));
