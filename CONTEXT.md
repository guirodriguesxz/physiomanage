# Contexto do projeto

PhysioManage — sistema de gestão para clínicas de fisioterapia (multi-tenant), portfólio público
(`guirodriguesxz/physiomanage`). Stack: Spring Boot, Postgres, Redis, Docker Compose, CI no GitHub Actions.

## Estado atual (HEAD `5926db1`, branch `main`, working tree limpo)

7 fases implementadas e revisadas, todas com CI verde:

- **Fases 1-3** (`1d79dbe`) — auth JWT multi-tenant, agendamento, prontuário + notificação assíncrona.
  Já revisadas e aprovadas pelo usuário antes da Fase 4 começar — não re-revisar do zero a menos que
  o usuário peça ou esses arquivos mudem.
- **Fase 4** (`0603610`, fix em `df21982`) — cache de disponibilidade Redis, CI, deploy prep.
- **Fase 5** (`1bf0de0`, `cc1b134`, `2e24489`, `a87a82e`) — refresh token com rotação/revogação (Redis,
  TTL 7d, access JWT 15min), rate limiting em `/auth/login` e `/auth/register-clinic` (Redis, fail-open),
  logging JSON estruturado + correlation ID, métricas Micrometer/Prometheus.
- **Fase 6** (`17cad5f`) — cobertura de testes unitários (Mockito, sem Spring context/Docker). Suite: 64
  testes (34 unit + 30 integration/etc.).
- **Fase 7** (`5926db1`) — dashboard/relatórios (`/reports/summary`, `/reports/professionals-productivity`,
  ADMIN-only).

## Decisões e motivos que importam

- Usuário quer **zero falhas de lógica/segurança** antes de tornar o repo público de vez — por isso
  cada fase passou por revisão de código explícita antes de avançar.
- `RateLimiter`/`RefreshTokenService` foram deixados **sem** unit tests de propósito — já cobertos por
  integration tests com Redis real, que é o que importa pra lógica atômica de Lua/rotação deles.

## Gotchas descobertos (não óbvios, valem a pena lembrar)

- `docker info` falhar não significa Docker indisponível — rodar `open -a Docker` e esperar antes de
  desistir de testes de integração locais.
- **Sempre validar localmente** (`./mvnw verify` + smoke test manual com `spring-boot:run` contra
  Postgres/Redis reais) antes de dar push — nesta sessão o CI quebrou duas vezes seguidas por pular
  essa etapa.
- Bug de app inteiro encontrado só por teste manual ao vivo (não por testes automatizados): negação de
  `@PreAuthorize` retornava `500` em vez de `403` desde a Fase 1 (faltava handler pra
  `AccessDeniedException` no `GlobalExceptionHandler`) — corrigido na Fase 7. Nenhum teste integrado
  batia um role-mismatch real por HTTP até então.
- `JwtService.isTokenValid` lançava `ExpiredJwtException` em vez de retornar `false` pra token expirado
  (bug de contrato, não explorável na prática porque o único caller já tinha try/catch) — corrigido na
  Fase 6 ao escrever `JwtServiceTest`.

Histórico completo: `git log --oneline` no repo. Detalhes de cada bug/commit não estão duplicados aqui
de propósito — ver as mensagens de commit acima.
