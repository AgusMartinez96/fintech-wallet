# Fintech: Pasarela de Pagos y Billetera Virtual — Plan del proyecto

> Documento original. Ante cualquier diferencia, prevalecen los archivos 01 a 07 de docs/.

Oct 14, 2026 · @Agustin

## Resumen y objetivos

Una billetera virtual con transferencias entre cuentas, construida como 3 microservicios Spring Boot que se comunican por RabbitMQ y garantizan que nunca se pierda ni se duplique dinero, aunque un servicio se caiga a mitad de la operación.

Lo que el proyecto tiene que demostrar a un reclutador:

- **Seguridad estricta:** login OAuth2, tokens JWT validados en cada servicio, roles y datos sensibles encriptados en la base.
- **Consistencia distribuida:** patrón Saga orquestado con compensaciones, Outbox para no perder mensajes e idempotencia para no procesar dos veces.
- **Aislamiento transaccional:** una base por servicio, bloqueos sobre los saldos y un libro contable (ledger) de doble entrada.
- **Producto completo:** gateway, frontend React, todo levantable con un solo `docker compose up` y publicado online.

Alcance mínimo viable (MVP): registrarse, ver saldo, cargar saldo de prueba, transferir a otra cuenta, ver historial y ver transferencias bloqueadas por fraude. Pagos a comercios (pasarela) quedan como fase opcional al final.

## Arquitectura general

El usuario solo habla con el gateway; los tres servicios nunca se llaman entre sí para mover dinero, sino que intercambian mensajes por RabbitMQ, y cada uno tiene su propia base.

&#91;embedded content: arquitectura · 3 microservicios, gateway, Keycloak y RabbitMQ\]

La línea punteada es la validación del token contra Keycloak; cada servicio vuelve a validar el JWT por su cuenta.

## Stack tecnológico

Todo el backend en Java + Spring Boot; versiones exactas a elegir en start.spring.io el día que arranques (usá la última estable).

| Capa | Tecnología | Para qué |
| --- | --- | --- |
| Lenguaje | Java 21 (LTS) | Records, virtual threads, pattern matching |
| Framework | Spring Boot 3.x (Web, Data JPA, Validation, Actuator) | Base de cada microservicio |
| Seguridad | Spring Security OAuth2 Resource Server | Validar JWT en cada servicio |
| Servidor de identidad | Keycloak (en Docker) | Login, emisión de JWT, roles. Alternativa más difícil: Spring Authorization Server |
| Gateway | Spring Cloud Gateway | Punto de entrada único, ruteo, rate limiting, CORS |
| Mensajería | RabbitMQ + Spring AMQP | Comandos y eventos de la Saga, colas de error (DLQ) |
| Base de datos | PostgreSQL (una base por servicio) | Saldos, transferencias, análisis de riesgo |
| Migraciones | Flyway | Versionar el esquema de cada base |
| Mapeo DTO | MapStruct + Lombok | Menos código repetido |
| Tests | JUnit 5, Mockito, Testcontainers | Tests con Postgres y RabbitMQ reales en Docker |
| Docs API | springdoc-openapi (Swagger UI) | Documentar endpoints |
| Frontend | React + TypeScript + Vite + Tailwind + TanStack Query | Billetera web |
| Login en el front | keycloak-js u oidc-client-ts (flujo Authorization Code + PKCE) | Obtener el JWT sin manejar contraseñas |
| Infra local | Docker Desktop + Docker Compose | Levantar todo con un comando |
| Observabilidad (opcional) | Micrometer + Zipkin o Grafana | Seguir una transferencia entre servicios |

## Diseño de cada microservicio

Cada servicio es dueño de su propia base: ninguno lee ni escribe la base de otro. Se hablan solo por HTTP (consultas) o RabbitMQ (comandos y eventos).

### Core-Banking-Service (puerto 8081, base `core_db`)

Es la única fuente de verdad del dinero. Nadie más modifica saldos.

Tablas:

- `accounts`: id (UUID), user\_id (el `sub` del JWT), alias, currency, balance NUMERIC(19,4), status (ACTIVE / BLOCKED), version (para bloqueo optimista), created\_at.
- `ledger_entries`: id, account\_id, transfer\_id, type (DEBIT / CREDIT), amount, balance\_after, created\_at. Restricción UNIQUE (transfer\_id, account\_id, type) para que un mismo movimiento no se aplique dos veces.
- `outbox_events`: id, aggregate\_id, event\_type, payload (JSON), status (PENDING / SENT), created\_at.
- `processed_messages`: message\_id (PK), processed\_at. Sirve para descartar mensajes repetidos.

Endpoints:

| Método | Ruta | Rol | Qué hace |
| --- | --- | --- | --- |
| POST | /api/accounts | USER | Abre una cuenta para el usuario logueado |
| GET | /api/accounts/me | USER | Lista mis cuentas y saldos |
| GET | /api/accounts/{id}/movements | USER (dueño) | Historial del ledger, paginado |
| POST | /api/accounts/{id}/deposit | ADMIN | Carga saldo de prueba |
| PATCH | /api/accounts/{id}/status | ADMIN | Bloquea o desbloquea una cuenta |

Escucha por RabbitMQ: `DebitFundsCommand`, `CreditFundsCommand`, `RefundFundsCommand`. Responde con `FundsDebited`, `InsufficientFunds`, `FundsCredited`, `CreditFailed`, `FundsRefunded`.

### Transaction-Service (puerto 8082, base `tx_db`)

Recibe los pedidos de transferencia y actúa como **orquestador de la Saga**: decide el siguiente paso según la respuesta de cada servicio.

Tablas:

- `transfers`: id, idempotency\_key (UNIQUE), user\_id, from\_account\_id, to\_account\_id, amount, currency, description, status, failure\_reason, created\_at, updated\_at.
- `saga_steps`: id, transfer\_id, step (FRAUD\_CHECK / DEBIT / CREDIT / REFUND), status, created\_at. Es la bitácora de la Saga.
- `outbox_events` y `processed_messages`: igual que en Core-Banking.

Estados de una transferencia: PENDING → FRAUD\_APPROVED → DEBITED → COMPLETED. Finales de error: REJECTED (fraude), FAILED (sin fondos), COMPENSATED (se devolvió la plata).

Endpoints:

| Método | Ruta | Rol | Qué hace |
| --- | --- | --- | --- |
| POST | /api/transfers | USER | Inicia una transferencia; header obligatorio `Idempotency-Key`; responde 202 Accepted |
| GET | /api/transfers/{id} | USER (dueño) | Estado actual de la transferencia |
| GET | /api/transfers | USER | Mis transferencias, paginado |
| GET | /api/admin/transfers | ADMIN | Todas, con filtro por estado |

### Fraud-Detection-Service (puerto 8083, base `fraud_db`)

Analiza cada transferencia de forma asíncrona con reglas simples y devuelve una decisión con puntaje.

Tablas:

- `risk_assessments`: id, transfer\_id (UNIQUE), user\_id, score (0–100), decision (APPROVED / REJECTED / REVIEW), reasons (JSON), created\_at.
- `user_activity`: user\_id, transfer\_count\_last\_hour, amount\_last\_24h, last\_transfer\_at (se actualiza con cada evento).
- `risk_rules`: code, description, weight, active. Así las reglas se activan sin redeploy.

Reglas iniciales sugeridas (los umbrales los definís vos):

1. Monto mayor a un límite por operación.
2. Más de N transferencias en la última hora (velocidad).
3. Monto acumulado en 24 h por encima de un límite.
4. Cuenta destino nueva o nunca usada antes por ese usuario.
5. Transferencia a la propia cuenta repetida muchas veces (patrón de lavado).

Escucha: `FraudCheckRequested`. Publica: `FraudApproved` o `FraudRejected`.

Endpoints (solo ADMIN): `GET /api/fraud/assessments`, `GET /api/fraud/rules`, `PATCH /api/fraud/rules/{code}`.

## Patrón Saga con RabbitMQ

Usamos una Saga **orquestada**: Transaction-Service manda un comando por vez y, si un paso falla después de haber tocado plata, ejecuta la compensación. Es más fácil de explicar en una entrevista que la coreografía, porque toda la lógica del flujo vive en un solo lugar.

&#91;embedded content: Saga de transferencia · 3 decisiones, 1 compensación\]

Cada rombo es una respuesta que llega por RabbitMQ al orquestador; solo la rama del crédito fallido necesita deshacer un movimiento ya hecho.

### Configuración de RabbitMQ

- Un exchange de tipo **topic** llamado `fintech.exchange`.
- Una cola por servicio: `core.commands`, `fraud.commands`, `tx.replies`, cada una atada al exchange con su routing key (por ejemplo `core.debit`, `fraud.check`, `tx.reply.*`).
- Cada cola tiene su **cola de mensajes muertos** (DLQ): `core.commands.dlq`, etc. Un mensaje que falla 3 veces va ahí en lugar de perderse.
- **Publisher confirms** activados y **ack manual** en los consumidores: el mensaje se confirma recién después de hacer commit en la base.
- Mensajes en JSON con: `messageId`, `transferId`, `type`, `payload`, `timestamp`.

### Flujo feliz de una transferencia

1. El usuario hace `POST /api/transfers` con `Idempotency-Key`. Transaction guarda la transferencia en PENDING y, **en la misma transacción de base**, un `FraudCheckRequested` en su tabla outbox. Responde 202.
2. Un proceso programado (`@Scheduled` cada 1–2 s) lee el outbox y publica en RabbitMQ. Esto es el **patrón Outbox**: si RabbitMQ está caído, el evento queda guardado y se reintenta; nunca se pierde.
3. Fraud evalúa las reglas y responde `FraudApproved`. Transaction pasa a FRAUD\_APPROVED y envía `DebitFundsCommand`.
4. Core-Banking bloquea la cuenta origen (`SELECT ... FOR UPDATE`), verifica saldo, descuenta, escribe el asiento DEBIT en el ledger y responde `FundsDebited`. Transaction pasa a DEBITED.
5. Transaction envía `CreditFundsCommand`. Core-Banking acredita en la cuenta destino y responde `FundsCredited`. Transaction pasa a COMPLETED.
6. El frontend consulta `GET /api/transfers/{id}` cada pocos segundos (o con polling de TanStack Query) hasta ver el estado final.

### Qué pasa cuando algo falla

| Falla en el paso | Respuesta recibida | Acción del orquestador | Estado final |
| --- | --- | --- | --- |
| Análisis de fraude | FraudRejected | Ninguna: todavía no se movió plata | REJECTED |
| Débito | InsufficientFunds | Ninguna: no se descontó nada | FAILED |
| Crédito (cuenta destino bloqueada o inexistente) | CreditFailed | Envía `RefundFundsCommand`: Core devuelve el monto al origen con un asiento CREDIT de reversa | COMPENSATED |
| Cualquier paso sin respuesta | (timeout) | Un `@Scheduled` revisa sagas trabadas más de X minutos y reenvía el último comando; gracias a la idempotencia no se duplica | Sigue el flujo |

### Las tres reglas que evitan perder o duplicar dinero

1. **Outbox:** el cambio de estado y el mensaje se guardan en la misma transacción. Nunca llamás a `rabbitTemplate.send()` dentro de la lógica de negocio.
2. **Consumidor idempotente:** antes de procesar, el consumidor inserta el `messageId` en `processed_messages`. Si ya existe, descarta el mensaje y solo hace ack. Esto va en la misma transacción que el cambio de saldo.
3. **Idempotency-Key en la API:** si el usuario aprieta dos veces "Transferir", la segunda petición con la misma clave devuelve la transferencia ya creada en lugar de crear otra.

Además, Fraud sigue analizando en segundo plano todas las transferencias completadas para detectar patrones (por ejemplo, muchas cuentas nuevas recibiendo plata del mismo usuario) y puede marcar cuentas para revisión del ADMIN.

## Seguridad y aislamiento transaccional

El principio es "nadie confía en nadie": el gateway valida el token, pero cada servicio lo vuelve a validar y verifica que el recurso pertenezca al usuario.

### Autenticación y autorización (OAuth2 + JWT)

1. Keycloak tiene un *realm* `fintech` con dos clientes: `wallet-frontend` (público, Authorization Code + PKCE) y `gateway` (confidencial).
2. Roles del realm: `USER` y `ADMIN`. Los usuarios se registran desde la pantalla de Keycloak (registro habilitado).
3. El frontend redirige a Keycloak, recibe el JWT (access token de corta vida, \~5 min) y lo manda en `Authorization: Bearer ...`.
4. Spring Cloud Gateway valida firma y expiración del JWT y lo reenvía a los servicios.
5. Cada servicio es un **Resource Server**: valida el JWT contra el `issuer-uri` de Keycloak, convierte los roles del token en `GrantedAuthority` y protege endpoints con `@PreAuthorize("hasRole('ADMIN')")`.
6. **Control de propiedad:** en cada consulta se compara el `sub` del token con el `user_id` del recurso. Un USER que pide la cuenta de otro recibe 404 (no 403, para no revelar que existe).
7. Comunicación entre servicios por HTTP (si la hay): token de servicio con *client credentials*.

### Encriptación y protección de datos

- **En tránsito:** HTTPS en el deploy (lo da el proveedor o un proxy como Caddy/Nginx).
- **Contraseñas:** las maneja Keycloak con hash; tu código nunca las ve.
- **Datos sensibles en reposo:** número de cuenta/CBU simulado y documento del usuario encriptados con AES-256-GCM mediante un `AttributeConverter` de JPA. La clave viene de una variable de entorno, nunca del código.
- **Secretos:** archivo `.env` local (en `.gitignore`) y variables de entorno en el deploy.
- **Logs:** nunca loguear tokens, montos con datos personales completos ni claves. Enmascarar (`****1234`).
- **Gateway:** rate limiting por usuario en `POST /api/transfers`, CORS solo para el dominio del frontend, cabeceras de seguridad.
- **Auditoría:** tabla `audit_log` en Core-Banking con quién, qué y cuándo para operaciones ADMIN.

### Aislamiento transaccional y dinero

- **Una base por servicio:** tres bases separadas (en local, tres contenedores Postgres o una instancia con tres bases y tres usuarios distintos con permisos solo sobre la suya).
- **Montos:** siempre `BigDecimal` en Java y `NUMERIC(19,4)` en la base. Nunca `double` ni `float`.
- **Bloqueo pesimista al debitar:** `@Lock(LockModeType.PESSIMISTIC_WRITE)` en el repositorio, que genera `SELECT ... FOR UPDATE`. Dos débitos simultáneos sobre la misma cuenta se ejecutan uno después del otro.
- **Bloqueo optimista como red de seguridad:** campo `@Version` en `Account`.
- **Evitar deadlocks:** al tocar dos cuentas, bloquearlas siempre en el mismo orden (por id ascendente).
- **Nivel de aislamiento:** `READ COMMITTED` + bloqueos explícitos alcanza para el débito. Podés mostrar `SERIALIZABLE` en un caso puntual (por ejemplo, el reporte de conciliación) y explicar la diferencia en el README.
- **Ledger de doble entrada:** el saldo de una cuenta debe ser siempre igual a la suma de sus asientos. Un job o endpoint ADMIN de **conciliación** lo verifica y es una gran demo.
- **Restricciones en la base:** `CHECK (balance >= 0)` como última línea de defensa.

## Estructura del repositorio

Un solo repositorio (monorepo) en GitHub, por ejemplo `fintech-wallet`, con un proyecto Maven independiente por servicio. Así el reclutador ve todo junto y vos lo levantás con un comando.

```
fintech-wallet/
├── docker-compose.yml          # Postgres x3, RabbitMQ, Keycloak, servicios, frontend
├── .env.example                # variables de ejemplo (el .env real va en .gitignore)
├── README.md                   # diagrama, cómo correrlo, decisiones de diseño
├── docs/
│   ├── arquitectura.png
│   └── saga-flujo.png
├── infra/
│   ├── keycloak/realm-fintech.json   # realm exportado, se importa al arrancar
│   ├── rabbitmq/definitions.json     # exchange, colas y DLQ
│   └── postgres/init.sql             # crea las 3 bases y usuarios
├── shared-events/              # (opcional) librería con los records de eventos
├── api-gateway/
├── core-banking-service/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/agustin/corebanking/
│       ├── account/          # entity, repository, service, controller, dto
│       ├── ledger/
│       ├── messaging/        # listeners, publisher del outbox
│       ├── outbox/
│       ├── security/         # SecurityConfig, converter de roles
│       └── common/           # excepciones, handler global
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/     # V1__init.sql, V2__... (Flyway)
├── transaction-service/        # misma organización, con paquete saga/
├── fraud-detection-service/    # misma organización, con paquete rules/
└── wallet-frontend/            # React + TS + Vite + Tailwind
```

Organizá los paquetes **por funcionalidad** (`account/`, `ledger/`) y no por capa (`controllers/`, `services/`): se lee mejor en microservicios y se nota la madurez.

## Roadmap por fases

Ocho fases, cada una termina con algo que funciona y se puede commitear. No pases a la siguiente hasta cumplir el criterio de "listo".

### Fase 0 — Entorno e infraestructura

- [ ] Instalar en Windows: JDK 21, Maven (o usar el `mvnw` que trae cada proyecto), Docker Desktop (con WSL 2 activado), Node.js LTS, Git, IntelliJ IDEA y Postman o Bruno.
- [ ] Verificar en **CMD o PowerShell**: `java -version`, `docker --version`, `docker compose version`, `node -v`, `git --version`.
- [ ] Crear la carpeta `fintech-wallet` (por ejemplo en `C:\proyectos\`), hacer `git init` adentro y crear el repo en GitHub.
- [ ] Escribir el archivo `docker-compose.yml` en la raíz con: 3 Postgres (o 1 con `infra/postgres/init.sql`), RabbitMQ con imagen `rabbitmq:management` y Keycloak.
- [ ] Desde la carpeta raíz, en la terminal: `docker compose up -d`. Entrar a RabbitMQ en `http://localhost:15672` y a Keycloak en el puerto que configures.
- [ ] En Keycloak: crear realm `fintech`, roles `USER` y `ADMIN`, clientes `wallet-frontend` y `gateway`, y un usuario de cada rol. Exportar el realm a `infra/keycloak/realm-fintech.json`.

Listo cuando: `docker compose up -d` levanta todo y podés loguearte en Keycloak con el usuario de prueba.

### Fase 1 — Core-Banking básico (sin seguridad todavía)

- [ ] Generar el proyecto en start.spring.io con: Web, Data JPA, PostgreSQL Driver, Validation, Flyway, Lombok, Actuator. Descomprimirlo en `fintech-wallet/core-banking-service/`.
- [ ] Configurar `src/main/resources/application.yml` (puerto 8081, conexión a `core_db`).
- [ ] Crear `db/migration/V1__init.sql` con `accounts` y `ledger_entries`.
- [ ] Implementar entidades, repositorios, servicio y controlador de cuentas + depósito de prueba.
- [ ] Implementar un `debit()` y `credit()` en el servicio con `PESSIMISTIC_WRITE` y asiento en el ledger.
- [ ] Handler global de excepciones (`@RestControllerAdvice`) con errores claros (saldo insuficiente, cuenta no encontrada).
- [ ] Test de concurrencia con Testcontainers: 50 débitos en paralelo sobre la misma cuenta → el saldo final es exacto y nunca negativo.

Listo cuando: el test de concurrencia pasa y probás los endpoints desde Postman.

### Fase 2 — Seguridad y API Gateway

- [ ] Agregar a Core-Banking la dependencia OAuth2 Resource Server y una clase `SecurityConfig` con el `issuer-uri` del realm.
- [ ] Convertir los roles de Keycloak (vienen en `realm_access.roles`) a `ROLE_USER`/`ROLE_ADMIN`.
- [ ] Proteger endpoints con `@PreAuthorize` y validar propiedad con el `sub` del token.
- [ ] Crear `api-gateway/` con Spring Cloud Gateway: rutas `/api/accounts/**` → 8081, `/api/transfers/**` → 8082, `/api/fraud/**` → 8083. Validación de JWT, CORS y rate limiting.
- [ ] Probar desde Postman: pedir token a Keycloak, llamar por el gateway, verificar 401 sin token y 403/404 con el usuario equivocado.

Listo cuando: sin token nada responde y un USER solo ve sus cuentas.

### Fase 3 — Transaction-Service y Saga

- [ ] Crear el proyecto con las mismas dependencias + Spring for RabbitMQ (AMQP) + Resource Server.
- [ ] Declarar exchange, colas y DLQ (en `infra/rabbitmq/definitions.json` o con `@Bean` en cada servicio).
- [ ] Implementar `POST /api/transfers` con `Idempotency-Key`, la tabla `transfers` y el outbox.
- [ ] Implementar el publicador del outbox (`@Scheduled`).
- [ ] Implementar el orquestador: un listener de `tx.replies` que mira el tipo de respuesta y decide el siguiente comando o la compensación.
- [ ] En Core-Banking: listeners para `DebitFundsCommand`, `CreditFundsCommand`, `RefundFundsCommand`, con `processed_messages` y su propio outbox.
- [ ] Mientras no exista Fraud, simular la aprobación automática (un flag en configuración).
- [ ] Tests: transferencia exitosa, sin fondos, cuenta destino bloqueada (debe quedar COMPENSATED y el saldo de origen intacto), mensaje duplicado (no se aplica dos veces).

Listo cuando: podés apagar RabbitMQ en medio de una transferencia, volver a prenderlo y la transferencia termina bien.

### Fase 4 — Fraud-Detection-Service

- [ ] Crear el proyecto, tablas `risk_assessments`, `user_activity`, `risk_rules`.
- [ ] Implementar cada regla como una clase que implementa una interfaz `RiskRule` (patrón Strategy); el servicio suma los pesos.
- [ ] Listener de `FraudCheckRequested` que responde `FraudApproved` o `FraudRejected`.
- [ ] Quitar la aprobación simulada de Transaction-Service.
- [ ] Endpoints ADMIN para ver evaluaciones y activar o desactivar reglas.

Listo cuando: una transferencia que rompe una regla queda REJECTED sin mover plata.

### Fase 5 — Frontend

- [ ] Crear el proyecto en `fintech-wallet/wallet-frontend/` con `npm create vite@latest` (plantilla React + TypeScript) y agregar Tailwind y TanStack Query.
- [ ] Login con Keycloak (Authorization Code + PKCE) usando `keycloak-js` u `oidc-client-ts`.
- [ ] Pantallas: Inicio (saldos), Transferir (formulario con generación de `Idempotency-Key` con `crypto.randomUUID()`), Historial, Detalle de transferencia con estado en vivo (polling), y panel ADMIN (fraude, cuentas bloqueadas, conciliación).
- [ ] Manejo de errores y estados de carga en cada pantalla.

Listo cuando: un usuario nuevo se registra, recibe saldo de prueba del ADMIN y transfiere desde el navegador.

### Fase 6 — Docker Compose completo, calidad y observabilidad

- [ ] Escribir un `Dockerfile` multi-stage en cada servicio y en el frontend.
- [ ] Agregar los servicios al `docker-compose.yml` con `depends_on` y `healthcheck`.
- [ ] Endpoint ADMIN de conciliación (saldo = suma del ledger) y `audit_log`.
- [ ] (Opcional) Trazas con Micrometer Tracing + Zipkin para ver una transferencia cruzando los 3 servicios.
- [ ] GitHub Actions: compilar y correr tests en cada push.

Listo cuando: alguien clona el repo, copia `.env.example` a `.env`, corre `docker compose up` y todo funciona.

### Fase 7 — Deploy y presentación

Detallado en la sección siguiente.

### Fase opcional — Pasarela de pagos

- [ ] Entidad `Merchant` con API key, endpoint `POST /api/payments` para que un comercio cobre a un usuario, y webhook firmado (HMAC) para avisar al comercio el resultado. Reutiliza la misma Saga.

## Deploy, testing y presentación en el CV

### Deploy en la nube

Con 3 servicios + gateway + Keycloak + RabbitMQ + 3 bases, la opción más simple y barata es **un solo servidor virtual (VPS) corriendo el mismo `docker-compose.yml`**. Los planes y precios cambian seguido: comparalos el día que deployes.

| Opción | Cómo queda | Ventaja | Contra |
| --- | --- | --- | --- |
| VPS único (Oracle Cloud free tier, Hetzner, DigitalOcean) | Todo en Docker Compose + Caddy para HTTPS | Igual que en tu máquina, muestra que sabés operar un servidor | Tenés que configurar el servidor vos |
| Servicios gestionados por partes | Backend en Render o Railway, Postgres en Neon, RabbitMQ en CloudAMQP, front en Vercel | Menos administración | Los planes gratis duermen los servicios y Keycloak necesita bastante memoria |

Pasos con VPS:

1. Crear el servidor (Ubuntu, mínimo 4 GB de RAM por Keycloak + 4 JVM).
2. Conectarte desde PowerShell con `ssh usuario@IP` e instalar Docker y Docker Compose.
3. Clonar el repo en el servidor, crear el `.env` con claves reales (distintas a las locales).
4. Apuntar un dominio o subdominio gratuito a la IP y poner Caddy como proxy con HTTPS automático.
5. `docker compose up -d` y probar el flujo completo desde el navegador.
6. Crear un usuario demo de solo lectura y ponerlo en el README.

Tip para ahorrar RAM: limitá la memoria de cada JVM con `JAVA_TOOL_OPTIONS=-Xmx256m` en el compose.

### Qué testear (y mostrar en el README)

- **Unitarios:** reglas de fraude, transiciones de estado de la Saga.
- **Integración con Testcontainers:** repositorios con Postgres real, listeners con RabbitMQ real.
- **Concurrencia:** débitos en paralelo sin saldo negativo ni pérdida.
- **Resiliencia:** mensaje duplicado, servicio caído a mitad de la Saga, compensación.
- **Seguridad:** 401 sin token, 403 sin rol, 404 sobre recursos ajenos.
- **Invariante del ledger:** después de cualquier test, la suma de todos los saldos no cambió (el dinero no se crea ni se destruye, salvo depósitos).

### Cómo presentarlo

- README con: diagrama de arquitectura, diagrama de la Saga, link al deploy, usuario demo, cómo correrlo en local y una sección "Decisiones de diseño" (por qué Saga orquestada, por qué Outbox, por qué bloqueo pesimista).
- Un GIF corto o video de 2 minutos: transferencia exitosa, una bloqueada por fraude y una compensada.
- Bullets sugeridos para el CV:
  - Billetera virtual con 3 microservicios Spring Boot, RabbitMQ y PostgreSQL, desplegada con Docker Compose.
  - Consistencia distribuida con patrón Saga orquestado, Outbox y consumidores idempotentes; sin pérdida ni duplicación de fondos ante caídas.
  - Seguridad con OAuth2/JWT (Keycloak + Spring Security Resource Server), control por roles y encriptación AES-GCM de datos sensibles.
  - Tests de concurrencia e integración con Testcontainers.
