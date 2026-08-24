package com.physiomanage.service;

import com.physiomanage.dto.request.LoginRequest;
import com.physiomanage.dto.request.RefreshTokenRequest;
import com.physiomanage.dto.request.RegisterClinicRequest;
import com.physiomanage.dto.response.AuthResponse;
import com.physiomanage.entity.Clinic;
import com.physiomanage.entity.Role;
import com.physiomanage.entity.User;
import com.physiomanage.exception.DuplicateResourceException;
import com.physiomanage.exception.InvalidCredentialsException;
import com.physiomanage.exception.InvalidRefreshTokenException;
import com.physiomanage.repository.ClinicRepository;
import com.physiomanage.repository.UserRepository;
import com.physiomanage.security.JwtService;
import com.physiomanage.security.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final ClinicRepository clinicRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

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
        String refreshToken = refreshTokenService.issue(admin.getId());
        return AuthResponse.of(token, refreshToken, admin.getId(), clinic.getId(), admin.getRole().name());
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
        String refreshToken = refreshTokenService.issue(user.getId());
        return AuthResponse.of(token, refreshToken, user.getId(), clinic.getId(), user.getRole().name());
    }

    /**
     * Troca um refresh token válido por um novo par access+refresh
     * (rotação — ver RefreshTokenService). O usuário é revalidado no
     * banco (não confia só no que estava no Redis) para pegar o caso de
     * a conta ter sido desativada depois que o refresh token foi emitido.
     */
    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshTokenRequest request) {
        UUID userId = refreshTokenService.validateAndConsume(request.refreshToken());

        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);

        String token = jwtService.generateToken(user);
        String newRefreshToken = refreshTokenService.issue(user.getId());
        return AuthResponse.of(token, newRefreshToken, user.getId(), user.getClinic().getId(), user.getRole().name());
    }

    /**
     * Revoga o refresh token informado. Idempotente: chamar de novo (ou
     * com um token já expirado/inexistente) não é erro, já que o
     * objetivo final ("esse token não serve mais") já está garantido.
     */
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }
}
