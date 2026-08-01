package com.physiomanage.repository;

import com.physiomanage.entity.Professional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProfessionalRepository extends JpaRepository<Professional, UUID> {

    /**
     * Sobrescreve findById para trazer o User já inicializado: com
     * open-in-view=false, ProfessionalResponse.from acessa
     * professional.getUser().getEmail() fora da transação (no
     * controller), e User é ManyToOne LAZY.
     */
    @Override
    @EntityGraph(attributePaths = "user")
    Optional<Professional> findById(UUID id);

    @EntityGraph(attributePaths = "user")
    Page<Professional> findByClinicId(UUID clinicId, Pageable pageable);

    boolean existsByLicenseNumberAndClinicId(String licenseNumber, UUID clinicId);

    Optional<Professional> findByUserIdAndClinicId(UUID userId, UUID clinicId);
}
