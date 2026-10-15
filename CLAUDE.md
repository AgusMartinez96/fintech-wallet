# CLAUDE.md — fintech-wallet

**Resumen.** Billetera virtual en ARS (proyecto de portafolio) con transferencias entre cuentas por alias.
Tres microservicios Spring Boot (Core-Banking, Transaction, Fraud-Detection) detrás de Spring Cloud Gateway, comunicados por RabbitMQ con Saga orquestada, Outbox e idempotencia; una base PostgreSQL por servicio; login OAuth2/JWT con Keycloak.
Frontend React + TypeScript; todo corre con Docker Compose y se despliega en un VPS. Desarrollo en Windows (PowerShell).

## Regla principal
Antes de implementar, leé los documentos de /docs. Seguí docs/06-plan-implementacion.md fase por fase, de a una por vez, y verificá cada fase con docs/07-criterios-aceptacion.md antes de pasar a la siguiente. Si algo de lo que vas a hacer contradice un documento, avisame antes de cambiarlo.

## Documentos (`/docs`)
`01-PRD` producto · `02-TRD` técnico y decisiones · `03-flujo-app` pantallas y Saga · `04-diseno` UI · `05-esquema-backend` tablas, endpoints y mensajes · `06-plan-implementacion` fases · `07-criterios-aceptacion` criterios y tests. `plan-proyecto.md` es el original: ante diferencias, prevalecen 01–07.

## Stack y convenciones
- Java 21, Spring Boot 4.1.x, Spring Cloud 2025.1.x, PostgreSQL 17, RabbitMQ 4, Keycloak 26.x, Flyway, Testcontainers. Un proyecto Maven por servicio (sin POM padre). Frontend: React 19 + TS + Vite + Tailwind + TanStack Query + `keycloak-js`.
- **Paquetes por funcionalidad** (`account/`, `ledger/`, `saga/`), no por capa. Base `com.agustin.<servicio>`.
- **Dinero: `BigDecimal` en Java, `NUMERIC(19,4)` en la base, string decimal en JSON. Nunca `double` ni `float`.**
- Nunca llamar a `rabbitTemplate.send()` desde la lógica de negocio: todo mensaje sale del outbox. Ack manual después del commit.
- **RabbitMQ:** cada cola y su DLQ las declara únicamente el servicio que la consume; el exchange (`fintech.exchange`, `fintech.dlx`) lo declaran todos con exactamente los mismos argumentos (ver docs/05 §6.1).
- **Versiones:** antes de la Fase 1 verificá la compatibilidad Spring Boot ↔ Spring Cloud en la tabla oficial; si no coinciden, usá la versión de Boot que soporte Spring Cloud (docs/02 §1).
- **Fraude:** `VELOCITY` cuenta intentos (`risk_assessments`); `DAILY_LIMIT` suma solo transferencias completadas (`user_activity`).
- Idempotencia por clave de negocio (`transfer_operations`, `Idempotency-Key`), no solo por `messageId`.
- Recurso ajeno → 404 (no 403). Sin secretos ni tokens en el repo ni en los logs; números de cuenta enmascarados.
- Código, API, tablas y commits en inglés; UI y documentos en español.

## Comandos (PowerShell, desde la raíz del repo)
```powershell
# Preparación (una vez)
Copy-Item .env.example .env                     # completar claves
# Host de Keycloak (PowerShell como administrador):
Add-Content C:\Windows\System32\drivers\etc\hosts "127.0.0.1 keycloak"

# Infraestructura / stack
docker compose up -d                            # Postgres, RabbitMQ, Keycloak (+ servicios desde la Fase 6)
docker compose up -d --build                    # stack completo
docker compose logs -f core-banking-service
docker compose down                             # conserva datos
docker compose down -v                          # borra volúmenes (reimporta realm e init.sql)

# Backend (por servicio: core-banking-service, transaction-service, fraud-detection-service, api-gateway)
cd core-banking-service
.\mvnw.cmd spring-boot:run                      # correr
.\mvnw.cmd clean verify                         # compilar + tests (requiere Docker encendido)
.\mvnw.cmd -Dtest=NombreDelTest test            # un test
cd ..

# Frontend
cd wallet-frontend
npm ci                                          # instalar
npm run dev                                     # http://localhost:5173
npm test                                        # Vitest
npx playwright test                             # e2e (stack levantado)
npm run build
```

URLs locales: gateway `http://localhost:8080` · Keycloak `http://keycloak:8180` · RabbitMQ UI `http://localhost:15672` · front `http://localhost:5173`.

## Al trabajar en una fase
1. Releé la fase en `docs/06` y los criterios en `docs/07`; escribí primero los tests de lo crítico.
2. Implementá solo lo de esa fase; dejá el proyecto compilando y con tests en verde.
3. Verificá el criterio de "terminado" (incluida la conciliación `ok` cuando haya dinero) y recién ahí mostrame los comandos de commit (ver Git y commits).

## Git y commits
- **No hagas commits ni push.** Los commits los hago yo a mano.
- Al terminar cada fase, mostrame los comandos de git que tengo que correr: `git add` y un mensaje de commit sugerido en formato `tipo: descripción` (en inglés, ej. `feat: add account ledger`).
- Si en la fase se generó un servicio nuevo con Spring Initializr, incluí en esos comandos: `git add --chmod=+x <servicio>/mvnw`.
