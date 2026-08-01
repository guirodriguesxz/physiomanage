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

/**
 * Professional representa o fisioterapeuta. Diferente de Patient, aqui o
 * vínculo com User é obrigatório: todo profissional precisa logar no
 * sistema para gerenciar sua agenda e registrar prontuários.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "professionals", uniqueConstraints = @UniqueConstraint(columnNames = {"clinic_id", "license_number"}))
public class Professional extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clinic_id", nullable = false)
    private Clinic clinic;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 100)
    private String specialty;

    /** Registro no CREFITO (conselho de fisioterapia) */
    @Column(name = "license_number", nullable = false, length = 30)
    private String licenseNumber;

    @Column(nullable = false)
    private boolean active = true;
}
