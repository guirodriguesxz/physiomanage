package com.physiomanage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Patient representa o paciente da clínica. O vínculo com User é opcional
 * (nullable) porque nem todo paciente necessariamente terá login no
 * sistema — a recepção pode cadastrar o paciente sem que ele acesse a
 * plataforma, e um portal de paciente pode ser habilitado depois
 * (associando um User a esse Patient).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "patients", uniqueConstraints = @UniqueConstraint(columnNames = {"clinic_id", "cpf"}))
public class Patient extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clinic_id", nullable = false)
    private Clinic clinic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 14)
    private String cpf;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(length = 20)
    private String phone;

    @Column(length = 150)
    private String email;

    @Column(name = "insurance_name", length = 100)
    private String insuranceName;

    @Column(name = "insurance_number", length = 50)
    private String insuranceNumber;

    @Column(nullable = false)
    private boolean active = true;
}
