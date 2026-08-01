package com.physiomanage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Clinic é o "tenant" do sistema: cada clínica cadastrada é isolada das
 * demais. Toda entidade de negócio (User, Patient, Professional,
 * Appointment...) referencia uma Clinic, e o isolamento é reforçado a
 * nível de aplicação (ver ClinicContext / filtros de repositório).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "clinics")
public class Clinic extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String cnpj;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false)
    private boolean active = true;
}
