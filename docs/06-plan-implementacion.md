# 06 — Plan de implementación

> Basado en el roadmap de [plan-proyecto.md](plan-proyecto.md), con los cambios de [02-TRD](02-TRD.md) y [05-esquema-backend](05-esquema-backend.md). Ante diferencias, prevalecen los documentos 01 a 07.
> **Regla de trabajo:** una fase por vez, en orden. No se pasa a la siguiente hasta cumplir su criterio de "terminado" y verificarlo con [07-criterios-aceptacion](07-criterios-aceptacion.md). Cada fase deja el proyecto funcionando y testeable, y se commitea.
> Comandos en PowerShell. Rutas relativas a la raíz del repo (`C:\Programacion\fintech-wallet`).
> **⚠️ SUPUESTO** = decisión propia.

## Convenciones de todas las fases
- Código y API en inglés; UI y documentos en español. Paquetes por funcionalidad. Montos `BigDecimal` / `NUMERIC(19,4)`; nunca `double`/`float`.
- Cada fase termina con: tests de la fase en verde (`.\mvnw.cmd verify` en el servicio, o `npm test`), commit con mensaje claro y el criterio de terminado comprobado.
- Orden de tests: primero el test que falla (TDD) en lo crítico — débito/crédito/refund, idempotencia, máquina de estados, reglas de fraude.
- Ningún secreto en el repo: todo por `.env` (`.env.example` versionado).

---

## Fase 0 — Entorno e infraestructura

**Objetivo:** Docker Compose levanta Postgres, RabbitMQ y Keycloak, y se puede iniciar sesión.

**Tareas**
1. Instalar y verificar: JDK 21, Docker Desktop (WSL 2), Node.js 22 LTS, Git, IntelliJ, Bruno/Postman. Verificar en PowerShell: `java -version`, `docker --version`, `docker compose version`, `node -v`, `git --version`.
2. Crear `%UserProfile%\.wslconfig` con `[wsl2]` `memory=6GB` `processors=4` y ejecutar `wsl --shutdown`.
3. Agregar el host de Keycloak (PowerShell como administrador): `Add-Content C:\Windows\System32\drivers\etc\hosts "127.0.0.1 keycloak"`.
4. `git init` en la raíz (si no existe). Crear `.gitattributes` (`* text=auto eol=lf`), `.gitignore` (`.env`, `target/`, `node_modules/`, `.idea/`, `dist/`), `.env.example`.
5. Crear `infra/postgres/init.sql`: roles `core_user`, `tx_user`, `fraud_user` y bases `core_db`, `tx_db`, `fraud_db` (owner respectivo, `REVOKE CONNECT ... FROM PUBLIC`).
6. Crear `docker-compose.yml` con `postgres:17` (volumen nombrado), `rabbitmq:4-management` (volumen nombrado) y Keycloak 26.x (`start-dev --import-realm`, `KC_HTTP_PORT=8180`, `KC_HOSTNAME=http://keycloak:8180`, volumen del realm). `healthcheck` en los tres.
7. En Keycloak crear el realm `fintech`: roles `USER` (por defecto) y `ADMIN`, cliente público `wallet-frontend` (Code + PKCE S256), registro habilitado, access token 5 min, usuarios `user1`, `user2`, `admin`. Exportar a `infra/keycloak/realm-fintech.json`.

**Archivos que se tocan:** `.gitattributes`, `.gitignore`, `.env.example`, `docker-compose.yml`, `infra/postgres/init.sql`, `infra/keycloak/realm-fintech.json`.

**Terminado cuando:** `docker compose up -d` deja los 3 contenedores `healthy`; `http://localhost:15672` (RabbitMQ) y `http://keycloak:8180` (Keycloak) responden; se obtiene un JWT con `user1` y trae el rol `USER`; `admin` trae `ADMIN`; `docker compose down -v && docker compose up -d` reimporta el realm.

---

## Fase 1 — Core-Banking básico (sin seguridad)

**Objetivo:** cuentas, ledger de doble entrada, depósitos y débito/crédito/refund correctos y concurrentes.

**Tareas**
0. **Antes de generar cualquier proyecto Java:** verificar en la tabla oficial de Spring Cloud que la versión de Spring Boot es compatible con el release train elegido; si no coinciden, usar la versión de Boot que soporte Spring Cloud y actualizar [02-TRD §1](02-TRD.md) (⚠️ SUPUESTO).
1. Generar el proyecto en start.spring.io (Java 21, Boot 4.1.x salvo lo que resulte del paso 0): Web, Data JPA, PostgreSQL Driver, Validation, Flyway, Lombok, Actuator. Ubicarlo en `core-banking-service/`. Confirmar que el wrapper `mvnw.cmd` funciona.
2. `application.yml` (puerto 8081, `core_db` desde variables de entorno).
3. `V1__init.sql`: `accounts` (con `type` y CHECK condicional), `ledger_entries`, `transfer_operations`, `deposits`, `outbox_events`, `processed_messages`, `audit_log` y la cuenta `TREASURY` sembrada (ver [05 §1](05-esquema-backend.md)).
4. `crypto/`: `AttributeConverter` AES-256-GCM para `account_number` + test unitario (cifrar/descifrar, IV distinto, manipulación detectada).
5. `account/`: entidad, repositorio (`@Lock(PESSIMISTIC_WRITE)` + `findByIdForUpdate`), servicio, controlador, DTO (records). Creación idempotente, alias generado y único, `lookup`.
6. `deposit/`: depósito demo y de ADMIN con `Idempotency-Key`, dos asientos (TREASURY y cuenta), topes (50.000 / saldo 200.000 / 1.000.000).
7. `ledger/` y `operation/`: asientos inmutables; servicio con `debit()`, `credit()`, `refund()` según [05 §1.3](05-esquema-backend.md), usando `transfer_operations` (de momento invocables desde el servicio/tests, sin mensajería).
8. `reconciliation/`: servicio y endpoint `GET /api/admin/reconciliation` (sin seguridad todavía).
9. `common/`: `@RestControllerAdvice` con el formato de error de [05 §5](05-esquema-backend.md) y códigos (`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_FOUND`, ...).
10. Tests con Testcontainers (Postgres real): débito/crédito/refund, idempotencia de `transfer_operations`, **50 débitos concurrentes** sobre una cuenta, refund tras crédito rechazado, conciliación `ok`.

**Archivos:** `core-banking-service/**` (`pom.xml`, `application.yml`, `db/migration/V1__init.sql`, paquetes `account`, `ledger`, `deposit`, `operation`, `reconciliation`, `crypto`, `common`).

**Terminado cuando:** `cd core-banking-service; .\mvnw.cmd verify` pasa, incluido el test de concurrencia (saldo final exacto y nunca negativo), y se prueban los endpoints desde Bruno/Postman contra `localhost:8081`.

---

## Fase 2 — Seguridad y API Gateway

**Objetivo:** nada responde sin token; cada usuario solo ve lo suyo.

**Tareas**
1. Core: agregar OAuth2 Resource Server; `SecurityConfig` con `issuer-uri` (`http://keycloak:8180/realms/fintech`) y `jwk-set-uri` interno; conversor `realm_access.roles` → `ROLE_*`; `@EnableMethodSecurity`.
2. Proteger endpoints según [05 §5.1](05-esquema-backend.md) con `@PreAuthorize`; validar propiedad con `sub` (ajeno → 404); `audit_log` para operaciones ADMIN (bloqueo/desbloqueo, depósito).
3. Crear `api-gateway/` (Spring Cloud Gateway reactivo): rutas de [05 §5](05-esquema-backend.md), Resource Server JWT, CORS solo para el origen del front, cabeceras de seguridad, rate limit con Bucket4j en memoria (10 req/min por `sub` en `POST /api/transfers` y depósitos).
4. Tests de seguridad (`spring-security-test`): 401 sin token, 403 sin rol, 404 sobre recursos ajenos.
5. Probar con Bruno: pedir token a Keycloak, llamar por el gateway (`:8080`).
6. Agregar Core y Gateway al `docker-compose.yml` ya es de la Fase 6; aquí se corren desde el IDE/Maven.

**Archivos:** `core-banking-service/**/security/**`, `.../audit/**`, `api-gateway/**`.

**Terminado cuando:** sin token → 401; USER con endpoint ADMIN → 403; USER pidiendo la cuenta de otro → 404; `user1` y `user2` solo ven la suya; el gateway rechaza orígenes CORS no permitidos; los tests de seguridad pasan.

---

## Fase 3 — Transaction-Service y Saga

**Objetivo:** transferencias de punta a punta (con fraude simulado) sin perder ni duplicar dinero.

**Tareas**
1. Crear `transaction-service/` (mismas dependencias + Spring AMQP + Resource Server). Puerto 8082, `tx_db`. `V1__init.sql` con `transfers`, `saga_steps`, `outbox_events`, `processed_messages`.
2. Topología RabbitMQ por `@Bean` ([05 §6.1](05-esquema-backend.md)): cada servicio declara solo las colas y DLQ que consume; el exchange (`fintech.exchange`, `fintech.dlx`) lo declaran todos con los mismos argumentos. Agregar RabbitMQ al código de Core (consumidor de comandos) y a Transaction.
3. Transaction: `POST /api/transfers` con `Idempotency-Key` + `request_hash`, persistencia + outbox en una transacción; `GET /api/transfers/{id}`, `GET /api/transfers`; endpoints ADMIN.
4. Publicador de outbox (`@Scheduled` 1 s, `FOR UPDATE SKIP LOCKED`, publisher confirms) en Transaction y en Core.
5. Orquestador: listener de `tx.replies` con la máquina de estados de [03 §5](03-flujo-app.md), ignorando replies inválidos; job de reintentos (30 s) con backoff y `FAILED_MANUAL_REVIEW`; `PENDING` expira a `FAILED` a los 5 min; endpoint `resume`.
6. Core: listeners de `DebitFundsCommand`, `CreditFundsCommand`, `RefundFundsCommand` con `transfer_operations` (re-emisión de la respuesta guardada), `processed_messages`, ack manual tras commit, 3 reintentos y DLQ. Publica `FundsDebited`, `DebitRejected`, `FundsCredited`, `CreditFailed`, `FundsRefunded`.
7. Mientras no exista Fraud: propiedad `saga.fraud.simulate-approval=true` — Transaction auto-aprueba (salta de `PENDING` a `FRAUD_APPROVED` sin mensaje). Se elimina en la Fase 4.
8. Publicar `TransferCompleted` al completar (Fraud aún no lo consume).
9. Tests (Testcontainers Postgres + RabbitMQ): transferencia exitosa, sin fondos, destino bloqueado (queda `COMPENSATED` y saldo de origen intacto), mensaje duplicado con otro `messageId` (no se aplica dos veces y se re-emite la respuesta), reply tardío/duplicado ignorado, RabbitMQ detenido a mitad y reanudado, Core caído y vuelto a levantar, `Idempotency-Key` repetida / con distinto payload, conciliación `ok` al final.

**Archivos:** `transaction-service/**`, `core-banking-service/**/messaging/**`, `.../outbox/**`, `.../operation/**`.

**Terminado cuando:** una transferencia llega a `COMPLETED`; apagar RabbitMQ en medio de una transferencia (`docker compose stop rabbitmq`), volver a prenderlo (`docker compose start rabbitmq`) y la transferencia termina bien; los tests de resiliencia pasan y la conciliación da `ok = true`.

---

## Fase 4 — Fraud-Detection-Service

**Objetivo:** el análisis de fraude real reemplaza la aprobación simulada.

**Tareas**
1. Crear `fraud-detection-service/` (puerto 8083, `fraud_db`): `risk_assessments`, `user_activity`, `risk_rules` (sembradas), `outbox_events`, `processed_messages`.
2. `rules/`: interfaz `RiskRule` (Strategy) y una clase por regla (`HIGH_AMOUNT`, `VELOCITY`, `DAILY_LIMIT`, `NEW_DESTINATION`); `assessment/` suma pesos de las reglas activas, `score = min(100, suma)`, rechaza con `score >= 70`. `VELOCITY` cuenta intentos (todas las filas de `risk_assessments` de la última hora, rechazadas incluidas); `DAILY_LIMIT` suma solo transferencias completadas desde `user_activity` ([05 §3](05-esquema-backend.md)).
3. Listener de `FraudCheckRequested` (idempotente por `transfer_id`; si ya existe, re-emite la decisión) → outbox `FraudApproved` / `FraudRejected`. Listener de `TransferCompleted` → `user_activity`.
4. Endpoints ADMIN: `GET /api/fraud/assessments`, `GET /api/fraud/rules`, `PATCH /api/fraud/rules/{code}` (con seguridad y ruta en el gateway).
5. Transaction: guardar `fraud_score` / `fraud_reasons`; **eliminar** `saga.fraud.simulate-approval`.
6. Tests: unitarios por regla y por combinación (HIGH_AMOUNT solo rechaza; VELOCITY+DAILY rechaza; NEW_DESTINATION+VELOCITY aprueba; un intento rechazado cuenta para VELOCITY pero no suma al acumulado de 24 h), idempotencia del listener, integración completa, fraude caído → `PENDING` expira a `FAILED`.

**Archivos:** `fraud-detection-service/**`, `transaction-service/**/saga/**`, `api-gateway/**` (ruta `/api/fraud/**`).

**Terminado cuando:** una transferencia de 100.001 queda `REJECTED` sin mover dinero (saldos y ledger intactos, conciliación `ok`); una de 1.500 se completa; el ADMIN puede desactivar `HIGH_AMOUNT` y esa misma transferencia pasa.

---

## Fase 5 — Frontend

**Objetivo:** un usuario nuevo se registra, se carga saldo y transfiere desde el navegador.

**Tareas**
1. `npm create vite@latest wallet-frontend -- --template react-ts` en la raíz; agregar Tailwind, TanStack Query, React Router, `keycloak-js`, lucide-react; configurar tokens de [04-diseno](04-diseno.md) (claro/oscuro).
2. `auth/`: inicialización de Keycloak (`check-sso` + PKCE), `updateToken(30)`, cliente HTTP (`fetch`) que agrega el Bearer y maneja 401/403.
3. Primer login: `GET /api/accounts/me` → si 404, `POST /api/accounts`.
4. Pantallas de [03 §1](03-flujo-app.md): Inicio, Transferir (`Idempotency-Key` con `crypto.randomUUID()` al abrir el formulario), Detalle con polling cada 2 s, Historial, Cargar saldo demo, Mi cuenta y panel ADMIN (transferencias, fraude, cuentas, conciliación).
5. Estados de carga, vacíos y errores en cada pantalla con los mensajes de [03 §4](03-flujo-app.md); responsive y modo oscuro.
6. Tests: Vitest + Testing Library (formularios, formateo de montos, estados) y un e2e con Playwright (registro → carga → transferencia → completada; rechazada por fraude).

**Archivos:** `wallet-frontend/**` (`src/auth`, `src/api`, `src/features/*`, `src/components`, `src/routes`, `src/styles`).

**Terminado cuando:** se ejecuta el flujo completo desde el navegador contra los servicios (Compose de infraestructura + servicios locales), los tests del front pasan y funciona en un ancho de 375 px y en modo oscuro.

---

## Fase 6 — Docker Compose completo, calidad y observabilidad

**Objetivo:** `git clone` + `.env` + `docker compose up` y todo funciona.

**Tareas**
1. `Dockerfile` multi-stage por servicio (build con Maven wrapper → `eclipse-temurin:21-jre`, usuario no root) y para el frontend (build Vite → `nginx` con fallback de SPA). Imágenes multi-arch.
2. Agregar todo a `docker-compose.yml` con `depends_on: condition: service_healthy`, `healthcheck` (Actuator `health`) y `JAVA_TOOL_OPTIONS=-Xmx256m`; `.env.example` completo (puertos, claves, `APP_CRYPTO_KEY`, URLs).
3. Endpoint de conciliación listo (Fase 1) y accesible desde el panel; `audit_log` completo (Fase 2): verificar que ambos funcionan en el stack completo.
4. Logs JSON con `transferId`/`messageId`/`userId` en MDC; métricas Micrometer (transferencias por estado, outbox pendiente).
5. (Opcional) Micrometer Tracing + Zipkin.
6. `.github/workflows/ci.yml`: en cada push, `mvnw verify` de los 4 proyectos Java y `npm ci && npm test && npm run build` del front (Ubuntu, Docker disponible para Testcontainers). Job e2e opcional con Compose.
7. `README.md` con diagramas, decisiones de diseño (Saga orquestada, Outbox, bloqueo pesimista), cómo correrlo en Windows, usuarios demo.

**Archivos:** `*/Dockerfile`, `docker-compose.yml`, `.env.example`, `.github/workflows/ci.yml`, `README.md`.

**Terminado cuando:** desde un clon limpio (`Copy-Item .env.example .env; docker compose up -d --build`) todo queda `healthy`, el flujo completo funciona desde el navegador y el CI está en verde.

---

## Fase 7 — Deploy y presentación

**Objetivo:** demo pública con HTTPS.

**Tareas**
1. Crear la VM en Oracle Cloud free tier (Ubuntu ARM64, ≥ 4 GB), abrir puertos 22/80/443 (lista de seguridad y firewall del SO), instalar Docker y Compose; conectarse desde PowerShell con `ssh`.
2. DNS: `app.<IP>.sslip.io` y `auth.<IP>.sslip.io` (o DuckDNS). Caddy (`infra/caddy/Caddyfile`): `app.` → frontend + `/api` → gateway; `auth.` → Keycloak. `docker-compose.prod.yml`: Caddy, `mem_limit`, Keycloak en modo producción (`KC_HOSTNAME=https://auth...`, `KC_PROXY_HEADERS=xforwarded`), Swagger desactivado.
3. Clonar el repo, crear `.env` con claves distintas a las locales (incluida `APP_CRYPTO_KEY`), actualizar `redirectUris`/`webOrigins` del realm, `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d`.
4. Probar el flujo completo desde el navegador, correr la conciliación y comprobar que RabbitMQ/Postgres/Keycloak admin no quedan expuestos a Internet.
5. Crear un usuario demo y documentarlo en el README (los reclutadores también pueden registrarse y usar "Cargar saldo demo").
6. Grabar un GIF/video de 2 minutos (transferencia exitosa, rechazada por fraude, compensada) y agregar bullets de CV.

**Archivos:** `docker-compose.prod.yml`, `infra/caddy/Caddyfile`, `README.md`, ajustes del realm.

**Terminado cuando:** la URL pública funciona con HTTPS, el registro/login/transferencia funcionan, no hay puertos internos expuestos y el README tiene link, usuario demo y guía de ejecución.

---

## Fase opcional — Pasarela de pagos (Futuro)

`Merchant` con API key, `POST /api/payments`, webhook firmado HMAC; reutiliza la Saga. Fuera del alcance de los documentos 01–07.
