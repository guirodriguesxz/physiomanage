package com.physiomanage.controller;

import com.physiomanage.dto.response.NotificationLogResponse;
import com.physiomanage.security.ClinicContext;
import com.physiomanage.service.NotificationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Visibilidade operacional sobre o processamento assíncrono de
 * notificações de consulta (ver NotificationService) — não existe fluxo
 * de negócio que dependa deste endpoint, ele serve para inspecionar/
 * validar o resultado do envio simulado.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Tag(name = "Notificações")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<NotificationLogResponse>> list(@RequestParam(required = false) UUID appointmentId, Pageable pageable) {
        UUID clinicId = ClinicContext.getClinicId();
        Page<NotificationLogResponse> page = appointmentId != null
                ? notificationService.listByAppointment(clinicId, appointmentId, pageable).map(NotificationLogResponse::from)
                : notificationService.listByClinic(clinicId, pageable).map(NotificationLogResponse::from);
        return ResponseEntity.ok(page);
    }
}
