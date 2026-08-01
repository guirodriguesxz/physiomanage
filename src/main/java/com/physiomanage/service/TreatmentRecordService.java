package com.physiomanage.service;

import com.physiomanage.dto.request.TreatmentRecordRequest;
import com.physiomanage.dto.request.TreatmentRecordUpdateRequest;
import com.physiomanage.entity.Appointment;
import com.physiomanage.entity.AppointmentStatus;
import com.physiomanage.entity.Professional;
import com.physiomanage.entity.TreatmentRecord;
import com.physiomanage.exception.AppointmentNotCompletedException;
import com.physiomanage.exception.DuplicateResourceException;
import com.physiomanage.exception.ResourceNotFoundException;
import com.physiomanage.repository.AppointmentRepository;
import com.physiomanage.repository.ProfessionalRepository;
import com.physiomanage.repository.TreatmentRecordRepository;
import com.physiomanage.security.ClinicContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Regra central da Fase 3: só o profissional dono da consulta pode
 * registrar/editar a evolução, e só depois que a consulta está COMPLETED
 * (ver AppointmentStatus). Cada consulta admite no máximo um registro —
 * ver constraint UNIQUE em treatment_records.appointment_id.
 */
@Service
@RequiredArgsConstructor
public class TreatmentRecordService {

    private final TreatmentRecordRepository treatmentRecordRepository;
    private final AppointmentRepository appointmentRepository;
    private final ProfessionalRepository professionalRepository;

    @Transactional
    public TreatmentRecord create(TreatmentRecordRequest request) {
        UUID clinicId = ClinicContext.getClinicId();
        Appointment appointment = findOwnedAppointment(request.appointmentId(), clinicId);
        Professional own = findOwnProfessional();

        if (!appointment.getProfessional().getId().equals(own.getId())) {
            throw new ResourceNotFoundException("Consulta");
        }
        if (appointment.getStatus() != AppointmentStatus.COMPLETED) {
            throw new AppointmentNotCompletedException();
        }
        if (treatmentRecordRepository.existsByAppointmentId(appointment.getId())) {
            throw new DuplicateResourceException("Prontuário já registrado para esta consulta");
        }

        TreatmentRecord record = new TreatmentRecord();
        record.setClinic(appointment.getClinic());
        record.setAppointment(appointment);
        record.setPatient(appointment.getPatient());
        record.setProfessional(appointment.getProfessional());
        record.setEvolution(request.evolution());

        return saveWithUniqueGuard(record);
    }

    /**
     * O existsByAppointmentId acima é um SELECT seguido de INSERT sem
     * lock — duas requisições concorrentes para a mesma consulta podem
     * passar pela checagem antes de qualquer uma persistir. saveAndFlush
     * força o INSERT a rodar aqui, dentro do try/catch, para que a
     * constraint UNIQUE de treatment_records.appointment_id vire um 409
     * de negócio em vez de um 500 não tratado (mesmo padrão de
     * AppointmentService.saveWithOverlapGuard).
     */
    private TreatmentRecord saveWithUniqueGuard(TreatmentRecord record) {
        try {
            return treatmentRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateResourceException("Prontuário já registrado para esta consulta");
        }
    }

    @Transactional
    public TreatmentRecord update(UUID id, TreatmentRecordUpdateRequest request) {
        TreatmentRecord record = findOwnedRecord(id);
        Professional own = findOwnProfessional();

        if (!record.getProfessional().getId().equals(own.getId())) {
            throw new ResourceNotFoundException("Prontuário");
        }

        record.setEvolution(request.evolution());
        return treatmentRecordRepository.save(record);
    }

    @Transactional(readOnly = true)
    public TreatmentRecord getById(UUID id) {
        TreatmentRecord record = findOwnedRecord(id);

        if ("PROFESSIONAL".equals(ClinicContext.getRole())) {
            Professional own = findOwnProfessional();
            if (!record.getProfessional().getId().equals(own.getId())) {
                throw new ResourceNotFoundException("Prontuário");
            }
        }

        return record;
    }

    @Transactional(readOnly = true)
    public Page<TreatmentRecord> listByPatient(UUID patientId, Pageable pageable) {
        return treatmentRecordRepository.findByClinicIdAndPatientId(ClinicContext.getClinicId(), patientId, pageable);
    }

    private Professional findOwnProfessional() {
        return professionalRepository.findByUserIdAndClinicId(ClinicContext.getUserId(), ClinicContext.getClinicId())
                .orElseThrow(() -> new ResourceNotFoundException("Profissional"));
    }

    private Appointment findOwnedAppointment(UUID id, UUID clinicId) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Consulta"));
        if (!appointment.getClinic().getId().equals(clinicId)) {
            throw new ResourceNotFoundException("Consulta");
        }
        return appointment;
    }

    private TreatmentRecord findOwnedRecord(UUID id) {
        TreatmentRecord record = treatmentRecordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Prontuário"));
        if (!record.getClinic().getId().equals(ClinicContext.getClinicId())) {
            throw new ResourceNotFoundException("Prontuário");
        }
        return record;
    }
}
