package com.physiomanage.repository;

import com.physiomanage.entity.NotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    Page<NotificationLog> findByClinicId(UUID clinicId, Pageable pageable);

    Page<NotificationLog> findByClinicIdAndAppointmentId(UUID clinicId, UUID appointmentId, Pageable pageable);
}
