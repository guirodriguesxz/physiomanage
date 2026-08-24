package com.physiomanage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre o núcleo da Fase 2: bloqueio de duplo-agendamento do mesmo
 * profissional (conflito de horário) e a máquina de estados da consulta.
 * Usa Postgres real via Testcontainers pelo mesmo motivo do
 * AuthFlowIntegrationTest — constraints e queries JPQL com FUNCTION()
 * precisam rodar contra o banco de verdade, não H2/mocks.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentFlowIntegrationTest {

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
        // Sem isso, o setUp() (que registra uma clínica por teste) esbarraria
        // no limite de produção de /auth/register-clinic — não estamos
        // testando rate limiting aqui, ver RateLimitIntegrationTest.
        registry.add("app.rate-limit.register-clinic.max-attempts", () -> 1000);
        registry.add("app.rate-limit.login.max-attempts", () -> 1000);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String patientId;
    private String professionalId;
    private String clinicCnpj;
    private String professionalEmail;

    /**
     * O container Postgres é compartilhado (static) entre os métodos de
     * teste da classe, então CNPJ/CPF/e-mail/registro precisam ser únicos
     * por teste — do contrário o segundo/terceiro teste bate em
     * DuplicateResourceException ao repetir o setUp.
     */
    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        String seed = String.format("%06d", Math.abs(testInfo.getTestMethod().orElseThrow().getName().hashCode()) % 1_000_000);
        clinicCnpj = "11222333" + seed;
        professionalEmail = "ana" + seed + "@clinicaagenda.com";

        var registerRequest = new RegisterClinicRequest(
                "Clínica Agenda", clinicCnpj, "Admin Agenda", "admin" + seed + "@clinicaagenda.com", "senha12345"
        );
        adminToken = extract(mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "token");

        var patientRequest = new PatientRequest("João Souza", "123" + seed + "01", null, "11999999999", null, null, null);
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
    }

    @Test
    void shouldRejectOverlappingAppointmentForSameProfessional() throws Exception {
        Instant scheduledAt = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(appointmentJson(scheduledAt, 50)))
                .andExpect(status().isCreated());

        // Começa 20 minutos depois do início da primeira (que dura 50min) -> se sobrepõe
        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(appointmentJson(scheduledAt.plus(20, ChronoUnit.MINUTES), 30)))
                .andExpect(status().isConflict());

        // Começa exatamente quando a primeira termina -> não se sobrepõe
        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(appointmentJson(scheduledAt.plus(50, ChronoUnit.MINUTES), 30)))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldFollowStatusStateMachine() throws Exception {
        Instant scheduledAt = Instant.now().plus(11, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        String appointmentId = extract(mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(appointmentJson(scheduledAt, 50)))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        // SCHEDULED -> CONFIRMED é válido
        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // CONFIRMED -> SCHEDULED é inválido (não existe no mapa de transições)
        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"status\":\"SCHEDULED\"}"))
                .andExpect(status().isConflict());

        // CONFIRMED -> COMPLETED é válido
        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        // COMPLETED é terminal
        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldAllowProfessionalToManageOnlyOwnAppointments() throws Exception {
        Instant scheduledAt = Instant.now().plus(12, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        String appointmentId = extract(mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(appointmentJson(scheduledAt, 50)))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        var loginRequest = new com.physiomanage.dto.request.LoginRequest(
                clinicCnpj, professionalEmail, "senha12345"
        );
        String professionalToken = extract(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn(), "token");

        mockMvc.perform(get("/api/v1/appointments/me")
                        .header("Authorization", "Bearer " + professionalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(appointmentId));

        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                        .header("Authorization", "Bearer " + professionalToken)
                        .contentType("application/json")
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isOk());
    }

    private String appointmentJson(Instant scheduledAt, int durationMinutes) throws Exception {
        var request = new com.physiomanage.dto.request.AppointmentRequest(
                java.util.UUID.fromString(patientId),
                java.util.UUID.fromString(professionalId),
                scheduledAt,
                durationMinutes,
                null
        );
        return objectMapper.writeValueAsString(request);
    }

    private String extract(MvcResult result, String field) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get(field).asText();
    }
}
