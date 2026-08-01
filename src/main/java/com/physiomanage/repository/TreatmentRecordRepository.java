package com.physiomanage.repository;

import com.physiomanage.entity.TreatmentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TreatmentRecordRepository extends JpaRepository<TreatmentRecord, UUID> {

    /**
     * Sobrescreve findById para trazer patient/professional já
     * inicializados: com open-in-view=false, TreatmentRecordResponse é
     * montado no controller (fora da transação), e os dois são
     * ManyToOne LAZY — sem isso, dá LazyInitializationException.
     */
    @Override
    @EntityGraph(attributePaths = {"patient", "professional"})
    Optional<TreatmentRecord> findById(UUID id);

    @EntityGraph(attributePaths = {"patient", "professional"})
    Page<TreatmentRecord> findByClinicIdAndPatientId(UUID clinicId, UUID patientId, Pageable pageable);

    Optional<TreatmentRecord> findByAppointmentId(UUID appointmentId);

    boolean existsByAppointmentId(UUID appointmentId);
}
