package com.physiomanage.service;

import com.physiomanage.dto.request.AppointmentRequest;
import com.physiomanage.entity.Appointment;
import com.physiomanage.entity.AppointmentStatus;
import com.physiomanage.entity.Clinic;
import com.physiomanage.entity.Patient;
import com.physiomanage.entity.Professional;
import com.physiomanage.exception.InvalidStatusTransitionException;
import com.physiomanage.exception.ResourceNotFoundException;
import com.physiomanage.exception.ScheduleConflictException;
import com.physiomanage.repository.AppointmentRepository;
import com.physiomanage.repository.ClinicRepository;
import com.physiomanage.repository.PatientRepository;
import com.physiomanage.repository.ProfessionalRepository;
import com.physiomanage.security.ClinicContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Regras centrais da Fase 2: um profissional não pode ter duas consultas
 * ativas que se sobrepõem (checkAvailability, via AppointmentRepository
 * .findOverlapping, que já ignora consultas CANCELLED/NO_SHOW) e a
 * transição de status segue estritamente a máquina de estados documentada
 * em AppointmentStatus — qualquer transição fora do mapa é rejeitada.
 */
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private static final int DEFAULT_DURATION_MINUTES = 50;

    private static final Map<AppointmentStatus, Set<AppointmentStatus>> ALLOWED_TRANSITIONS = Map.of(
            AppointmentStatus.SCHEDULED, Set.of(AppointmentStatus.CONFIRMED),
            AppointmentStatus.CONFIRMED, Set.of(AppointmentStatus.COMPLETED, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW),
            AppointmentStatus.COMPLETED, Set.of(),
            AppointmentStatus.CANCELLED, Set.of(),
            AppointmentStatus.NO_SHOW, Set.of()
    );

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final ProfessionalRepository professionalRepository;
    private final ClinicRepository clinicRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Appointment create(AppointmentRequest request) {
        UUID clinicId = ClinicContext.getClinicId();

        Patient patient = findOwnedPatient(request.patientId(), clinicId);
        Professional professional = findOwnedProfessional(request.professionalId(), clinicId);
        int durationMinutes = resolveDuration(request);

        checkAvailability(professional.getId(), request.scheduledAt(), durationMinutes, null);

        Clinic clinic = clinicRepository.getReferenceById(clinicId);

        Appointment appointment = new Appointment();
        appointment.setClinic(clinic);
        appointment.setPatient(patient);
        appointment.setProfessional(professional);
        appointment.setScheduledAt(request.scheduledAt());
        appointment.setDurationMinutes(durationMinutes);
        appointment.setNotes(request.notes());

        Appointment saved = saveWithOverlapGuard(appointment);

        /*
         * Publicado aqui (ainda dentro da transação) mas só entregue ao
         * listener @Async depois do commit (ver NotificationService) —
         * do contrário a thread assíncrona poderia tentar gravar
         * notification_log referenciando um appointment_id ainda não
         * commitado, violando a FK ou falhando silenciosamente.
         */
        eventPublisher.publishEvent(new AppointmentNotificationCommand(
                clinicId, saved.getId(), patient.getEmail(), professional.getName(), saved.getScheduledAt()
        ));

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Appointment> list(Pageable pageable) {
        return appointmentRepository.findByClinicId(ClinicContext.getClinicId(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<Appointment> listByPatient(UUID patientId, Pageable pageable) {
        return appointmentRepository.findByClinicIdAndPatientId(ClinicContext.getClinicId(), patientId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Appointment> listByProfessional(UUID professionalId, Pageable pageable) {
        return appointmentRepository.findByClinicIdAndProfessionalId(ClinicContext.getClinicId(), professionalId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Appointment> listMine(Pageable pageable) {
        Professional professional = findOwnProfessional();
        return appointmentRepository.findByClinicIdAndProfessionalId(ClinicContext.getClinicId(), professional.getId(), pageable);
    }

    @Transactional(readOnly = true)
    public Appointment getById(UUID id) {
        return findOwnedAppointment(id);
    }

    @Transactional
    public Appointment reschedule(UUID id, AppointmentRequest request) {
        Appointment appointment = findOwnedAppointment(id);
        UUID clinicId = ClinicContext.getClinicId();

        Patient patient = findOwnedPatient(request.patientId(), clinicId);
        Professional professional = findOwnedProfessional(request.professionalId(), clinicId);
        int durationMinutes = resolveDuration(request);

        checkAvailability(professional.getId(), request.scheduledAt(), durationMinutes, appointment.getId());

        appointment.setPatient(patient);
        appointment.setProfessional(professional);
        appointment.setScheduledAt(request.scheduledAt());
        appointment.setDurationMinutes(durationMinutes);
        appointment.setNotes(request.notes());

        return saveWithOverlapGuard(appointment);
    }

    @Transactional
    public Appointment updateStatus(UUID id, AppointmentStatus newStatus) {
        Appointment appointment = findOwnedAppointment(id);

        if ("PROFESSIONAL".equals(ClinicContext.getRole())) {
            Professional own = findOwnProfessional();
            if (!appointment.getProfessional().getId().equals(own.getId())) {
                throw new ResourceNotFoundException("Consulta");
            }
        }

        AppointmentStatus currentStatus = appointment.getStatus();
        if (!ALLOWED_TRANSITIONS.get(currentStatus).contains(newStatus)) {
            throw new InvalidStatusTransitionException(currentStatus, newStatus);
        }

        appointment.setStatus(newStatus);
        return appointmentRepository.save(appointment);
    }

    private int resolveDuration(AppointmentRequest request) {
        return request.durationMinutes() != null ? request.durationMinutes() : DEFAULT_DURATION_MINUTES;
    }

    /**
     * O findOverlapping em checkAvailability é um SELECT seguido de
     * INSERT/UPDATE sem lock — sob concorrência, duas requisições podem
     * passar pela checagem antes de qualquer uma persistir. saveAndFlush
     * força o INSERT/UPDATE (e a constraint de exclusão do banco, ver
     * V2__appointment_overlap_constraint.sql) a rodar aqui, dentro do
     * try/catch, em vez de só no commit da transação.
     */
    private Appointment saveWithOverlapGuard(Appointment appointment) {
        try {
            return appointmentRepository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException e) {
            throw new ScheduleConflictException();
        }
    }

    private void checkAvailability(UUID professionalId, Instant scheduledAt, int durationMinutes, UUID excludeAppointmentId) {
        Instant end = scheduledAt.plus(Duration.ofMinutes(durationMinutes));
        boolean hasConflict = appointmentRepository.findOverlapping(professionalId, scheduledAt, end).stream()
                .anyMatch(existing -> !existing.getId().equals(excludeAppointmentId));
        if (hasConflict) {
            throw new ScheduleConflictException();
        }
    }

    private Professional findOwnProfessional() {
        return professionalRepository.findByUserIdAndClinicId(ClinicContext.getUserId(), ClinicContext.getClinicId())
                .orElseThrow(() -> new ResourceNotFoundException("Profissional"));
    }

    private Patient findOwnedPatient(UUID id, UUID clinicId) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente"));
        if (!patient.getClinic().getId().equals(clinicId)) {
            throw new ResourceNotFoundException("Paciente");
        }
        if (!patient.isActive()) {
            throw new ResourceNotFoundException("Paciente");
        }
        return patient;
    }

    private Professional findOwnedProfessional(UUID id, UUID clinicId) {
        Professional professional = professionalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Profissional"));
        if (!professional.getClinic().getId().equals(clinicId)) {
            throw new ResourceNotFoundException("Profissional");
        }
        if (!professional.isActive()) {
            throw new ResourceNotFoundException("Profissional");
        }
        return professional;
    }

    private Appointment findOwnedAppointment(UUID id) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Consulta"));
        if (!appointment.getClinic().getId().equals(ClinicContext.getClinicId())) {
            throw new ResourceNotFoundException("Consulta");
        }
        return appointment;
    }
}
