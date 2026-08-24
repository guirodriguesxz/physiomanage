# PhysioManage

SaaS multi-tenant de gestão para clínicas de fisioterapia. Projeto de portfólio
back-end construído com Spring Boot 3 / Java 21, focado em mostrar práticas de
engenharia (autenticação JWT, isolamento multi-tenant, testes de integração,
containerização) aplicadas a um domínio de negócio real.

## Stack

- **Java 21 + Spring Boot 3** (Web, Security, Data JPA, Validation)
- **PostgreSQL** + **Flyway** (migrations versionadas, nunca `ddl-auto=update`)
- **Redis** — cache de disponibilidade de horários
- **JWT** (biblioteca `jjwt`) para autenticação stateless
- **springdoc-openapi** — Swagger UI automático
- **JUnit 5 + Testcontainers** — testes de integração com Postgres e Redis reais
- **Docker / docker-compose** — ambiente reproduzível com 1 comando
- **GitHub Actions** — CI (build + testes a cada push/PR)

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

### Refresh token e revogação (Redis)

O access token (JWT) tem vida curta (15min por padrão,
`app.jwt.expiration-ms`) — expira rápido de propósito, porque um JWT não
pode ser revogado antes de expirar (é stateless por definição). A sessão
de fato é sustentada por um **refresh token opaco** (`RefreshTokenService`),
armazenado no Redis como `hash SHA-256 do token → userId`, com TTL de
7 dias (`app.jwt.refresh-expiration-ms`). Só o hash é persistido — nunca
o valor bruto — mesmo racional de guardar senha com BCrypt: um dump do
Redis não deve entregar tokens utilizáveis.

- `POST /api/v1/auth/refresh` troca um refresh token válido por um novo
  par access+refresh — **com rotação**: o token usado é sempre invalidado,
  mesmo se válido. Isso limita o estrago de um token vazado a uma única
  troca, e um token consumido reaparecendo é sinal de comprometimento.
- `POST /api/v1/auth/logout` revoga o refresh token informado
  (idempotente: chamar de novo, ou com um token já revogado, não é erro).

Diferente do cache de disponibilidade (que é *fail-open*: Redis fora do
ar → recalcula na hora), a revogação é **fail-closed** — é a única
garantia real de logout que o sistema tem, então uma falha no Redis aqui
propaga erro em vez de fingir sucesso.

### Rate limiting (`/auth/login`, `/auth/register-clinic`)

`RateLimitFilter` limita tentativas por IP nessas duas rotas — mitigação
de brute-force de senha e de spam de criação de tenant. O contador é uma
janela fixa no Redis (`RateLimiter`), incrementada com `INCR` + `EXPIRE`
atômicos (script Lua, evita a chave ficar sem TTL se o processo morrer
entre os dois comandos). Ao estourar o limite (default 5/min login,
3/min cadastro — `app.rate-limit.*`), a resposta é `429` com header
`Retry-After`.

Limita só por IP, não por CNPJ/e-mail do corpo — ler o corpo antes do
controller processar exigiria complexidade adicional (request wrapper)
que não se paga aqui. E é *fail-open*, como o cache de disponibilidade:
Redis fora do ar não pode derrubar login/cadastro, já que essa é só uma
camada extra de defesa (a senha já é validada com BCrypt de qualquer
forma).

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

### Disponibilidade e cache (Redis)

`GET /api/v1/professionals/{id}/availability?date=YYYY-MM-DD` devolve os
horários livres do profissional num dia, a partir de um horário de
funcionamento fixo da clínica (`app.availability.*`, default 08h-18h,
slots de 50min — mesmo valor da duração padrão de consulta) menos as
consultas ativas já agendadas naquele dia.

O resultado é cacheado no Redis (`AvailabilityCache`), chave
`clinicId:professionalId:date`, TTL de 5min por padrão
(`app.cache.availability-ttl-seconds`). Criar, reagendar ou mudar o
status de uma consulta invalida a entrada correspondente
(`AppointmentService`) — o TTL existe só como rede de segurança, não
como mecanismo principal de invalidação. O acesso ao cache é feito
manualmente (não via `@Cacheable`/`@CacheEvict`): no reagendamento, a
data *antiga* da consulta só é conhecida depois de carregar a entidade
do banco, o que não dá pra expressar em SpEL de anotação. Toda operação
de cache é *fail-open* — se o Redis cair, loga um warning e recalcula
na hora, já que cache é otimização e não pode derrubar o agendamento.

## Roadmap

- [x] **Fase 1** — Setup, autenticação JWT multi-tenant, CRUD de Clinic/User/Patient/Professional
- [x] **Fase 2** — Agendamento de consultas com validação de conflito de horário e disponibilidade do profissional
- [x] **Fase 3** — Prontuário/evolução clínica (`TreatmentRecord`), notificação assíncrona de consulta
- [x] **Fase 4** — Cache de disponibilidade (Redis), CI (GitHub Actions), deploy
- [ ] **Fase 5** — Observabilidade e hardening: refresh token + revogação (Redis) ✅,
  rate limiting em `/auth/login` e `/auth/register-clinic` (Redis) ✅,
  logging estruturado + correlation ID, métricas (Micrometer/Prometheus)

## CI

Todo push/PR para `main` roda `.github/workflows/ci.yml`: build + suíte
completa de testes (`./mvnw -B verify`) num runner `ubuntu-latest`, que já
vem com Docker — necessário porque os testes de integração sobem Postgres
e Redis reais via Testcontainers.

## Deploy

A imagem Docker (`Dockerfile`, multi-stage build) e o `docker-compose.yml`
já cobrem o ambiente completo (app + Postgres + Redis) — qualquer
plataforma que rode um `Dockerfile` e forneça Postgres/Redis gerenciados
serve (Render, Railway, Fly.io etc.), sem exigir nenhum vendor específico.

Variáveis de ambiente esperadas em produção:

| Variável | Obrigatória | Descrição |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Sim | Conexão com o Postgres |
| `JWT_SECRET` | Sim | Chave de assinatura dos JWTs (mín. 256 bits) — **trocar o default de dev** |
| `REDIS_HOST`, `REDIS_PORT` | Sim | Conexão com o Redis (cache de disponibilidade) |
| `CORS_ALLOWED_ORIGINS` | Sim | Origens do front-end, separadas por vírgula — nunca `*` |
| `SERVER_PORT` | Não (default `8080`) | Porta HTTP da aplicação |
| `JWT_EXPIRATION_MS` | Não (default 15min) | Validade do access token |
| `JWT_REFRESH_EXPIRATION_MS` | Não (default 7 dias) | Validade do refresh token |
| `AVAILABILITY_CACHE_TTL_SECONDS` | Não (default 300) | TTL do cache de disponibilidade |
| `RATE_LIMIT_LOGIN_MAX_ATTEMPTS` / `_WINDOW_SECONDS` | Não (default 5 / 60) | Limite de tentativas de login por IP |
| `RATE_LIMIT_REGISTER_MAX_ATTEMPTS` / `_WINDOW_SECONDS` | Não (default 3 / 60) | Limite de cadastro de clínica por IP |

A aplicação expõe `GET /actuator/health` (liberado sem autenticação em
`SecurityConfig`) para healthcheck da plataforma de deploy — é o mesmo
endpoint usado no healthcheck do serviço `app` no `docker-compose.yml`.

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

# 9. Conferir a disponibilidade do profissional num dia (cacheada no Redis)
curl "http://localhost:8080/api/v1/professionals/ID_DO_PROFISSIONAL/availability?date=2026-08-10" \
  -H "Authorization: Bearer SEU_TOKEN_AQUI"

# 10. Renovar o access token quando expirar, usando o refreshToken do login/register
#     (rotação: o refreshToken usado aqui deixa de valer, use o novo devolvido na resposta)
curl -X POST http://localhost:8080/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "SEU_REFRESH_TOKEN_AQUI"}'

# 11. Logout (revoga o refresh token — o access token em uso continua valendo até expirar)
curl -X POST http://localhost:8080/api/v1/auth/logout \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "SEU_REFRESH_TOKEN_AQUI"}'
```
