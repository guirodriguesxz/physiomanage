package com.physiomanage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.physiomanage.dto.request.LoginRequest;
import com.physiomanage.dto.request.RefreshTokenRequest;
import com.physiomanage.dto.request.RegisterClinicRequest;
import org.junit.jupiter.api.Test;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração ponta a ponta: sobe um Postgres real em container
 * (Testcontainers) em vez de usar H2/mocks, para garantir que as
 * constraints do banco (unique, foreign key) e as migrations do Flyway
 * realmente funcionam como esperado — não só a lógica Java isolada.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

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
        // Não é o foco desta classe (ver RateLimitIntegrationTest) — sem
        // isso, os vários register-clinic/login espalhados pelos testes
        // aqui esbarrariam no limite de produção.
        registry.add("app.rate-limit.register-clinic.max-attempts", () -> 1000);
        registry.add("app.rate-limit.login.max-attempts", () -> 1000);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRegisterClinicAndLoginSuccessfully() throws Exception {
        var registerRequest = new RegisterClinicRequest(
                "Clínica Teste",
                "12345678000199",
                "Admin Teste",
                "admin@clinicateste.com",
                "senha12345"
        );

        mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        var loginRequest = new LoginRequest(
                "12345678000199",
                "admin@clinicateste.com",
                "senha12345"
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void shouldRejectLoginWithWrongPassword() throws Exception {
        var registerRequest = new RegisterClinicRequest(
                "Clínica Teste 2",
                "98765432000188",
                "Admin Teste 2",
                "admin2@clinicateste.com",
                "senha12345"
        );

        mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        var wrongLogin = new LoginRequest(
                "98765432000188",
                "admin2@clinicateste.com",
                "senhaErrada"
        );

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(wrongLogin)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRefreshRotateAndRejectReuseOfOldToken() throws Exception {
        var registerRequest = new RegisterClinicRequest(
                "Clínica Refresh",
                "11122233000144",
                "Admin Refresh",
                "admin@clinicarefresh.com",
                "senha12345"
        );

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String originalRefreshToken = readRefreshToken(registerResult);

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(originalRefreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn();

        String rotatedRefreshToken = readRefreshToken(refreshResult);

        // rotação: o token usado no refresh não pode ser reaproveitado
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(originalRefreshToken))))
                .andExpect(status().isUnauthorized());

        // o novo token emitido pela rotação continua válido
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(rotatedRefreshToken))))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectRefreshWithGarbageToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest("token-que-nunca-existiu"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRevokeRefreshTokenOnLogout() throws Exception {
        var registerRequest = new RegisterClinicRequest(
                "Clínica Logout",
                "55566677000188",
                "Admin Logout",
                "admin@clinicalogout.com",
                "senha12345"
        );

        MvcResult registerResult = mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String refreshToken = readRefreshToken(registerResult);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken))))
                .andExpect(status().isUnauthorized());

        // logout de um token já revogado continua não sendo erro (idempotente)
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken))))
                .andExpect(status().isNoContent());
    }

    private String readRefreshToken(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("refreshToken").asText();
    }
}
