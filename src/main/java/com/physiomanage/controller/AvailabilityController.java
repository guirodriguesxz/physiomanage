package com.physiomanage.controller;

import com.physiomanage.dto.response.AvailabilityResponse;
import com.physiomanage.service.AvailabilityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/professionals/{id}/availability")
@RequiredArgsConstructor
@Tag(name = "Disponibilidade")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPTION')")
    public ResponseEntity<AvailabilityResponse> getAvailability(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var slots = availabilityService.getAvailability(id, date);
        return ResponseEntity.ok(new AvailabilityResponse(id, date, slots));
    }
}
