# 07 — Criterios de aceptación y tests

> Fuente: [01-PRD](01-PRD.md), [03-flujo-app](03-flujo-app.md), [05-esquema-backend](05-esquema-backend.md) y el plan original. Ante diferencias, prevalecen los documentos 01 a 07.
> Cada fase de [06-plan-implementacion](06-plan-implementacion.md) se verifica con los criterios de este documento antes de pasar a la siguiente. Formato: Dado / Cuando / Entonces.

## 0. Invariante de conciliación (se aplica a casi todos los tests)

Misma fórmula que en [02-TRD](02-TRD.md#5-invariante-de-conciliación) y [05](05-esquema-backend.md):

> **suma(saldos de todas las cuentas, incluida TREASURY) + montos debitados todavía no acreditados ni revertidos = 0**

Además, por cuenta: `balance = suma(ledger_entries.amount)`. Todo test de integración de Core/Transaction termina con `assertReconciliationOk()` (helper compartido por copia en cada proyecto): `ok = true`, `difference = 0`, sin `mismatches`.

## 1. Criterios por funcionalidad

### F1 — Registro y login (Fase 0, 2, 5)
- **Dado** que no hay sesión, **cuando** abro la app, **entonces** me redirige a Keycloak (Authorization Code + PKCE) y al volver tengo un JWT con `sub` y rol `USER`.
- **Dado** un registro nuevo en Keycloak, **cuando** termina, **entonces** el usuario tiene rol `USER` por defecto y no `ADMIN`.
- **Dado** un access token vencido (> 5 min), **cuando** el front hace una petición, **entonces** renueva con `updateToken` y no cierra la sesión; si la renovación falla, redirige al login conservando la ruta.

### F2 — Cuenta automática (Fase 1, 5)
- **Dado** un usuario sin cuenta, **cuando** `GET /api/accounts/me`, **entonces** 404 `ACCOUNT_NOT_FOUND`; **cuando** `POST /api/accounts`, **entonces** 201 con alias generado, `balance = 0`, `status = ACTIVE`.
- **Dado** un usuario con cuenta, **cuando** `POST /api/accounts` otra vez (o dos veces en paralelo), **entonces** 200 con la misma cuenta y existe una sola fila para ese `user_id`.
- **Dado** el alias generado, **cuando** lo cambio por uno libre, **entonces** 200 y `aliasEditable = false`; **cuando** intento otro cambio, **entonces** 409 `ALIAS_ALREADY_CHANGED`; con alias ocupado, 409 `ALIAS_TAKEN`.
- **Dado** una cuenta creada, **entonces** `account_number` está cifrado en la base (el valor almacenado no es un número de 22 dígitos) y el dueño lo ve completo por API.

### F3 — Ver saldo (Fase 1, 2, 5)
- **Dado** una cuenta con saldo 1.500,00, **cuando** `GET /api/accounts/me`, **entonces** `balance = "1500.00"`.
- **Dado** el usuario B, **cuando** pide `GET /api/accounts/{id de A}/movements`, **entonces** 404 (no 403).

### F4 — Cargar saldo demo (Fase 1, 5)
- **Dado** saldo 0, **cuando** `POST /api/accounts/me/demo-deposit` con 50.000 y una `Idempotency-Key`, **entonces** 201, saldo 50.000, y dos asientos `DEPOSIT` (TREASURY −50.000, cuenta +50.000).
- **Dado** el mismo request repetido con la misma clave, **entonces** 200 y el saldo sigue en 50.000 (un solo depósito).
- **Dado** la misma clave con otro monto, **entonces** 422 `IDEMPOTENCY_KEY_REUSED`.
- **Dado** 50.000.01, **entonces** 422 `DEMO_LIMIT_EXCEEDED`; **dado** saldo 180.000 y carga de 50.000, **entonces** 422 `BALANCE_CAP_EXCEEDED`.
- **Dado** un ADMIN, **cuando** deposita 400.000 con `/api/accounts/{id}/deposit`, **entonces** 201; con 1.000.001, 422 `AMOUNT_LIMIT_EXCEEDED`.
- **Dado** el depósito, **entonces** la conciliación sigue en `ok` y TREASURY queda con saldo negativo (permitido).

### F5 — Transferir (Fase 3, 4, 5)
- **Dado** A con 50.000 y B existente, **cuando** A hace `POST /api/transfers` de 1.500 con `Idempotency-Key`, **entonces** 202 con `status = PENDING` y, tras la Saga, `COMPLETED`; saldo A = 48.500, saldo B = +1.500; asientos `DEBIT` (−1.500) y `CREDIT` (+1.500); conciliación `ok`.
- **Dado** la misma `Idempotency-Key` y mismo payload, **cuando** se reenvía (o doble click), **entonces** 200 con la misma transferencia y solo una Saga ejecutada.
- **Dado** la misma clave con distinto monto, **entonces** 422 `IDEMPOTENCY_KEY_REUSED`; **dado** el header ausente, **entonces** 400.
- **Dado** `fromAccountId = toAccountId`, **entonces** 422 `SAME_ACCOUNT`; **dado** monto ≤ 0, con 3 decimales o > 1.000.000, **entonces** 422 (`INVALID_AMOUNT` / `AMOUNT_LIMIT_EXCEEDED`).
- **Dado** saldo insuficiente, **cuando** se procesa, **entonces** `FAILED` con `INSUFFICIENT_FUNDS`, sin asientos y saldos intactos.
- **Dado** que A usa como origen la cuenta de otro usuario, **entonces** `FAILED` con `NOT_OWNER` y no se mueve dinero.
- **Dado** el destino bloqueado al debitar, **entonces** `FAILED` con `DESTINATION_UNAVAILABLE`; **dado** el destino que se bloquea después del débito, **entonces** `CreditFailed` → `COMPENSATING` → `COMPENSATED`, con asiento `REVERSAL` y saldo de A = saldo inicial.
- **Dado** el origen bloqueado después del débito y un crédito fallido, **entonces** el refund igualmente se aplica (ignora `BLOCKED`).

### F6 — Estado en vivo (Fase 3, 5)
- **Dado** una transferencia en curso, **cuando** el front consulta `GET /api/transfers/{id}` cada 2 s, **entonces** muestra los pasos hasta un estado terminal y detiene el polling.
- **Dado** que pasan 60 s sin estado terminal, **entonces** la UI muestra el aviso "Sigue en proceso" sin error.
- **Dado** otro usuario, **cuando** pide la transferencia ajena, **entonces** 404 `TRANSFER_NOT_FOUND`.

### F7 — Historial (Fase 1, 5)
- **Dado** movimientos de varios tipos, **cuando** `GET /api/accounts/{id}/movements?page=0&size=20`, **entonces** vienen ordenados por fecha descendente, paginados, con `entryType`, `amount` con signo, `balanceAfter` y `counterpartyAlias`.
- **Dado** una cuenta sin movimientos, **entonces** `content = []` y la UI muestra el estado vacío.

### F8 — Transferencias rechazadas visibles (Fase 4, 5)
- **Dado** una transferencia `REJECTED`, **cuando** `GET /api/transfers?status=REJECTED`, **entonces** aparece con `failureReason = FRAUD_REJECTED` y la UI muestra el motivo en español; el USER **no** ve `fraudScore` (solo el ADMIN).

### F9 — Fraude (Fase 4)
- **Dado** un monto de 100.001, **entonces** `score = 70`, `REJECTED`, sin movimientos de dinero y conciliación `ok`.
- **Dado** un monto de 100.000 exactos a un destino conocido, **entonces** `APPROVED` (el límite es estricto: `>`).
- **Dado** 5 intentos previos del usuario en la última hora (sin importar su decisión) y transferencias completadas en 24 h que, con el monto actual, superan 300.000, **cuando** envía el 6.º intento, **entonces** se disparan VELOCITY (40) y DAILY_LIMIT (40): score 80 → `REJECTED`.
- **Dado** 5 intentos previos en la última hora, entre ellos rechazados, **cuando** envía otro, **entonces** VELOCITY se dispara (los intentos rechazados cuentan para la velocidad).
- **Dado** que una transferencia fue rechazada (por ejemplo 150.000 por `HIGH_AMOUNT`) y el usuario tiene 200.000 completadas en 24 h, **cuando** envía 90.000, **entonces** DAILY_LIMIT **no** se dispara (200.000 + 90.000 = 290.000 ≤ 300.000): **una transferencia rechazada no suma al acumulado de 24 h**. Lo mismo vale para una `FAILED`, `COMPENSATED` o todavía no completada.
- **Dado** `TransferCompleted` de 100.000, **entonces** `user_activity` registra la fila y el acumulado de 24 h del usuario aumenta en 100.000; un `TransferCompleted` duplicado no la duplica (`UNIQUE(transfer_id)`).
- **Dado** destino nuevo (20) + VELOCITY (40) = 60, **entonces** `APPROVED`.
- **Dado** `HIGH_AMOUNT` desactivada por el ADMIN, **cuando** se envía 150.000, **entonces** `APPROVED`.
- **Dado** `FraudCheckRequested` duplicado, **entonces** no se crea otra evaluación y se re-emite la misma decisión.

### F10 — Panel ADMIN (Fase 2, 4, 5)
- **Dado** un ADMIN, **cuando** lista `/api/admin/transfers?status=FAILED`, **entonces** ve todas las de ese estado (de cualquier usuario).
- **Dado** un USER, **cuando** llama a un endpoint ADMIN, **entonces** 403.
- **Dado** un ADMIN, **cuando** bloquea una cuenta, **entonces** 200, la cuenta rechaza débitos (`ACCOUNT_BLOCKED`) y queda un `audit_log`; no puede bloquear TREASURY (`SYSTEM_ACCOUNT_IMMUTABLE`).
- **Dado** una transferencia en `FAILED_MANUAL_REVIEW`, **cuando** el ADMIN la reanuda, **entonces** vuelve a `resume_status`, se reenvía el comando y termina bien; si no está en ese estado, 409.
- **Dado** un ADMIN, **cuando** cambia `weight`/`threshold`/`active` de una regla, **entonces** aplica a las evaluaciones siguientes sin redeploy.

### F11 — Conciliación (Fase 1, 3, 6)
- **Dado** un conjunto de depósitos y transferencias, **cuando** `GET /api/admin/reconciliation`, **entonces** `ok = true`, `difference = 0`.
- **Dado** una transferencia con el débito aplicado y el crédito pendiente, **entonces** `inFlightAmount` = su monto y `ok = true`.
- **Dado** un saldo alterado manualmente en la base (test de laboratorio), **entonces** `ok = false` y `mismatches` lo identifica.

### Seguridad transversal (Fase 2)
- **Dado** cualquier endpoint `/api/**`, **cuando** no hay token, **entonces** 401; con token vencido o firma inválida, 401; con `iss` incorrecto, 401.
- **Dado** el gateway, **cuando** el origen CORS no es el permitido, **entonces** se rechaza; **cuando** se excede el rate limit en `POST /api/transfers`, **entonces** 429.
- **Dado** los logs de cualquier servicio, **entonces** no contienen tokens, claves ni números de cuenta completos.
- **Dado** un acceso directo a un servicio (sin pasar por el gateway) con token válido, **entonces** funciona; sin token, 401 (cada servicio valida).

## 2. Tests automáticos

### 2.1 Unitarios (JUnit 5 + Mockito + AssertJ)
| Área | Casos |
| --- | --- |
| Fraude | Cada regla (borde estricto `>`), VELOCITY sobre `risk_assessments` (cuenta intentos rechazados), DAILY_LIMIT solo sobre `user_activity` (no suma rechazadas), suma de pesos, tope 100, umbral de rechazo 70, reglas inactivas |
| Saga | Cada transición válida de [03 §5](03-flujo-app.md) y cada transición inválida (se ignora); backoff de reintentos; expiración de `PENDING` vs no expiración de `FRAUD_APPROVED` |
| Dinero | Validación de montos (decimales, límites), redondeo prohibido, `BigDecimal` en todo el flujo |
| Cifrado | AES-GCM: ida y vuelta, IV distinto por valor, manipulación detectada, enmascarado |
| Idempotencia | Cálculo del `request_hash`; comparación de payloads |
| Conversor de roles | `realm_access.roles` → `ROLE_*` |

### 2.2 Integración con Testcontainers (Postgres 17 + RabbitMQ 4)
- **Core:** repositorios con Postgres real, constraints (`CHECK` de balance para USER, TREASURY negativo permitido, `UNIQUE` de ledger y `transfer_operations`), migraciones Flyway, outbox (`SKIP LOCKED`), conciliación.
- **Topología:** cada servicio declara solo sus colas y DLQ; los exchanges coinciden en argumentos (arrancar los servicios en cualquier orden no produce `PRECONDITION_FAILED`); un mensaje publicado antes de que exista la cola de su consumidor no se marca `SENT` y se entrega cuando el consumidor arranca.
- **Mensajería:** listeners de Core/Fraud/Transaction con RabbitMQ real: ack tras commit, reintentos y envío a DLQ tras 3 fallos, mensaje malformado a DLQ.
- **Outbox:** con RabbitMQ detenido, los eventos quedan `PENDING`; al volver se publican y pasan a `SENT`.
- **Saga completa** con los tres servicios (o con Core + Transaction + Fraud en un test de sistema): caso feliz, fraude rechazado, sin fondos, crédito fallido.

### 2.3 Concurrencia
- **50 débitos paralelos** de 1.000 sobre una cuenta con 30.000: exactamente 30 triunfan, 20 reciben `INSUFFICIENT_FUNDS`, saldo final 0, nunca negativo.
- **Transferencias cruzadas** A→B y B→A en paralelo (100 de cada una): sin deadlock, conciliación `ok`.
- **Crédito concurrente** al mismo destino desde 50 orígenes: saldo final exacto.
- **Doble envío simultáneo** con la misma `Idempotency-Key`: una sola transferencia, la segunda responde con la existente.
- **Dos refunds/créditos simultáneos** sobre la misma transferencia: solo uno aplica (`transfer_operations` serializa).

### 2.4 Resiliencia de la Saga
| Escenario | Resultado esperado |
| --- | --- |
| Comando `Debit` duplicado con **otro** `messageId` | No se debita dos veces; se re-emite `FundsDebited` |
| Comando duplicado con el **mismo** `messageId` (redelivery) | No se vuelve a aplicar; ack |
| Respuesta `FundsDebited` perdida | El job de reintentos reenvía el comando, Core re-emite la respuesta, la Saga avanza |
| Reply duplicado o fuera de orden en Transaction | Se ignora (ack + log) sin cambiar el estado |
| RabbitMQ detenido a mitad de la Saga y reiniciado | La transferencia termina en estado correcto, sin duplicados |
| Core caído (contenedor detenido) y reiniciado durante el débito | La transferencia termina; conciliación `ok` |
| Crash entre el cambio de estado y el envío | No hay mensaje sin commit: el outbox garantiza el envío al reiniciar |
| `CreditFailed` | `COMPENSATED`; saldo de origen restituido; conciliación `ok` |
| Fraude caído | `PENDING` expira a `FAILED` (`SAGA_TIMEOUT`) tras 5 min; sin dinero movido |
| Reintentos agotados | `FAILED_MANUAL_REVIEW`; reanudable por ADMIN |
| `FraudApproved` tardío después de expirar | Se ignora; la transferencia sigue `FAILED` |
| Outbox publicado pero sin marcar `SENT` (crash) | Se republica; el consumidor lo descarta/re-emite sin duplicar dinero |

### 2.5 Seguridad
- 401 sin token / vencido / firma inválida (por servicio y por gateway).
- 403 de USER en endpoints ADMIN (todos).
- 404 de USER sobre cuentas, movimientos y transferencias ajenas.
- Un token con rol `ADMIN` accede a los endpoints ADMIN; un USER nuevo no se vuelve ADMIN.
- Rate limit (429) y CORS en el gateway.
- Verificación de que los logs no contienen datos sensibles (test de captura de logs).
- Un test con **Keycloak real** (Testcontainers o el Compose): flujo de token real → llamada por el gateway.

### 2.6 End-to-end (Playwright, contra el stack completo de Compose)
1. Registro de usuario nuevo → cuenta creada → cargar 50.000 → transferir 1.500 a `user2` → `COMPLETED` → saldos correctos.
2. Transferencia de 150.000 → `REJECTED` con el mensaje de seguridad; saldo intacto.
3. Transferencia con saldo insuficiente → `FAILED` "Saldo insuficiente".
4. ADMIN: ver transferencias, desactivar una regla, bloquear una cuenta, ejecutar la conciliación (`ok`).
5. Móvil (375 px) y modo oscuro: el flujo 1 funciona y no hay scroll horizontal.
6. Accesibilidad básica con axe en Inicio, Transferir y Detalle (sin violaciones críticas).

### 2.7 Tests del frontend (Vitest + Testing Library)
Formateo de montos es-AR, validación de formularios, `AliasInput` (estados), `TransferStepper` (todos los estados), manejo de 401/403/404/429, `Idempotency-Key` reutilizada en reintentos y renovada tras éxito, `ThemeToggle`.

## 3. Verificación por fase (checklist)

| Fase | Se considera terminada si… |
| --- | --- |
| 0 | `docker compose up -d` → 3 servicios `healthy`; se loguea en Keycloak; `user1` tiene `USER`, `admin` tiene `ADMIN`; el realm se reimporta con `down -v` |
| 1 | Tests de Core (§2.1, §2.2 Core, §2.3 concurrencia) en verde; endpoints probados con Bruno; F2, F3 (parcial), F4, F11 |
| 2 | Tests de seguridad (§2.5) en verde; sin token todo da 401; USER solo ve lo suyo |
| 3 | F5 y §2.4 en verde; apagar y prender RabbitMQ en medio de una transferencia la deja completada; conciliación `ok` |
| 4 | F8, F9 en verde; la transferencia de 100.001 queda `REJECTED` sin mover plata; se quitó la aprobación simulada |
| 5 | F1, F2, F5–F7 desde el navegador; §2.6 casos 1–3 y §2.7 en verde; 375 px y modo oscuro |
| 6 | Clon limpio + `.env` + `docker compose up` funciona; CI en verde; §2.6 completo |
| 7 | URL pública con HTTPS; flujo completo; sin puertos internos expuestos; README con demo |
