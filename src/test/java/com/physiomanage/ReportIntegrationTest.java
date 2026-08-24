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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre a Fase 7: agregação real via Postgres (GROUP BY, SUM/CASE WHEN,
 * ORDER BY alias — comportamento específico do provider JPA, não confiável
 * em mock) e a autorização ADMIN-only dos relatórios.
 *
 * O teste shouldForbidNonAdminFromViewingReports também serve de teste de
 * regressão pra um bug encontrado manualmente durante o desenvolvimento
 * desta fase: @PreAuthorize negado lança AuthorizationDeniedException, que
 * não tinha handler dedicado em GlobalExceptionHandler e virava 500 em vez
 * de 403 — bug pré-existente em toda a aplicação (não só nos relatórios),
 * só descoberto porque este foi o primeiro endpoint testado manualmente
 * contra um papel sem permissão.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ReportIntegrationTest {

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
    private String professionalEmail;
    private String patientId;
    private String professionalId;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        String seed = String.format("%06d", Math.abs(testInfo.getTestMethod().orElseThrow().getName().hashCode()) % 1_000_000);
        clinicCnpj = "44455566" + seed;
        professionalEmail = "profreport" + seed + "@clinicareports.com";

        var registerRequest = new RegisterClinicRequest(
                "Clínica Reports", clinicCnpj, "Admin Reports", "admin" + seed + "@clinicareports.com", "senha12345"
        );
        adminToken = extract(mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "token");

        var patientRequest = new PatientRequest("Paciente Reports", "999" + seed + "01", null, "11999999999", null, null, null);
        patientId = extract(mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(patientRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        var professionalRequest = new ProfessionalRequest(
                "Dra. Report", professionalEmail, "senha12345", "Ortopedia", "CREFITO-" + seed
        );
        professionalId = extract(mockMvc.perform(post("/api/v1/professionals")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(professionalRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "id");
    }

    @Test
    void shouldSummarizeAppointmentsByStatusWithRates() throws Exception {
        Instant base = Instant.now().plus(15, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        String completed = createAppointment(base);
        String cancelled = createAppointment(base.plus(2, ChronoUnit.HOURS));
        String noShow = createAppointment(base.plus(4, ChronoUnit.HOURS));
        createAppointment(base.plus(6, ChronoUnit.HOURS)); // fica SCHEDULED

        moveStatus(completed, "CONFIRMED");
        moveStatus(completed, "COMPLETED");
        moveStatus(cancelled, "CONFIRMED");
        moveStatus(cancelled, "CANCELLED");
        moveStatus(noShow, "CONFIRMED");
        moveStatus(noShow, "NO_SHOW");

        LocalDate from = LocalDate.ofInstant(base, java.time.ZoneOffset.UTC).minusDays(1);
        LocalDate to = LocalDate.ofInstant(base, java.time.ZoneOffset.UTC).plusDays(1);

        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.byStatus.COMPLETED").value(1))
                .andExpect(jsonPath("$.byStatus.CANCELLED").value(1))
                .andExpect(jsonPath("$.byStatus.NO_SHOW").value(1))
                .andExpect(jsonPath("$.byStatus.SCHEDULED").value(1))
                .andExpect(jsonPath("$.byStatus.CONFIRMED").value(0))
                .andExpect(jsonPath("$.noShowRate").value(0.25))
                .andExpect(jsonPath("$.cancellationRate").value(0.25));
    }

    @Test
    void shouldReportProfessionalProductivity() throws Exception {
        Instant base = Instant.now().plus(16, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        String completed = createAppointment(base);
        moveStatus(completed, "CONFIRMED");
        moveStatus(completed, "COMPLETED");

        LocalDate from = LocalDate.ofInstant(base, java.time.ZoneOffset.UTC).minusDays(1);
        LocalDate to = LocalDate.ofInstant(base, java.time.ZoneOffset.UTC).plusDays(1);

        mockMvc.perform(get("/api/v1/reports/professionals-productivity")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].professionalId").value(professionalId))
                .andExpect(jsonPath("$[0].completed").value(1))
                .andExpect(jsonPath("$[0].total").value(1));
    }

    @Test
    void shouldRejectInvalidDateRange() throws Exception {
        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-09-01")
                        .param("to", "2026-08-01")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldForbidNonAdminFromViewingReports() throws Exception {
        var loginRequest = new LoginRequest(clinicCnpj, professionalEmail, "senha12345");
        String professionalToken = extract(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn(), "token");

        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31")
                        .header("Authorization", "Bearer " + professionalToken))
                .andExpect(status().isForbidden());
    }

    private String createAppointment(Instant scheduledAt) throws Exception {
        var request = new AppointmentRequest(UUID.fromString(patientId), UUID.fromString(professionalId), scheduledAt, null, null);
        return extract(mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn(), "id");
    }

    private void moveStatus(String appointmentId, String newStatus) throws Exception {
        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"status\":\"" + newStatus + "\"}"))
                .andExpect(status().isOk());
    }

    private String extract(MvcResult result, String field) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get(field).asText();
    }
}
