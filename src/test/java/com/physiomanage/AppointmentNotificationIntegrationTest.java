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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A notificação de consulta é processada em @Async (ver NotificationService),
 * então não aparece na resposta de POST /appointments — o teste precisa
 * dar polling em GET /notifications até o registro assíncrono ser
 * persistido, daí o uso de Awaitility em vez de checar a resposta direto.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentNotificationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("physiomanage_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String professionalId;

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        String seed = String.format("%06d", Math.abs(testInfo.getTestMethod().orElseThrow().getName().hashCode()) % 1_000_000);
        String clinicCnpj = "33444555" + seed;
        String professionalEmail = "ana" + seed + "@clinicanotifica.com";

        var registerRequest = new RegisterClinicRequest(
                "Clínica Notifica", clinicCnpj, "Admin Notifica", "admin" + seed + "@clinicanotifica.com", "senha12345"
        );
        adminToken = extract(mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "token");

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
    void shouldRecordSentNotificationWhenPatientHasEmail(TestInfo testInfo) throws Exception {
        String seed = String.format("%06d", Math.abs(testInfo.getTestMethod().orElseThrow().getName().hashCode()) % 1_000_000);
        var patientRequest = new PatientRequest("João Souza", "444" + seed + "01", null, "11999999999", "joao" + seed + "@paciente.com", null, null);
        String patientId = extract(mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(patientRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        String appointmentId = createAppointment(patientId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/notifications")
                                .param("appointmentId", appointmentId)
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[0].status").value("SENT"))
                        .andExpect(jsonPath("$.content[0].recipient").value("joao" + seed + "@paciente.com")));
    }

    @Test
    void shouldRecordFailedNotificationWhenPatientHasNoEmail(TestInfo testInfo) throws Exception {
        String seed = String.format("%06d", Math.abs(testInfo.getTestMethod().orElseThrow().getName().hashCode()) % 1_000_000);
        var patientRequest = new PatientRequest("Maria Sem Email", "555" + seed + "01", null, "11988888888", null, null, null);
        String patientId = extract(mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(patientRequest)))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        String appointmentId = createAppointment(patientId);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                mockMvc.perform(get("/api/v1/notifications")
                                .param("appointmentId", appointmentId)
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.content[0].status").value("FAILED")));
    }

    private String createAppointment(String patientId) throws Exception {
        var request = new AppointmentRequest(
                UUID.fromString(patientId), UUID.fromString(professionalId),
                Instant.now().plus(15, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS), 50, null
        );
        return extract(mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn(), "id");
    }

    private String extract(MvcResult result, String field) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get(field).asText();
    }
}
