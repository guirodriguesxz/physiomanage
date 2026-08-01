# PhysioManage

SaaS multi-tenant de gestão para clínicas de fisioterapia. Projeto de portfólio
back-end construído com Spring Boot 3 / Java 21, focado em mostrar práticas de
engenharia (autenticação JWT, isolamento multi-tenant, testes de integração,
containerização) aplicadas a um domínio de negócio real.

## Stack

- **Java 21 + Spring Boot 3** (Web, Security, Data JPA, Validation)
- **PostgreSQL** + **Flyway** (migrations versionadas, nunca `ddl-auto=update`)
- **JWT** (biblioteca `jjwt`) para autenticação stateless
- **springdoc-openapi** — Swagger UI automático
- **JUnit 5 + Testcontainers** — testes de integração com Postgres real
- **Docker / docker-compose** — ambiente reproduzível com 1 comando

## Como rodar localmente

### Opção 1 — Docker (recomendado, sobe tudo junto)

```bash
docker compose up --build
```

A API sobe em `http://localhost:8080`. O Postgres sobe junto, já com as
migrations do Flyway aplicadas automaticamente no start da aplicação.

### Opção 2 — Rodando local (Postgres à parte)

```bash
# suba um Postgres (ex: via docker)
docker run -d --name pg-physio -e POSTGRES_DB=physiomanage \
  -e POSTGRES_USER=physiomanage -e POSTGRES_PASSWORD=physiomanage \
  -p 5432:5432 postgres:16-alpine

# rode a aplicação
./mvnw spring-boot:run
```

### Documentação da API (Swagger)

Com a aplicação rodando: `http://localhost:8080/swagger-ui.html`

### Rodando os testes

```bash
./mvnw test
```

Os testes de integração usam Testcontainers e sobem um Postgres real em
container automaticamente — não é necessário ter um banco rodando à parte
para os testes (só é necessário ter o Docker disponível na máquina).

## Arquitetura

### Multi-tenancy

Cada clínica cadastrada é um *tenant* isolado. A estratégia escolhida foi
**schema compartilhado com coluna `clinic_id`** em cada tabela de negócio —
mais simples de operar do que schema-por-tenant ou banco-por-tenant, e
suficiente para o volume de dados desse tipo de aplicação.

O isolamento é reforçado na camada de aplicação, não confiado ao client:

1. O JWT carrega o `clinicId` do usuário autenticado como claim.
2. `JwtAuthenticationFilter` extrai essa claim a cada requisição e popula
   `ClinicContext` (um `ThreadLocal`).
3. Todo `Service` consulta `ClinicContext.getClinicId()` para filtrar e
   validar propriedade dos dados — nunca aceita um `clinicId` vindo do
   corpo da requisição.
4. Ao buscar um recurso por ID que pertence a outra clínica, a resposta é
   `404 Not Found` (não `403 Forbidden`), para não revelar a existência de
   dados de outro tenant.

### Autenticação

O login é feito por **CNPJ da clínica + e-mail + senha**, não só e-mail —
porque o e-mail de usuário é único apenas *dentro* de uma clínica (duas
clínicas diferentes podem ter um usuário cadastrado com o mesmo e-mail).
Por esse motivo, o fluxo de autenticação padrão do Spring Security
(`AuthenticationManager` / `UserDetailsService`, que assumem username
global-único) não se encaixava bem aqui — a validação de credenciais é
feita manualmente em `AuthService`, comparando a senha com BCrypt.

### Modelagem de domínio

| Entidade | Papel |
|---|---|
| `Clinic` | Tenant raiz do sistema |
| `User` | Autenticação (e-mail, senha, role) |
| `Patient` | Paciente da clínica; vínculo com `User` é opcional |
| `Professional` | Fisioterapeuta; vínculo com `User` é obrigatório |
| `Appointment` | Consulta (paciente + profissional + horário + status) |
| `TreatmentRecord` | Evolução clínica de uma consulta concluída (1:1 com `Appointment`) |
| `NotificationLog` | Registro do envio (simulado) de notificações de consulta |

### Prontuário (`TreatmentRecord`)

Cada consulta pode gerar no máximo um registro de evolução clínica —
relação 1:1 reforçada por `UNIQUE` em `treatment_records.appointment_id`.
Regras de negócio:

1. Só é possível registrar evolução para uma consulta com
   `status = COMPLETED` — não faz sentido documentar atendimento que
   ainda não aconteceu.
2. Só o profissional dono da consulta pode criar/editar o registro.
3. Tentar criar um segundo registro para a mesma consulta retorna
   `409 Conflict`; acessar um registro de outro profissional/clínica
   retorna `404` (mesmo racional de não vazar existência de dados de
   outro tenant usado em `Appointment`).

### Notificação assíncrona de consulta

Ao criar uma consulta, `AppointmentService.create` dispara
`NotificationService.notifyAppointmentCreated` — um método `@Async`
rodando num `ThreadPoolTaskExecutor` dedicado (ver `config/AsyncConfig`)
— para não acoplar o tempo de resposta do `POST /appointments` ao
processamento da notificação. O resultado (simulado; sem SMTP/fila de
mensagens neste estágio) fica em `notification_log`, com status `SENT`
ou `FAILED` (ex: paciente sem e-mail cadastrado), consultável via
`GET /api/v1/notifications`.

## Roadmap

- [x] **Fase 1** — Setup, autenticação JWT multi-tenant, CRUD de Clinic/User/Patient/Professional
- [x] **Fase 2** — Agendamento de consultas com validação de conflito de horário e disponibilidade do profissional
- [x] **Fase 3** — Prontuário/evolução clínica (`TreatmentRecord`), notificação assíncrona de consulta
- [ ] **Fase 4** — Cache de disponibilidade (Redis), CI (GitHub Actions), deploy

## Estrutura de pastas

```
src/main/java/com/physiomanage/
├── config/          # Security, JPA Auditing
├── controller/       # Endpoints REST
├── dto/              # Request/Response (records)
│   ├── request/
│   └── response/
├── entity/           # Entidades JPA
├── exception/         # Exceptions de negócio + handler global
├── repository/        # Spring Data JPA
├── security/          # JWT, ClinicContext, filtro de autenticação
└── service/            # Regras de negócio
```

## Testando a API manualmente

```bash
# 1. Cadastrar uma clínica (cria também o admin)
curl -X POST http://localhost:8080/api/v1/auth/register-clinic \
  -H "Content-Type: application/json" \
  -d '{
    "clinicName": "Fisio Vida",
    "cnpj": "12345678000199",
    "adminName": "Maria Silva",
    "adminEmail": "maria@fisiovida.com",
    "adminPassword": "senha12345"
  }'

# 2. Login (guarde o token retornado)
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "clinicCnpj": "12345678000199",
    "email": "maria@fisiovida.com",
    "password": "senha12345"
  }'

# 3. Cadastrar um paciente (use o token do passo anterior)
curl -X POST http://localhost:8080/api/v1/patients \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SEU_TOKEN_AQUI" \
  -d '{
    "name": "João Souza",
    "cpf": "12345678901",
    "birthDate": "1990-05-10",
    "phone": "11999999999"
  }'

# 4. Cadastrar um profissional (cria também o login dele)
curl -X POST http://localhost:8080/api/v1/professionals \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SEU_TOKEN_AQUI" \
  -d '{
    "name": "Dra. Ana Lima",
    "email": "ana@fisiovida.com",
    "password": "senha12345",
    "specialty": "Ortopedia",
    "licenseNumber": "CREFITO-12345"
  }'

# 5. Agendar uma consulta (use os IDs retornados nos passos 3 e 4)
curl -X POST http://localhost:8080/api/v1/appointments \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SEU_TOKEN_AQUI" \
  -d '{
    "patientId": "ID_DO_PACIENTE",
    "professionalId": "ID_DO_PROFISSIONAL",
    "scheduledAt": "2026-08-10T14:00:00Z",
    "durationMinutes": 50,
    "notes": "Primeira sessão"
  }'

# 6. Confirmar e concluir a consulta (transições de status)
curl -X PATCH http://localhost:8080/api/v1/appointments/ID_DA_CONSULTA/status \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SEU_TOKEN_AQUI" \
  -d '{"status": "CONFIRMED"}'

curl -X PATCH http://localhost:8080/api/v1/appointments/ID_DA_CONSULTA/status \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SEU_TOKEN_AQUI" \
  -d '{"status": "COMPLETED"}'

# 7. Registrar a evolução clínica (use o token do PROFISSIONAL, não do admin)
curl -X POST http://localhost:8080/api/v1/treatment-records \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer TOKEN_DO_PROFISSIONAL" \
  -d '{
    "appointmentId": "ID_DA_CONSULTA",
    "evolution": "Paciente relatou melhora da dor lombar, sem intercorrências."
  }'

# 8. Conferir o resultado da notificação assíncrona (token do admin)
curl "http://localhost:8080/api/v1/notifications?appointmentId=ID_DA_CONSULTA" \
  -H "Authorization: Bearer SEU_TOKEN_AQUI"
```
