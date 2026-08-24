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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unitário (Mockito, sem Spring/DB/Redis) da regra central da Fase 2:
 * máquina de estados da consulta e a guarda multi-tenant/overlap. Cenários
 * de concorrência real (constraint de exclusão do banco) continuam só em
 * AppointmentFlowIntegrationTest — aqui cobrimos a lógica pura do service.
 */
@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private ProfessionalRepository professionalRepository;
    @Mock
    private ClinicRepository clinicRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AvailabilityCache availabilityCache;

    private SimpleMeterRegistry meterRegistry;
    private AppointmentService appointmentService;

    private final UUID clinicId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID professionalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        appointmentService = new AppointmentService(
                appointmentRepository, patientRepository, professionalRepository,
                clinicRepository, eventPublisher, availabilityCache, meterRegistry
        );
        ClinicContext.set(clinicId, UUID.randomUUID(), "ADMIN");
    }

    @AfterEach
    void tearDown() {
        ClinicContext.clear();
    }

    @Test
    void create_shouldSaveAppointmentAndCountScheduledStatus() {
        stubOwnedPatient(activePatient());
        stubOwnedProfessional(activeProfessional());
        when(appointmentRepository.findOverlapping(any(), any(), any())).thenReturn(List.of());
        when(clinicRepository.getReferenceById(clinicId)).thenReturn(clinicWithId(clinicId));
        when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        AppointmentRequest request = requestFor(patientId, professionalId, null);
        Appointment saved = appointmentService.create(request);

        assertEquals(AppointmentStatus.SCHEDULED, saved.getStatus());
        assertEquals(50, saved.getDurationMinutes());
        assertEquals(1.0, meterRegistry.counter("appointments_total", "status", "SCHEDULED").count());
        verify(eventPublisher).publishEvent(any(AppointmentNotificationCommand.class));
        verify(availabilityCache).evict(any(), any(), any());
    }

    @Test
    void create_shouldUseRequestedDuration_whenProvided() {
        stubOwnedPatient(activePatient());
        stubOwnedProfessional(activeProfessional());
        when(appointmentRepository.findOverlapping(any(), any(), any())).thenReturn(List.of());
        when(clinicRepository.getReferenceById(clinicId)).thenReturn(clinicWithId(clinicId));
        when(appointmentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Appointment saved = appointmentService.create(requestFor(patientId, professionalId, 30));

        assertEquals(30, saved.getDurationMinutes());
    }

    @Test
    void create_shouldThrowScheduleConflict_whenOverlappingAppointmentExists() {
        stubOwnedPatient(activePatient());
        stubOwnedProfessional(activeProfessional());
        Appointment existing = new Appointment();
        existing.setId(UUID.randomUUID());
        when(appointmentRepository.findOverlapping(any(), any(), any())).thenReturn(List.of(existing));

        assertThrows(ScheduleConflictException.class,
                () -> appointmentService.create(requestFor(patientId, professionalId, null)));
        verify(appointmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_shouldMapDataIntegrityViolation_toScheduleConflict() {
        stubOwnedPatient(activePatient());
        stubOwnedProfessional(activeProfessional());
        when(appointmentRepository.findOverlapping(any(), any(), any())).thenReturn(List.of());
        when(clinicRepository.getReferenceById(clinicId)).thenReturn(clinicWithId(clinicId));
        when(appointmentRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("exclusion constraint"));

        assertThrows(ScheduleConflictException.class,
                () -> appointmentService.create(requestFor(patientId, professionalId, null)));
    }

    @Test
    void create_shouldThrowResourceNotFound_whenPatientBelongsToAnotherClinic() {
        Patient otherClinicPatient = new Patient();
        otherClinicPatient.setId(patientId);
        otherClinicPatient.setActive(true);
        otherClinicPatient.setClinic(clinicWithId(UUID.randomUUID()));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(otherClinicPatient));

        assertThrows(ResourceNotFoundException.class,
                () -> appointmentService.create(requestFor(patientId, professionalId, null)));
    }

    @Test
    void create_shouldThrowResourceNotFound_whenPatientInactive() {
        Patient inactivePatient = activePatient();
        inactivePatient.setActive(false);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(inactivePatient));

        assertThrows(ResourceNotFoundException.class,
                () -> appointmentService.create(requestFor(patientId, professionalId, null)));
    }

    @Test
    void updateStatus_shouldAllowScheduledToConfirmed() {
        Appointment appointment = ownedAppointment(AppointmentStatus.SCHEDULED, professionalId);
        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));
        when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Appointment updated = appointmentService.updateStatus(appointment.getId(), AppointmentStatus.CONFIRMED);

        assertEquals(AppointmentStatus.CONFIRMED, updated.getStatus());
        assertEquals(1.0, meterRegistry.counter("appointments_total", "status", "CONFIRMED").count());
    }

    @Test
    void updateStatus_shouldRejectSkippingConfirmedStep() {
        Appointment appointment = ownedAppointment(AppointmentStatus.SCHEDULED, professionalId);
        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));

        assertThrows(InvalidStatusTransitionException.class,
                () -> appointmentService.updateStatus(appointment.getId(), AppointmentStatus.COMPLETED));
    }

    @Test
    void updateStatus_shouldRejectAnyTransition_fromTerminalState() {
        Appointment appointment = ownedAppointment(AppointmentStatus.COMPLETED, professionalId);
        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));

        assertThrows(InvalidStatusTransitionException.class,
                () -> appointmentService.updateStatus(appointment.getId(), AppointmentStatus.CANCELLED));
    }

    @Test
    void updateStatus_shouldThrowResourceNotFound_whenAppointmentBelongsToAnotherClinic() {
        Appointment appointment = ownedAppointment(AppointmentStatus.SCHEDULED, professionalId);
        appointment.setClinic(clinicWithId(UUID.randomUUID()));
        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));

        assertThrows(ResourceNotFoundException.class,
                () -> appointmentService.updateStatus(appointment.getId(), AppointmentStatus.CONFIRMED));
    }

    @Test
    void updateStatus_shouldRejectWhenActingProfessionalIsNotTheOwner() {
        UUID actingUserId = UUID.randomUUID();
        ClinicContext.clear();
        ClinicContext.set(clinicId, actingUserId, "PROFESSIONAL");

        Appointment appointment = ownedAppointment(AppointmentStatus.SCHEDULED, professionalId);
        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));

        Professional actingProfessional = new Professional();
        actingProfessional.setId(UUID.randomUUID()); // diferente do dono da consulta
        when(professionalRepository.findByUserIdAndClinicId(actingUserId, clinicId))
                .thenReturn(Optional.of(actingProfessional));

        assertThrows(ResourceNotFoundException.class,
                () -> appointmentService.updateStatus(appointment.getId(), AppointmentStatus.CONFIRMED));
    }

    @Test
    void updateStatus_shouldAllowWhenActingProfessionalIsTheOwner() {
        UUID actingUserId = UUID.randomUUID();
        ClinicContext.clear();
        ClinicContext.set(clinicId, actingUserId, "PROFESSIONAL");

        Appointment appointment = ownedAppointment(AppointmentStatus.SCHEDULED, professionalId);
        when(appointmentRepository.findById(appointment.getId())).thenReturn(Optional.of(appointment));

        Professional actingProfessional = new Professional();
        actingProfessional.setId(professionalId); // mesmo profissional da consulta
        when(professionalRepository.findByUserIdAndClinicId(actingUserId, clinicId))
                .thenReturn(Optional.of(actingProfessional));
        when(appointmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Appointment updated = appointmentService.updateStatus(appointment.getId(), AppointmentStatus.CONFIRMED);

        assertEquals(AppointmentStatus.CONFIRMED, updated.getStatus());
    }

    // --- fixtures ---

    private void stubOwnedPatient(Patient patient) {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
    }

    private void stubOwnedProfessional(Professional professional) {
        when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
    }

    private Patient activePatient() {
        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setActive(true);
        patient.setClinic(clinicWithId(clinicId));
        return patient;
    }

    private Professional activeProfessional() {
        Professional professional = new Professional();
        professional.setId(professionalId);
        professional.setActive(true);
        professional.setClinic(clinicWithId(clinicId));
        return professional;
    }

    private Clinic clinicWithId(UUID id) {
        Clinic clinic = new Clinic();
        clinic.setId(id);
        return clinic;
    }

    private AppointmentRequest requestFor(UUID patientId, UUID professionalId, Integer durationMinutes) {
        return new AppointmentRequest(patientId, professionalId, Instant.now().plus(1, ChronoUnit.DAYS), durationMinutes, null);
    }

    private Appointment ownedAppointment(AppointmentStatus status, UUID professionalId) {
        Appointment appointment = new Appointment();
        appointment.setId(UUID.randomUUID());
        appointment.setClinic(clinicWithId(clinicId));
        appointment.setStatus(status);
        appointment.setScheduledAt(Instant.now().plus(1, ChronoUnit.DAYS));
        Professional professional = new Professional();
        professional.setId(professionalId);
        appointment.setProfessional(professional);
        return appointment;
    }
}
