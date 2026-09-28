package com.physiomanage.service;

import com.physiomanage.entity.Appointment;
import com.physiomanage.entity.Professional;
import com.physiomanage.exception.ResourceNotFoundException;
import com.physiomanage.repository.AppointmentRepository;
import com.physiomanage.security.ClinicContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Disponibilidade = horário fixo de funcionamento da clínica (mesmo para
 * todos os profissionais, configurável em app.availability.*) menos os
 * horários já ocupados por consultas ativas do profissional no dia.
 * O expediente é interpretado no fuso app.timezone (scheduledAt continua
 * em UTC) — simplificação: um fuso único para todas as clínicas, sem
 * dias fechados/feriados.
 */
@Service
public class AvailabilityService {

    private final AppointmentRepository appointmentRepository;
    private final ProfessionalService professionalService;
    private final AvailabilityCache availabilityCache;
    private final int workStartHour;
    private final int workEndHour;
    private final int slotMinutes;
    private final ZoneId clinicZone;

    public AvailabilityService(
            AppointmentRepository appointmentRepository,
            ProfessionalService professionalService,
            AvailabilityCache availabilityCache,
            @Value("${app.availability.work-start-hour}") int workStartHour,
            @Value("${app.availability.work-end-hour}") int workEndHour,
            @Value("${app.availability.slot-minutes}") int slotMinutes,
            ZoneId clinicZone) {
        if (slotMinutes <= 0) {
            // slot-minutes <= 0 trava computeSlots num loop infinito (o
            // Instant do slot nunca avança) — falha já na subida da
            // aplicação em vez de travar a thread na primeira requisição.
            throw new IllegalStateException("app.availability.slot-minutes deve ser positivo, valor atual: " + slotMinutes);
        }
        this.appointmentRepository = appointmentRepository;
        this.professionalService = professionalService;
        this.availabilityCache = availabilityCache;
        this.workStartHour = workStartHour;
        this.workEndHour = workEndHour;
        this.slotMinutes = slotMinutes;
        this.clinicZone = clinicZone;
    }

    @Transactional(readOnly = true)
    public List<Instant> getAvailability(UUID professionalId, LocalDate date) {
        Professional professional = professionalService.getById(professionalId);
        if (!professional.isActive()) {
            throw new ResourceNotFoundException("Profissional");
        }

        UUID clinicId = ClinicContext.getClinicId();
        Optional<List<Instant>> cached = availabilityCache.get(clinicId, professionalId, date);
        if (cached.isPresent()) {
            return cached.get();
        }

        List<Instant> computed = computeSlots(professionalId, date);
        availabilityCache.put(clinicId, professionalId, date, computed);
        return computed;
    }

    private List<Instant> computeSlots(UUID professionalId, LocalDate date) {
        Instant dayStart = date.atStartOfDay(clinicZone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(clinicZone).toInstant();
        List<Appointment> busy = appointmentRepository.findOverlapping(professionalId, dayStart, dayEnd);

        Instant workStart = date.atTime(workStartHour, 0).atZone(clinicZone).toInstant();
        Instant workEnd = date.atTime(workEndHour, 0).atZone(clinicZone).toInstant();
        Duration slotDuration = Duration.ofMinutes(slotMinutes);

        List<Instant> freeSlots = new ArrayList<>();
        for (Instant slotStart = workStart; !slotStart.plus(slotDuration).isAfter(workEnd); slotStart = slotStart.plus(slotDuration)) {
            Instant candidateStart = slotStart;
            Instant candidateEnd = slotStart.plus(slotDuration);
            boolean overlaps = busy.stream().anyMatch(appointment -> {
                Instant busyStart = appointment.getScheduledAt();
                Instant busyEnd = busyStart.plus(Duration.ofMinutes(appointment.getDurationMinutes()));
                return busyStart.isBefore(candidateEnd) && busyEnd.isAfter(candidateStart);
            });
            if (!overlaps) {
                freeSlots.add(candidateStart);
            }
        }
        return freeSlots;
    }
}
