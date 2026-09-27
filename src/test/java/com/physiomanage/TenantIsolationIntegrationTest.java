package com.physiomanage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physiomanage.dto.request.AppointmentRequest;
import com.physiomanage.dto.request.LoginRequest;
import com.physiomanage.dto.request.PatientRequest;
import com.physiomanage.dto.request.ProfessionalRequest;
import com.physiomanage.dto.request.RegisterClinicRequest;
import com.physiomanage.dto.request.TreatmentRecordRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prova, ponta a ponta via HTTP, a garantia central do sistema: um usuário
 * da Clínica B não lê, altera nem referencia nenhum dado da Clínica A,
 * mesmo conhecendo os IDs. Os testes unitários dos services já cobrem a
 * guarda de clinicId com mocks; aqui o caminho completo (JWT -> filtro ->
 * ClinicContext -> service -> query) roda contra Postgres real.
 *
 * Recurso de outra clínica sempre responde 404 (nunca 403), e listagens
 * filtradas por um ID de outra clínica voltam vazias — ver seção
 * "Multi-tenancy" do README.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationIntegrationTest {

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

    // Clínica A: dona dos dados
    private String adminA;
    private String patientA;
    private String professionalA;
    private String appointmentA;
    private String recordA;

    // Clínica B: tenta acessar os dados de A
    private String adminB;
    private String patientB;
    private String professionalB;
    private String professionalTokenB;

    private final Instant scheduledAt = Instant.now().plus(15, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    @BeforeEach
    void setUp(TestInfo testInfo) throws Exception {
        // Container compartilhado entre os testes: CNPJ/CPF/e-mail únicos por método
        String seed = String.format("%05d", Math.abs(testInfo.getTestMethod().orElseThrow().getName().hashCode()) % 100_000);

        String cnpjA = "10" + seed + "0001" + "001";
        adminA = registerClinic("Clínica A", cnpjA, "admin-a" + seed + "@a.com");
        patientA = createPatient(adminA, "111" + seed + "001");
        professionalA = createProfessional(adminA, "prof-a" + seed + "@a.com", "CREFITO-A" + seed);
        String professionalTokenA = login(cnpjA, "prof-a" + seed + "@a.com");
        appointmentA = createAppointment(adminA, patientA, professionalA);
        changeStatus(adminA, appointmentA, "CONFIRMED");
        changeStatus(adminA, appointmentA, "COMPLETED");
        recordA = extract(mockMvc.perform(post("/api/v1/treatment-records")
                        .header("Authorization", bearer(professionalTokenA))
                        .contentType("application/json")
                        .content(json(new TreatmentRecordRequest(UUID.fromString(appointmentA), "Evolução da clínica A"))))
                .andExpect(status().isCreated())
                .andReturn(), "id");

        String cnpjB = "20" + seed + "0001" + "002";
        adminB = registerClinic("Clínica B", cnpjB, "admin-b" + seed + "@b.com");
        patientB = createPatient(adminB, "222" + seed + "002");
        professionalB = createProfessional(adminB, "prof-b" + seed + "@b.com", "CREFITO-B" + seed);
        professionalTokenB = login(cnpjB, "prof-b" + seed + "@b.com");
    }

    @Test
    void patientsOfAnotherClinicAreInvisible() throws Exception {
        mockMvc.perform(get("/api/v1/patients/{id}", patientA).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/patients/{id}", patientA)
                        .header("Authorization", bearer(adminB))
                        .contentType("application/json")
                        .content(json(new PatientRequest("Nome Alterado", "99999999999", null, "11911111111", null, null, null))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/patients/{id}", patientA).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/patients").header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(patientB));

        // Nada do que B tentou afetou o paciente de A
        mockMvc.perform(get("/api/v1/patients/{id}", patientA).header("Authorization", bearer(adminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("João Souza"));
    }

    @Test
    void professionalsOfAnotherClinicAreInvisible() throws Exception {
        mockMvc.perform(get("/api/v1/professionals/{id}", professionalA).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/professionals/{id}", professionalA).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/professionals/{id}", professionalA).header("Authorization", bearer(adminA)))
                .andExpect(status().isOk());
    }

    @Test
    void appointmentsOfAnotherClinicAreInvisibleAndImmutable() throws Exception {
        mockMvc.perform(get("/api/v1/appointments/{id}", appointmentA).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/appointments/{id}", appointmentA)
                        .header("Authorization", bearer(adminB))
                        .contentType("application/json")
                        .content(appointmentJson(patientB, professionalB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentA)
                        .header("Authorization", bearer(adminB))
                        .contentType("application/json")
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isNotFound());

        // Filtrar a listagem de B por um paciente/profissional de A não vaza nada
        mockMvc.perform(get("/api/v1/appointments").param("patientId", patientA).header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/appointments").param("professionalId", professionalA).header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/appointments").header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/appointments/{id}", appointmentA).header("Authorization", bearer(adminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void cannotReferenceAnotherClinicsPatientOrProfessionalWhenScheduling() throws Exception {
        // Paciente de A com profissional de B
        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", bearer(adminB))
                        .contentType("application/json")
                        .content(appointmentJson(patientA, professionalB)))
                .andExpect(status().isNotFound());

        // Paciente de B com profissional de A
        mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", bearer(adminB))
                        .contentType("application/json")
                        .content(appointmentJson(patientB, professionalA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void treatmentRecordsOfAnotherClinicAreInvisible() throws Exception {
        mockMvc.perform(get("/api/v1/treatment-records/{id}", recordA).header("Authorization", bearer(adminB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/treatment-records/{id}", recordA).header("Authorization", bearer(professionalTokenB)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/treatment-records").param("patientId", patientA).header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Profissional de B tentando registrar evolução numa consulta de A
        mockMvc.perform(post("/api/v1/treatment-records")
                        .header("Authorization", bearer(professionalTokenB))
                        .contentType("application/json")
                        .content(json(new TreatmentRecordRequest(UUID.fromString(appointmentA), "Tentativa indevida"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void notificationsAndReportsOnlyReflectOwnClinic() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").param("appointmentId", appointmentA).header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        String from = LocalDate.now().toString();
        String to = LocalDate.now().plusDays(30).toString();

        mockMvc.perform(get("/api/v1/reports/summary").param("from", from).param("to", to)
                        .header("Authorization", bearer(adminB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/v1/reports/summary").param("from", from).param("to", to)
                        .header("Authorization", bearer(adminA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.byStatus.COMPLETED").value(1));
    }

    private String registerClinic(String name, String cnpj, String adminEmail) throws Exception {
        var request = new RegisterClinicRequest(name, cnpj, "Admin " + name, adminEmail, "senha12345");
        return extract(mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn(), "token");
    }

    private String createPatient(String token, String cpf) throws Exception {
        var request = new PatientRequest("João Souza", cpf, null, "11999999999", null, null, null);
        return extract(mockMvc.perform(post("/api/v1/patients")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn(), "id");
    }

    private String createProfessional(String token, String email, String license) throws Exception {
        var request = new ProfessionalRequest("Dra. Ana Lima", email, "senha12345", "Ortopedia", license);
        return extract(mockMvc.perform(post("/api/v1/professionals")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andReturn(), "id");
    }

    private String login(String cnpj, String email) throws Exception {
        return extract(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(json(new LoginRequest(cnpj, email, "senha12345"))))
                .andExpect(status().isOk())
                .andReturn(), "token");
    }

    private String createAppointment(String token, String patientId, String professionalId) throws Exception {
        return extract(mockMvc.perform(post("/api/v1/appointments")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content(appointmentJson(patientId, professionalId)))
                .andExpect(status().isCreated())
                .andReturn(), "id");
    }

    private void changeStatus(String token, String appointmentId, String newStatus) throws Exception {
        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"status\":\"" + newStatus + "\"}"))
                .andExpect(status().isOk());
    }

    private String appointmentJson(String patientId, String professionalId) throws Exception {
        return json(new AppointmentRequest(UUID.fromString(patientId), UUID.fromString(professionalId), scheduledAt, 50, null));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String extract(MvcResult result, String field) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get(field).asText();
    }
}
