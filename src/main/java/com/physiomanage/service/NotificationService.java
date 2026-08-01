package com.physiomanage.service;

import com.physiomanage.entity.NotificationLog;
import com.physiomanage.entity.NotificationStatus;
import com.physiomanage.repository.AppointmentRepository;
import com.physiomanage.repository.ClinicRepository;
import com.physiomanage.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Processa o envio (simulado) de notificações de consulta fora da thread
 * da requisição HTTP original — ver AppointmentService.create, que
 * publica o AppointmentNotificationCommand como evento ainda dentro da
 * sua transação. @TransactionalEventListener(AFTER_COMMIT) garante que
 * este método só roda depois que a consulta foi de fato commitada — sem
 * isso, a thread @Async poderia tentar gravar notification_log
 * referenciando um appointment_id ainda invisível para outras
 * transações (violação de FK / exceção assíncrona silenciosamente
 * descartada). getReferenceById evita um SELECT extra: só precisamos da
 * FK, não dos dados da entidade.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationLogRepository notificationLogRepository;
    private final ClinicRepository clinicRepository;
    private final AppointmentRepository appointmentRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyAppointmentCreated(AppointmentNotificationCommand command) {
        NotificationLog log = new NotificationLog();
        log.setClinic(clinicRepository.getReferenceById(command.clinicId()));
        log.setAppointment(appointmentRepository.getReferenceById(command.appointmentId()));
        log.setRecipient(command.patientEmail());

        if (command.patientEmail() == null || command.patientEmail().isBlank()) {
            log.setStatus(NotificationStatus.FAILED);
            log.setMessage("Paciente sem e-mail cadastrado");
        } else {
            log.setStatus(NotificationStatus.SENT);
            log.setMessage("Consulta agendada para " + command.scheduledAt() + " com " + command.professionalName());
        }

        notificationLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public Page<NotificationLog> listByClinic(UUID clinicId, Pageable pageable) {
        return notificationLogRepository.findByClinicId(clinicId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<NotificationLog> listByAppointment(UUID clinicId, UUID appointmentId, Pageable pageable) {
        return notificationLogRepository.findByClinicIdAndAppointmentId(clinicId, appointmentId, pageable);
    }
}
