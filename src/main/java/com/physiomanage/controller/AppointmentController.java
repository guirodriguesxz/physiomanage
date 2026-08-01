package com.physiomanage.controller;

import com.physiomanage.dto.request.AppointmentRequest;
import com.physiomanage.dto.request.AppointmentStatusUpdateRequest;
import com.physiomanage.dto.response.AppointmentResponse;
import com.physiomanage.entity.Appointment;
import com.physiomanage.service.AppointmentService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments")
@RequiredArgsConstructor
@Tag(name = "Consultas")
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPTION')")
    public ResponseEntity<AppointmentResponse> create(@Valid @RequestBody AppointmentRequest request) {
        var appointment = appointmentService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AppointmentResponse.from(appointment));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPTION')")
    public ResponseEntity<Page<AppointmentResponse>> list(
            @RequestParam(required = false) UUID patientId,
            @RequestParam(required = false) UUID professionalId,
            Pageable pageable) {

        Page<Appointment> appointments;
        if (patientId != null) {
            appointments = appointmentService.listByPatient(patientId, pageable);
        } else if (professionalId != null) {
            appointments = appointmentService.listByProfessional(professionalId, pageable);
        } else {
            appointments = appointmentService.list(pageable);
        }
        return ResponseEntity.ok(appointments.map(AppointmentResponse::from));
    }

    /**
     * Agenda do profissional autenticado — não recebe professionalId no
     * path/query para não depender de o cliente informar corretamente o
     * próprio ID; é resolvido a partir do usuário do token.
     */
    @GetMapping("/me")
    @PreAuthorize("hasRole('PROFESSIONAL')")
    public ResponseEntity<Page<AppointmentResponse>> listMine(Pageable pageable) {
        return ResponseEntity.ok(appointmentService.listMine(pageable).map(AppointmentResponse::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPTION')")
    public ResponseEntity<AppointmentResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(AppointmentResponse.from(appointmentService.getById(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPTION')")
    public ResponseEntity<AppointmentResponse> reschedule(@PathVariable UUID id, @Valid @RequestBody AppointmentRequest request) {
        return ResponseEntity.ok(AppointmentResponse.from(appointmentService.reschedule(id, request)));
    }

    /**
     * PROFESSIONAL só altera status das próprias consultas — a checagem
     * de posse fica no AppointmentService.updateStatus.
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPTION', 'PROFESSIONAL')")
    public ResponseEntity<AppointmentResponse> updateStatus(@PathVariable UUID id, @Valid @RequestBody AppointmentStatusUpdateRequest request) {
        return ResponseEntity.ok(AppointmentResponse.from(appointmentService.updateStatus(id, request.status())));
    }
}
