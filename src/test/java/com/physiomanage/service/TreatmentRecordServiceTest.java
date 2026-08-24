package com.physiomanage.service;

import com.physiomanage.dto.request.TreatmentRecordRequest;
import com.physiomanage.dto.request.TreatmentRecordUpdateRequest;
import com.physiomanage.entity.Appointment;
import com.physiomanage.entity.AppointmentStatus;
import com.physiomanage.entity.Clinic;
import com.physiomanage.entity.Patient;
import com.physiomanage.entity.Professional;
import com.physiomanage.entity.TreatmentRecord;
import com.physiomanage.exception.AppointmentNotCompletedException;
import com.physiomanage.exception.DuplicateResourceException;
import com.physiomanage.exception.ResourceNotFoundException;
import com.physiomanage.repository.AppointmentRepository;
import com.physiomanage.repository.ProfessionalRepository;
import com.physiomanage.repository.TreatmentRecordRepository;
import com.physiomanage.security.ClinicContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unitário da regra central da Fase 3: só cria/edita evolução clínica o
 * profissional dono da consulta, só depois de COMPLETED, no máximo um
 * registro por consulta. Fluxo ponta a ponta (constraint UNIQUE do banco
 * sob concorrência) continua em TreatmentRecordFlowIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class TreatmentRecordServiceTest {

    @Mock
    private TreatmentRecordRepository treatmentRecordRepository;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private ProfessionalRepository professionalRepository;

    private TreatmentRecordService treatmentRecordService;

    private final UUID clinicId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID appointmentId = UUID.randomUUID();
    private final UUID ownProfessionalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        treatmentRecordService = new TreatmentRecordService(treatmentRecordRepository, appointmentRepository, professionalRepository);
        ClinicContext.set(clinicId, userId, "PROFESSIONAL");
    }

    @AfterEach
    void tearDown() {
        ClinicContext.clear();
    }

    @Test
    void create_shouldThrowAppointmentNotCompleted_whenAppointmentNotCompleted() {
        Appointment appointment = ownedAppointment(AppointmentStatus.CONFIRMED, ownProfessionalId);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        stubOwnProfessional(ownProfessionalId);

        assertThrows(AppointmentNotCompletedException.class,
                () -> treatmentRecordService.create(new TreatmentRecordRequest(appointmentId, "Evoluiu bem")));
    }

    @Test
    void create_shouldThrowResourceNotFound_whenActingProfessionalIsNotTheOwner() {
        Appointment appointment = ownedAppointment(AppointmentStatus.COMPLETED, UUID.randomUUID());
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        stubOwnProfessional(ownProfessionalId);

        assertThrows(ResourceNotFoundException.class,
                () -> treatmentRecordService.create(new TreatmentRecordRequest(appointmentId, "Evoluiu bem")));
    }

    @Test
    void create_shouldThrowDuplicate_whenRecordAlreadyExistsForAppointment() {
        Appointment appointment = ownedAppointment(AppointmentStatus.COMPLETED, ownProfessionalId);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        stubOwnProfessional(ownProfessionalId);
        when(treatmentRecordRepository.existsByAppointmentId(appointmentId)).thenReturn(true);

        assertThrows(DuplicateResourceException.class,
                () -> treatmentRecordService.create(new TreatmentRecordRequest(appointmentId, "Evoluiu bem")));
    }

    @Test
    void create_shouldMapDataIntegrityViolation_toDuplicateException() {
        Appointment appointment = ownedAppointment(AppointmentStatus.COMPLETED, ownProfessionalId);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        stubOwnProfessional(ownProfessionalId);
        when(treatmentRecordRepository.existsByAppointmentId(appointmentId)).thenReturn(false);
        when(treatmentRecordRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThrows(DuplicateResourceException.class,
                () -> treatmentRecordService.create(new TreatmentRecordRequest(appointmentId, "Evoluiu bem")));
    }

    @Test
    void create_shouldSaveRecordCopyingFieldsFromAppointment() {
        Appointment appointment = ownedAppointment(AppointmentStatus.COMPLETED, ownProfessionalId);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        stubOwnProfessional(ownProfessionalId);
        when(treatmentRecordRepository.existsByAppointmentId(appointmentId)).thenReturn(false);
        when(treatmentRecordRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        TreatmentRecord saved = treatmentRecordService.create(new TreatmentRecordRequest(appointmentId, "Evoluiu bem"));

        assertEquals(appointment.getClinic(), saved.getClinic());
        assertEquals(appointment, saved.getAppointment());
        assertEquals(appointment.getPatient(), saved.getPatient());
        assertEquals(appointment.getProfessional(), saved.getProfessional());
        assertEquals("Evoluiu bem", saved.getEvolution());
    }

    @Test
    void create_shouldThrowResourceNotFound_whenAppointmentBelongsToAnotherClinic() {
        Appointment appointment = ownedAppointment(AppointmentStatus.COMPLETED, ownProfessionalId);
        appointment.setClinic(clinicWithId(UUID.randomUUID()));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        assertThrows(ResourceNotFoundException.class,
                () -> treatmentRecordService.create(new TreatmentRecordRequest(appointmentId, "Evoluiu bem")));
    }

    @Test
    void update_shouldThrowResourceNotFound_whenActingProfessionalIsNotTheOwner() {
        UUID recordId = UUID.randomUUID();
        TreatmentRecord record = ownedRecord(recordId, UUID.randomUUID());
        when(treatmentRecordRepository.findById(recordId)).thenReturn(Optional.of(record));
        stubOwnProfessional(ownProfessionalId);

        assertThrows(ResourceNotFoundException.class,
                () -> treatmentRecordService.update(recordId, new TreatmentRecordUpdateRequest("nova evolução")));
    }

    @Test
    void update_shouldUpdateEvolution_whenActingProfessionalIsTheOwner() {
        UUID recordId = UUID.randomUUID();
        TreatmentRecord record = ownedRecord(recordId, ownProfessionalId);
        when(treatmentRecordRepository.findById(recordId)).thenReturn(Optional.of(record));
        stubOwnProfessional(ownProfessionalId);
        when(treatmentRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TreatmentRecord updated = treatmentRecordService.update(recordId, new TreatmentRecordUpdateRequest("nova evolução"));

        assertEquals("nova evolução", updated.getEvolution());
    }

    @Test
    void getById_shouldThrowResourceNotFound_whenActingProfessionalDoesNotOwnRecord() {
        UUID recordId = UUID.randomUUID();
        TreatmentRecord record = ownedRecord(recordId, UUID.randomUUID());
        when(treatmentRecordRepository.findById(recordId)).thenReturn(Optional.of(record));
        stubOwnProfessional(ownProfessionalId);

        assertThrows(ResourceNotFoundException.class, () -> treatmentRecordService.getById(recordId));
    }

    @Test
    void getById_shouldReturnRecord_whenAdminRegardlessOfOwnership() {
        ClinicContext.clear();
        ClinicContext.set(clinicId, userId, "ADMIN");

        UUID recordId = UUID.randomUUID();
        TreatmentRecord record = ownedRecord(recordId, UUID.randomUUID());
        when(treatmentRecordRepository.findById(recordId)).thenReturn(Optional.of(record));

        TreatmentRecord found = treatmentRecordService.getById(recordId);

        assertEquals(record, found);
    }

    private void stubOwnProfessional(UUID professionalId) {
        Professional own = new Professional();
        own.setId(professionalId);
        when(professionalRepository.findByUserIdAndClinicId(userId, clinicId)).thenReturn(Optional.of(own));
    }

    private Clinic clinicWithId(UUID id) {
        Clinic clinic = new Clinic();
        clinic.setId(id);
        return clinic;
    }

    private Appointment ownedAppointment(AppointmentStatus status, UUID professionalId) {
        Appointment appointment = new Appointment();
        appointment.setId(appointmentId);
        appointment.setClinic(clinicWithId(clinicId));
        appointment.setStatus(status);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        appointment.setPatient(patient);
        Professional professional = new Professional();
        professional.setId(professionalId);
        appointment.setProfessional(professional);
        return appointment;
    }

    private TreatmentRecord ownedRecord(UUID recordId, UUID professionalId) {
        TreatmentRecord record = new TreatmentRecord();
        record.setId(recordId);
        record.setClinic(clinicWithId(clinicId));
        Professional professional = new Professional();
        professional.setId(professionalId);
        record.setProfessional(professional);
        record.setEvolution("evolução original");
        return record;
    }
}
