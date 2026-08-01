package com.physiomanage.service;

import com.physiomanage.dto.request.LoginRequest;
import com.physiomanage.dto.request.RegisterClinicRequest;
import com.physiomanage.dto.response.AuthResponse;
import com.physiomanage.entity.Clinic;
import com.physiomanage.entity.Role;
import com.physiomanage.entity.User;
import com.physiomanage.exception.DuplicateResourceException;
import com.physiomanage.exception.InvalidCredentialsException;
import com.physiomanage.repository.ClinicRepository;
import com.physiomanage.repository.UserRepository;
import com.physiomanage.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final ClinicRepository clinicRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Cadastra uma nova clínica (tenant) junto com seu primeiro usuário,
     * que sempre nasce como ADMIN. É transacional: se a criação do User
     * falhar, a Clinic também não é persistida.
     */
    @Transactional
    public AuthResponse registerClinic(RegisterClinicRequest request) {
        if (clinicRepository.existsByCnpj(request.cnpj())) {
            throw new DuplicateResourceException("Já existe uma clínica cadastrada com esse CNPJ");
        }
        if (clinicRepository.existsByEmail(request.adminEmail())) {
            throw new DuplicateResourceException("Já existe uma clínica cadastrada com esse e-mail");
        }

        Clinic clinic = new Clinic();
        clinic.setName(request.clinicName());
        clinic.setCnpj(request.cnpj());
        clinic.setEmail(request.adminEmail());
        clinic = clinicRepository.save(clinic);

        User admin = new User();
        admin.setClinic(clinic);
        admin.setName(request.adminName());
        admin.setEmail(request.adminEmail());
        admin.setPasswordHash(passwordEncoder.encode(request.adminPassword()));
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);

        String token = jwtService.generateToken(admin);
        return AuthResponse.of(token, admin.getId(), clinic.getId(), admin.getRole().name());
    }

    /**
     * Login por CNPJ (identifica a clínica) + e-mail + senha. O CNPJ é
     * pedido explicitamente porque, como o e-mail só é único dentro de
     * uma clínica, não temos como descobrir "qual clínica" só pelo e-mail.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Clinic clinic = clinicRepository.findByCnpj(request.clinicCnpj())
                .orElseThrow(InvalidCredentialsException::new);

        User user = userRepository.findByEmailAndClinicId(request.email(), clinic.getId())
                .orElseThrow(InvalidCredentialsException::new);

        if (!user.isActive() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user);
        return AuthResponse.of(token, user.getId(), clinic.getId(), user.getRole().name());
    }
}
