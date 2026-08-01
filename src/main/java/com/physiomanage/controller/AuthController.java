package com.physiomanage.controller;

import com.physiomanage.dto.request.LoginRequest;
import com.physiomanage.dto.request.RegisterClinicRequest;
import com.physiomanage.dto.response.AuthResponse;
import com.physiomanage.service.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Autenticação", description = "Cadastro de clínica e login")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register-clinic")
    public ResponseEntity<AuthResponse> registerClinic(@Valid @RequestBody RegisterClinicRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.registerClinic(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }
}
