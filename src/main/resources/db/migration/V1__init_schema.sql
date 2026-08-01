-- V1: schema inicial do PhysioManage
-- Todas as tabelas de negócio carregam clinic_id para isolamento multi-tenant.

CREATE TABLE clinics (
    id          UUID PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    cnpj        VARCHAR(20)  NOT NULL UNIQUE,
    email       VARCHAR(150) NOT NULL UNIQUE,
    phone       VARCHAR(20),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

CREATE TABLE users (
    id             UUID PRIMARY KEY,
    clinic_id      UUID         NOT NULL REFERENCES clinics(id),
    name           VARCHAR(150) NOT NULL,
    email          VARCHAR(150) NOT NULL,
    password_hash  TEXT         NOT NULL,
    role           VARCHAR(20)  NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uq_users_clinic_email UNIQUE (clinic_id, email)
);

CREATE INDEX idx_users_clinic ON users(clinic_id);

CREATE TABLE patients (
    id                UUID PRIMARY KEY,
    clinic_id         UUID         NOT NULL REFERENCES clinics(id),
    user_id           UUID         REFERENCES users(id),
    name              VARCHAR(150) NOT NULL,
    cpf               VARCHAR(14)  NOT NULL,
    birth_date        DATE,
    phone             VARCHAR(20),
    email             VARCHAR(150),
    insurance_name    VARCHAR(100),
    insurance_number  VARCHAR(50),
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    CONSTRAINT uq_patients_clinic_cpf UNIQUE (clinic_id, cpf)
);

CREATE INDEX idx_patients_clinic ON patients(clinic_id);

CREATE TABLE professionals (
    id              UUID PRIMARY KEY,
    clinic_id       UUID         NOT NULL REFERENCES clinics(id),
    user_id         UUID         NOT NULL REFERENCES users(id),
    name            VARCHAR(150) NOT NULL,
    specialty       VARCHAR(100) NOT NULL,
    license_number  VARCHAR(30)  NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_professionals_clinic_license UNIQUE (clinic_id, license_number)
);

CREATE INDEX idx_professionals_clinic ON professionals(clinic_id);

CREATE TABLE appointments (
    id                 UUID PRIMARY KEY,
    clinic_id          UUID        NOT NULL REFERENCES clinics(id),
    patient_id         UUID        NOT NULL REFERENCES patients(id),
    professional_id    UUID        NOT NULL REFERENCES professionals(id),
    scheduled_at        TIMESTAMP   NOT NULL,
    duration_minutes    INTEGER     NOT NULL DEFAULT 50,
    status              VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    notes               VARCHAR(500),
    created_at          TIMESTAMP   NOT NULL,
    updated_at          TIMESTAMP   NOT NULL
);

CREATE INDEX idx_appointments_clinic_date ON appointments(clinic_id, scheduled_at);
CREATE INDEX idx_appointments_professional ON appointments(professional_id, scheduled_at);
CREATE INDEX idx_appointments_patient ON appointments(patient_id);
