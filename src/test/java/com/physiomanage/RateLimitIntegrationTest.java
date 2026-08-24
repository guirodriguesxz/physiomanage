package com.physiomanage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.physiomanage.dto.request.LoginRequest;
import com.physiomanage.dto.request.RegisterClinicRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Isolada das outras classes de teste de propósito: aqui os limites de
 * /auth/login e /auth/register-clinic são baixados via
 * @DynamicPropertySource especificamente para exercitar o 429 de forma
 * determinística, sem depender do valor de produção (que é alto demais
 * pra estourar em poucos requests de teste).
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

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
        registry.add("app.rate-limit.register-clinic.max-attempts", () -> 2);
        registry.add("app.rate-limit.register-clinic.window-seconds", () -> 60);
        registry.add("app.rate-limit.login.max-attempts", () -> 3);
        registry.add("app.rate-limit.login.window-seconds", () -> 60);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldBlockRegisterClinicAfterExceedingLimitPerIp() throws Exception {
        // dentro do limite (2/60s): passam e chegam na regra de negócio
        for (int i = 1; i <= 2; i++) {
            var request = new RegisterClinicRequest(
                    "Clínica RL " + i, "9000000" + i + "000199", "Admin " + i,
                    "admin" + i + "@clinicarl.com", "senha12345"
            );
            mockMvc.perform(post("/api/v1/auth/register-clinic")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        // 3ª tentativa no mesmo IP, ainda dentro da mesma janela: bloqueada
        // antes de chegar na regra de negócio (nem importa se o CNPJ é novo)
        var thirdRequest = new RegisterClinicRequest(
                "Clínica RL 3", "90000003000199", "Admin 3",
                "admin3@clinicarl.com", "senha12345"
        );
        mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(thirdRequest)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void shouldBlockLoginAfterExceedingLimitPerIp() throws Exception {
        var registerRequest = new RegisterClinicRequest(
                "Clínica Login RL", "90000010000199", "Admin RL",
                "admin@clinicaloginrl.com", "senha12345"
        );
        mockMvc.perform(post("/api/v1/auth/register-clinic")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        var wrongLogin = new LoginRequest("90000010000199", "admin@clinicaloginrl.com", "senhaErrada");

        // dentro do limite (3/60s): erra a senha, mas chega na regra de negócio (401)
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(wrongLogin)))
                    .andExpect(status().isUnauthorized());
        }

        // 4ª tentativa no mesmo IP, mesma janela: bloqueada pelo rate limit, não mais 401
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(wrongLogin)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }
}
