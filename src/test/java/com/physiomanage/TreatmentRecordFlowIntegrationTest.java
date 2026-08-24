package com.physiomanage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physiomanage.dto.request.AppointmentRequest;
import com.physiomanage.dto.request.LoginRequest;
import com.physiomanage.dto.request.PatientRequest;
import com.physiomanage.dto.request.ProfessionalRequest;
import com.physiomanage.dto.request.RegisterClinicRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre a regra central da Fase 3: prontuário só pode ser criado para
 * consulta COMPLETED, só pelo profissional dono dela, e no máximo um por
 * consulta. Usa Postgres real via Testcontainers pelo mesmo motivo dos
 * demais testes de fluxo (constraint UNIQUE de treatment_records.appointment_id
 * precisa rodar contra o banco de verdade).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TreatmentRecordFlowIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("physiomanage_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.rate-limit.register-clinic.max-attempts", () -> 1000);
        registry.add("app.rate-limit.login.max-attempts", () -> 1000);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String clinicCnpj;
    private String patientId;
    private String professionalId;
    private String professionalToken;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        String seed = String.format("%06d", Math.abs(testInfo.getTestMethod().orElseThrow().getName().hashCode()) % 1_000_000);
        clinicCnpj = "22333444" + seed;
        String professionalEmail = "ana" + seed + "@clinicaevolucao.com";

        var registerRequest = new RegisterClinicRequest(
                "Clínica Evolução", clinicCnpj, "Admin Evolução", "admin" + seed + "@clinicaevolucao.com", "senha12345"
        );
        adminToken = extract(mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "token");

        var patientRequest = new PatientRequest("João Souza", "321" + seed + "01", null, "11999999999", null, null, null);
        patientId = extract(mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(patientRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        var professionalRequest = new ProfessionalRequest(
                "Dra. Ana Lima", professionalEmail, "senha12345", "Ortopedia", "CREFITO-" + seed
        );
        professionalId = extract(mockMvc.perform(post("/api/v1/professionals")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(professionalRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        var loginRequest = new LoginRequest(clinicCnpj, professionalEmail, "senha12345");
        professionalToken = extract(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn(), "token");
    }

    @Test
    void shouldFollowTreatmentRecordLifecycle() throws Exception {
        String appointmentId = createAppointment(Instant.now().plus(10, ChronoUnit.DAYS));

        // Consulta ainda SCHEDULED -> prontuário rejeitado
        mockMvc.perform(post("/api/v1/treatment-records")
                        .header("Authorization", "Bearer " + professionalToken)
                        .contentType("application/json")
                        .content(treatmentRecordJson(appointmentId, "Evolução inicial")))
                .andExpect(status().isConflict());

        confirmAndComplete(appointmentId);

        // Consulta COMPLETED -> prontuário aceito
        mockMvc.perform(post("/api/v1/treatment-records")
                        .header("Authorization", "Bearer " + professionalToken)
                        .contentType("application/json")
                        .content(treatmentRecordJson(appointmentId, "Paciente evoluiu bem, sem dor residual")))
                .andExpect(status().isCreated());

        // Segundo registro para a mesma consulta -> duplicado
        mockMvc.perform(post("/api/v1/treatment-records")
                        .header("Authorization", "Bearer " + professionalToken)
                        .contentType("application/json")
                        .content(treatmentRecordJson(appointmentId, "Segunda tentativa")))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldRejectTreatmentRecordFromNonOwningProfessional() throws Exception {
        String appointmentId = createAppointment(Instant.now().plus(11, ChronoUnit.DAYS));
        confirmAndComplete(appointmentId);

        String otherEmail = "outro" + clinicCnpj + "@clinicaevolucao.com";
        var otherProfessionalRequest = new ProfessionalRequest(
                "Dr. Bruno Alves", otherEmail, "senha12345", "Neurologia", "CREFITO-X" + clinicCnpj
        );
        mockMvc.perform(post("/api/v1/professionals")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(otherProfessionalRequest)))
                .andExpect(status().isCreated());

        String otherToken = extract(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new LoginRequest(clinicCnpj, otherEmail, "senha12345"))))
                .andExpect(status().isOk())
                .andReturn(), "token");

        mockMvc.perform(post("/api/v1/treatment-records")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType("application/json")
                        .content(treatmentRecordJson(appointmentId, "Tentativa de outro profissional")))
                .andExpect(status().isNotFound());
    }

    private String createAppointment(Instant scheduledAt) throws Exception {
        var request = new AppointmentRequest(
                java.util.UUID.fromString(patientId), java.util.UUID.fromString(professionalId),
                scheduledAt.truncatedTo(ChronoUnit.SECONDS), 50, null
        );
        return extract(mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn(), "id");
    }

    private void confirmAndComplete(String appointmentId) throws Exception {
        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());
    }

    private String treatmentRecordJson(String appointmentId, String evolution) throws Exception {
        var request = new com.physiomanage.dto.request.TreatmentRecordRequest(java.util.UUID.fromString(appointmentId), evolution);
        return objectMapper.writeValueAsString(request);
    }

    private String extract(MvcResult result, String field) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get(field).asText();
    }
}
