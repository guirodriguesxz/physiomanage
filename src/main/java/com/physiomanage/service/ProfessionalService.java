package com.physiomanage.service;

import com.physiomanage.dto.request.ProfessionalRequest;
import com.physiomanage.entity.Clinic;
import com.physiomanage.entity.Professional;
import com.physiomanage.entity.Role;
import com.physiomanage.entity.User;
import com.physiomanage.exception.DuplicateResourceException;
import com.physiomanage.exception.ResourceNotFoundException;
import com.physiomanage.repository.ClinicRepository;
import com.physiomanage.repository.ProfessionalRepository;
import com.physiomanage.repository.UserRepository;
import com.physiomanage.security.ClinicContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfessionalService {

    private final ProfessionalRepository professionalRepository;
    private final UserRepository userRepository;
    private final ClinicRepository clinicRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Cria o User (role PROFESSIONAL) e o Professional numa única
     * transação — um Professional sem User associado não conseguiria
     * fazer login, então não faz sentido existir um sem o outro.
     */
    @Transactional
    public Professional create(ProfessionalRequest request) {
        UUID clinicId = ClinicContext.getClinicId();

        if (userRepository.existsByEmailAndClinicId(request.email(), clinicId)) {
            throw new DuplicateResourceException("Já existe um usuário cadastrado com esse e-mail nessa clínica");
        }
        if (professionalRepository.existsByLicenseNumberAndClinicId(request.licenseNumber(), clinicId)) {
            throw new DuplicateResourceException("Já existe um profissional cadastrado com esse registro (CREFITO)");
        }

        Clinic clinic = clinicRepository.getReferenceById(clinicId);

        User user = new User();
        user.setClinic(clinic);
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.PROFESSIONAL);
        user = userRepository.save(user);

        Professional professional = new Professional();
        professional.setClinic(clinic);
        professional.setUser(user);
        professional.setName(request.name());
        professional.setSpecialty(request.specialty());
        professional.setLicenseNumber(request.licenseNumber());

        return professionalRepository.save(professional);
    }

    @Transactional(readOnly = true)
    public Page<Professional> list(Pageable pageable) {
        return professionalRepository.findByClinicId(ClinicContext.getClinicId(), pageable);
    }

    @Transactional(readOnly = true)
    public Professional getById(UUID id) {
        return findOwnedById(id);
    }

    @Transactional
    public void deactivate(UUID id) {
        Professional professional = findOwnedById(id);
        professional.setActive(false);
        professional.getUser().setActive(false);
        professionalRepository.save(professional);
    }

    private Professional findOwnedById(UUID id) {
        Professional professional = professionalRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Profissional"));

        if (!professional.getClinic().getId().equals(ClinicContext.getClinicId())) {
            throw new ResourceNotFoundException("Profissional");
        }
        return professional;
    }
}
