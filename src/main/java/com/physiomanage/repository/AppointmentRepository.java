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

    /**
     * Base do relatório de resumo (ver ReportService): contagem de
     * consultas por status num intervalo [start, end). Um status sem
     * nenhuma consulta no período simplesmente não aparece na lista — o
     * ReportService preenche os que faltam com zero.
     */
    @Query("""
            SELECT a.status AS status, COUNT(a) AS count
            FROM Appointment a
            WHERE a.clinic.id = :clinicId
              AND a.scheduledAt >= :start
              AND a.scheduledAt < :end
            GROUP BY a.status
            """)
    List<StatusCount> countByStatusInRange(@Param("clinicId") UUID clinicId,
                                            @Param("start") Instant start,
                                            @Param("end") Instant end);

    /**
     * Base do relatório de produtividade por profissional (ver
     * ReportService): total de consultas concluídas/canceladas/no-show
     * num intervalo, por profissional.
     */
    @Query("""
            SELECT a.professional.id AS professionalId,
                   a.professional.name AS professionalName,
                   SUM(CASE WHEN a.status = com.physiomanage.entity.AppointmentStatus.COMPLETED THEN 1L ELSE 0L END) AS completed,
                   SUM(CASE WHEN a.status = com.physiomanage.entity.AppointmentStatus.CANCELLED THEN 1L ELSE 0L END) AS cancelled,
                   SUM(CASE WHEN a.status = com.physiomanage.entity.AppointmentStatus.NO_SHOW THEN 1L ELSE 0L END) AS noShow,
                   COUNT(a) AS total
            FROM Appointment a
            WHERE a.clinic.id = :clinicId
              AND a.scheduledAt >= :start
              AND a.scheduledAt < :end
            GROUP BY a.professional.id, a.professional.name
            ORDER BY completed DESC
            """)
    List<ProfessionalProductivity> productivityByProfessional(@Param("clinicId") UUID clinicId,
                                                                @Param("start") Instant start,
                                                                @Param("end") Instant end);

    interface StatusCount {
        AppointmentStatus getStatus();
        long getCount();
    }

    interface ProfessionalProductivity {
        UUID getProfessionalId();
        String getProfessionalName();
        long getCompleted();
        long getCancelled();
        long getNoShow();
        long getTotal();
    }
}
