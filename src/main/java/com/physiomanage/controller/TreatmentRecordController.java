package com.physiomanage.controller;

import com.physiomanage.dto.request.TreatmentRecordRequest;
import com.physiomanage.dto.request.TreatmentRecordUpdateRequest;
import com.physiomanage.dto.response.TreatmentRecordResponse;
import com.physiomanage.service.TreatmentRecordService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/treatment-records")
@RequiredArgsConstructor
@Tag(name = "Prontuários")
public class TreatmentRecordController {

    private final TreatmentRecordService treatmentRecordService;

    @PostMapping
    @PreAuthorize("hasRole('PROFESSIONAL')")
    public ResponseEntity<TreatmentRecordResponse> create(@Valid @RequestBody TreatmentRecordRequest request) {
        var record = treatmentRecordService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TreatmentRecordResponse.from(record));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PROFESSIONAL')")
    public ResponseEntity<TreatmentRecordResponse> update(@PathVariable UUID id, @Valid @RequestBody TreatmentRecordUpdateRequest request) {
        return ResponseEntity.ok(TreatmentRecordResponse.from(treatmentRecordService.update(id, request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPTION', 'PROFESSIONAL')")
    public ResponseEntity<TreatmentRecordResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(TreatmentRecordResponse.from(treatmentRecordService.getById(id)));
    }

    /**
     * Histórico clínico do paciente — restrito a ADMIN/RECEPTION
     * (visão de gestão da clínica); o profissional acessa registros
     * individuais via GET /{id}.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'RECEPTION')")
    public ResponseEntity<Page<TreatmentRecordResponse>> listByPatient(@RequestParam UUID patientId, Pageable pageable) {
        return ResponseEntity.ok(treatmentRecordService.listByPatient(patientId, pageable).map(TreatmentRecordResponse::from));
    }
}
