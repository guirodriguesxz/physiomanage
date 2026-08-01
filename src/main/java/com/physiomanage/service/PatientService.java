package com.physiomanage.service;

import com.physiomanage.dto.request.PatientRequest;
import com.physiomanage.entity.Clinic;
import com.physiomanage.entity.Patient;
import com.physiomanage.exception.DuplicateResourceException;
import com.physiomanage.exception.ResourceNotFoundException;
import com.physiomanage.repository.ClinicRepository;
import com.physiomanage.repository.PatientRepository;
import com.physiomanage.security.ClinicContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Toda consulta e escrita aqui é filtrada pelo clinicId do
 * ClinicContext (extraído do JWT) — nunca por um clinicId vindo do
 * cliente. Isso é o que garante, na prática, que o usuário da Clínica A
 * não acesse/edite pacientes da Clínica B mesmo que tente adivinhar IDs.
 */
@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;
    private final ClinicRepository clinicRepository;

    @Transactional
    public Patient create(PatientRequest request) {
        UUID clinicId = ClinicContext.getClinicId();

        if (patientRepository.existsByCpfAndClinicId(request.cpf(), clinicId)) {
            throw new DuplicateResourceException("Já existe um paciente cadastrado com esse CPF");
        }

        Clinic clinic = clinicRepository.getReferenceById(clinicId);

        Patient patient = new Patient();
        patient.setClinic(clinic);
        applyRequest(patient, request);

        return patientRepository.save(patient);
    }

    @Transactional(readOnly = true)
    public Page<Patient> list(Pageable pageable) {
        return patientRepository.findByClinicId(ClinicContext.getClinicId(), pageable);
    }

    @Transactional(readOnly = true)
    public Patient getById(UUID id) {
        return findOwnedById(id);
    }

    @Transactional
    public Patient update(UUID id, PatientRequest request) {
        Patient patient = findOwnedById(id);
        applyRequest(patient, request);
        return patientRepository.save(patient);
    }

    @Transactional
    public void deactivate(UUID id) {
        Patient patient = findOwnedById(id);
        patient.setActive(false);
        patientRepository.save(patient);
    }

    /**
     * Busca o paciente garantindo que ele pertence à clínica do usuário
     * autenticado. Se o ID existir mas for de outra clínica, tratamos
     * como "não encontrado" (não como "proibido") para não revelar a
     * existência de dados de outro tenant.
     */
    private Patient findOwnedById(UUID id) {
        Patient patient = patientRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Paciente"));

        if (!patient.getClinic().getId().equals(ClinicContext.getClinicId())) {
            throw new ResourceNotFoundException("Paciente");
        }
        return patient;
    }

    private void applyRequest(Patient patient, PatientRequest request) {
        patient.setName(request.name());
        patient.setCpf(request.cpf());
        patient.setBirthDate(request.birthDate());
        patient.setPhone(request.phone());
        patient.setEmail(request.email());
        patient.setInsuranceName(request.insuranceName());
        patient.setInsuranceNumber(request.insuranceNumber());
    }
}
