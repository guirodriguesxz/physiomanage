package com.physiomanage.repository;

import com.physiomanage.entity.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    Page<Patient> findByClinicId(UUID clinicId, Pageable pageable);

    boolean existsByCpfAndClinicId(String cpf, UUID clinicId);
}
