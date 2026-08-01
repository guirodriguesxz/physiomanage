package com.physiomanage.repository;

import com.physiomanage.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Login é feito por e-mail + clínica, já que o e-mail é único
     * apenas dentro do escopo de uma clínica (ver constraint em User).
     */
    Optional<User> findByEmailAndClinicId(String email, UUID clinicId);

    boolean existsByEmailAndClinicId(String email, UUID clinicId);
}
