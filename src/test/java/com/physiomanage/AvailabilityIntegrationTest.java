package com.physiomanage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physiomanage.dto.request.AppointmentRequest;
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
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cobre a Fase 4: geração de slots livres a partir do horário fixo de
 * funcionamento (default 08h-18h, slots de 50min -> 12 slots/dia) menos
 * consultas ativas, e a invalidação do cache Redis quando uma consulta é
 * criada (senão o segundo GET voltaria a lista cheia, cacheada antes do
 * agendamento). Postgres e Redis reais via Testcontainers pelo mesmo
 * motivo dos outros testes de integração — comportamento de
 * serialização/expiração do cache não é confiável em mock.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AvailabilityIntegrationTest {

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
    private String patientId;
    private String professionalId;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        String seed = String.format("%06d", Math.abs(testInfo.getTestMethod().orElseThrow().getName().hashCode()) % 1_000_000);

        var registerRequest = new RegisterClinicRequest(
                "Clínica Disponibilidade", "22333444" + seed, "Admin Disp", "admin" + seed + "@clinicadisp.com", "senha12345"
        );
        adminToken = extract(mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "token");

        var patientRequest = new PatientRequest("Paciente Teste", "999" + seed + "01", null, "11999999999", null, null, null);
        patientId = extract(mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(patientRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        var professionalRequest = new ProfessionalRequest(
                "Dr. Disponível", "prof" + seed + "@clinicadisp.com", "senha12345", "Ortopedia", "CREFITO-" + seed
        );
        professionalId = extract(mockMvc.perform(post("/api/v1/professionals")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(professionalRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "id");
    }

    @Test
    void shouldReturnAllSlotsWhenProfessionalHasNoAppointments() throws Exception {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(20);

        mockMvc.perform(get("/api/v1/professionals/{id}/availability", professionalId)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableSlots.length()").value(12))
                .andExpect(jsonPath("$.availableSlots[0]").value(date.atTime(8, 0).atZone(ZoneOffset.UTC).toInstant().toString()));
    }

    @Test
    void shouldExcludeBookedSlotAndInvalidateCache() throws Exception {
        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(21);
        Instant firstSlot = date.atTime(8, 0).atZone(ZoneOffset.UTC).toInstant();

        // Primeira chamada popula o cache com os 12 slots livres.
        mockMvc.perform(get("/api/v1/professionals/{id}/availability", professionalId)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableSlots.length()").value(12));

        var appointmentRequest = new AppointmentRequest(
                UUID.fromString(patientId), UUID.fromString(professionalId), firstSlot, 50, null
        );
        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(appointmentRequest)))
                .andExpect(status().isCreated());

        // Se a invalidação não tivesse funcionado, viria a resposta cacheada
        // (12 slots, incluindo o que acabou de ser agendado).
        Instant secondSlot = date.atTime(8, 50).atZone(ZoneOffset.UTC).toInstant();
        mockMvc.perform(get("/api/v1/professionals/{id}/availability", professionalId)
                        .header("Authorization", "Bearer " + adminToken)
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableSlots.length()").value(11))
                .andExpect(jsonPath("$.availableSlots[0]").value(secondSlot.toString()));
    }

    @Test
    void shouldReturnNotFoundForProfessionalFromAnotherClinic() throws Exception {
        var otherClinicRequest = new RegisterClinicRequest(
                "Outra Clínica", "55666777000188", "Admin Outra", "outraadmin@outraclinica.com", "senha12345"
        );
        String otherAdminToken = extract(mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(otherClinicRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "token");

        LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(22);

        mockMvc.perform(get("/api/v1/professionals/{id}/availability", professionalId)
                        .header("Authorization", "Bearer " + otherAdminToken)
                        .param("date", date.toString()))
                .andExpect(status().isNotFound());
    }

    private String extract(MvcResult result, String field) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get(field).asText();
    }
}
