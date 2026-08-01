package com.physiomanage.repository;

import com.physiomanage.entity.Appointment;
import com.physiomanage.entity.AppointmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /**
     * Sobrescreve findById para trazer patient/professional já
     * inicializados: com open-in-view=false, o AppointmentResponse é
     * montado no controller (fora da transação), e os dois são
     * ManyToOne LAZY — sem isso, dá LazyInitializationException.
     */
    @Override
    @EntityGraph(attributePaths = {"patient", "professional"})
    Optional<Appointment> findById(UUID id);

    @EntityGraph(attributePaths = {"patient", "professional"})
    Page<Appointment> findByClinicId(UUID clinicId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "professional"})
    Page<Appointment> findByClinicIdAndPatientId(UUID clinicId, UUID patientId, Pageable pageable);

    @EntityGraph(attributePaths = {"patient", "professional"})
    Page<Appointment> findByClinicIdAndProfessionalId(UUID clinicId, UUID professionalId, Pageable pageable);

    /**
     * Verifica se o profissional já tem uma consulta ativa (não cancelada)
     * que colide com o intervalo [start, end). Usado para bloquear
     * duplo-agendamento antes de persistir uma nova consulta.
     */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.professional.id = :professionalId
              AND a.status NOT IN (com.physiomanage.entity.AppointmentStatus.CANCELLED,
                                    com.physiomanage.entity.AppointmentStatus.NO_SHOW)
              AND a.scheduledAt < :end
              AND FUNCTION('timestampadd', MINUTE, a.durationMinutes, a.scheduledAt) > :start
            """)
    List<Appointment> findOverlapping(@Param("professionalId") UUID professionalId,
                                       @Param("start") Instant start,
                                       @Param("end") Instant end);

    long countByClinicIdAndStatus(UUID clinicId, AppointmentStatus status);
}
