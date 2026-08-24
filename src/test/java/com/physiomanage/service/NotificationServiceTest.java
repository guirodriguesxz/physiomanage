package com.physiomanage.service;

import com.physiomanage.entity.Appointment;
import com.physiomanage.entity.Clinic;
import com.physiomanage.entity.NotificationLog;
import com.physiomanage.entity.NotificationStatus;
import com.physiomanage.repository.AppointmentRepository;
import com.physiomanage.repository.ClinicRepository;
import com.physiomanage.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unitário do branching SENT/FAILED da notificação (simulada) de
 * consulta — chama notifyAppointmentCreated() diretamente como método
 * Java comum; @Async/@TransactionalEventListener só importam quando o
 * bean é proxied pelo Spring, irrelevante pra testar a lógica em si.
 * O disparo real via evento após commit da transação continua coberto
 * por AppointmentNotificationIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationLogRepository notificationLogRepository;
    @Mock
    private ClinicRepository clinicRepository;
    @Mock
    private AppointmentRepository appointmentRepository;

    private NotificationService notificationService;

    private final UUID clinicId = UUID.randomUUID();
    private final UUID appointmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationLogRepository, clinicRepository, appointmentRepository);

        Clinic clinic = new Clinic();
        clinic.setId(clinicId);
        when(clinicRepository.getReferenceById(clinicId)).thenReturn(clinic);

        Appointment appointment = new Appointment();
        appointment.setId(appointmentId);
        when(appointmentRepository.getReferenceById(appointmentId)).thenReturn(appointment);
    }

    @Test
    void shouldMarkAsFailed_whenPatientHasNoEmail() {
        notificationService.notifyAppointmentCreated(commandWithEmail(null));

        NotificationLog saved = captureSaved();
        assertEquals(NotificationStatus.FAILED, saved.getStatus());
        assertEquals("Paciente sem e-mail cadastrado", saved.getMessage());
    }

    @Test
    void shouldMarkAsFailed_whenPatientEmailIsBlank() {
        notificationService.notifyAppointmentCreated(commandWithEmail("   "));

        assertEquals(NotificationStatus.FAILED, captureSaved().getStatus());
    }

    @Test
    void shouldMarkAsSent_whenPatientEmailIsPresent() {
        notificationService.notifyAppointmentCreated(commandWithEmail("paciente@example.com"));

        NotificationLog saved = captureSaved();
        assertEquals(NotificationStatus.SENT, saved.getStatus());
        assertEquals("paciente@example.com", saved.getRecipient());
        assertTrue(saved.getMessage().contains("Dra. Ana Lima"));
    }

    private NotificationLog captureSaved() {
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(captor.capture());
        return captor.getValue();
    }

    private AppointmentNotificationCommand commandWithEmail(String patientEmail) {
        return new AppointmentNotificationCommand(clinicId, appointmentId, patientEmail, "Dra. Ana Lima", Instant.now());
    }
}
