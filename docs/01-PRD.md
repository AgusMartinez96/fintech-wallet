# 01 — PRD: Requisitos de producto

> Fuente original: [plan-proyecto.md](plan-proyecto.md). Ante diferencias, prevalecen los documentos 01 a 07.
> Marcas: **⚠️ SUPUESTO** = decisión razonable tomada donde el plan no definía nada.

## 1. Resumen

**FintechWallet** (nombre provisional ⚠️ SUPUESTO) es una billetera virtual en pesos argentinos (ARS) donde cada usuario tiene una cuenta, puede cargarse saldo de prueba y transferir a otra cuenta por su alias. Las transferencias atraviesan tres microservicios (Core-Banking, Transaction, Fraud-Detection) coordinados por RabbitMQ con una Saga orquestada, Outbox e idempotencia, de modo que **el dinero nunca se pierde ni se duplica**, aunque un servicio o el broker se caigan a mitad de la operación. Es un proyecto de portafolio que demuestra seguridad (OAuth2/JWT), consistencia distribuida, aislamiento transaccional y un producto completo (gateway, frontend React, Docker Compose y deploy online).

## 2. Problema y usuarios

**Problema.** En un sistema distribuido, mover dinero entre cuentas implica varios pasos (analizar fraude, debitar, acreditar) en servicios distintos. Si un paso falla o un mensaje se repite, el sistema puede crear o destruir dinero. El producto resuelve esto con garantías demostrables (ledger de doble entrada, conciliación, tests de concurrencia y resiliencia).

| Usuario | Quién es | Qué necesita |
| --- | --- | --- |
| **USER** | Persona registrada (incluye al reclutador que prueba la demo) | Ver saldo, cargar saldo demo, transferir por alias, ver historial y el estado de cada transferencia, entender por qué una transferencia fue rechazada |
| **ADMIN** | Operador de la plataforma | Ver todas las transferencias, administrar reglas de fraude, bloquear/desbloquear cuentas, depositar sin tope de demo, correr la conciliación contable, reanudar transferencias trabadas |

## 3. Funcionalidades priorizadas

### Imprescindible (MVP)
| ID | Funcionalidad | Rol |
| --- | --- | --- |
| F1 | Registro e inicio de sesión (Keycloak, Authorization Code + PKCE) | USER |
| F2 | Cuenta única por usuario, creada automáticamente en el primer login, con alias generado y CBU simulado | USER |
| F3 | Ver saldo y datos de mi cuenta | USER |
| F4 | "Cargar saldo demo" (autoservicio con tope) | USER |
| F5 | Buscar destinatario por alias y transferir (con `Idempotency-Key`) | USER |
| F6 | Estado de la transferencia en vivo (polling) y detalle | USER |
| F7 | Historial de movimientos de mi cuenta (ledger), paginado | USER |
| F8 | Ver transferencias rechazadas por fraude, fallidas o devueltas, con el motivo | USER |
| F9 | Análisis de fraude con reglas configurables | sistema |
| F10 | Panel ADMIN: transferencias (filtro por estado), evaluaciones y reglas de fraude, cuentas (bloquear/desbloquear), depósito libre | ADMIN |
| F11 | Conciliación contable (ledger vs saldos vs transferencias en vuelo) | ADMIN |

### Deseable
- Docker Compose completo con healthchecks y un solo comando para levantar todo.
- CI en GitHub Actions (compilar y testear).
- `audit_log` de operaciones ADMIN.
- Reanudar transferencias en `FAILED_MANUAL_REVIEW` desde el panel ADMIN.
- Trazas distribuidas (Micrometer Tracing + Zipkin).
- Edición única del alias.

### Futuro
- Pasarela de pagos: comercios (`Merchant`), API key, `POST /api/payments`, webhook firmado HMAC.
- Decisión de fraude `REVIEW` con revisión manual.
- Multimoneda y conversión.
- Análisis de fraude retrospectivo que bloquee cuentas automáticamente.
- Notificaciones (email/push).

## 4. Fuera del alcance

- Dinero real, integración bancaria real, KYC, cumplimiento regulatorio.
- Múltiples cuentas por usuario (⚠️ SUPUESTO: una por usuario).
- Transferencia por CBU (solo por alias).
- Multimoneda (solo ARS).
- App móvil nativa (la web es responsive).
- Verificación de email y recuperación de contraseña personalizadas (se usan las de Keycloak; ⚠️ SUPUESTO: verificación de email desactivada).
- Alta disponibilidad, escalado horizontal y Kubernetes.

## 5. Reglas de negocio clave

| Regla | Valor |
| --- | --- |
| Moneda | ARS únicamente |
| Tope duro de la API por operación | 1.000.000 (422 si se supera) |
| Regla de fraude por monto | Rechaza por encima de 100.000 (editable en el panel ADMIN) |
| Carga de saldo demo (USER) | Hasta 50.000 por carga; el saldo resultante no puede superar 200.000 |
| Depósito ADMIN | Sin tope de demo; sí aplica el tope de 1.000.000 por operación |
| Decimales | Montos con 2 decimales (se almacenan como `NUMERIC(19,4)`) |
| Destino de una transferencia | Solo por alias; no se puede transferir a la propia cuenta |
| Consistencia | La conciliación debe cumplir siempre el invariante del ledger (ver [02-TRD](02-TRD.md#5-invariante-de-conciliación)) |

## 6. Métricas de éxito (demostrables)

1. Un usuario nuevo se registra, carga saldo demo y transfiere a otro en menos de 2 minutos desde el navegador.
2. 50 débitos concurrentes sobre una cuenta dejan el saldo exacto y nunca negativo.
3. Apagar RabbitMQ en medio de una transferencia y volver a encenderlo: la transferencia termina bien, sin duplicar ni perder dinero.
4. Una transferencia por encima de 100.000 queda `REJECTED` sin mover dinero.
5. Un crédito fallido deja la transferencia en `COMPENSATED` y el saldo de origen intacto.
6. La conciliación devuelve `ok = true` después de cualquier suite de tests.
7. `git clone` + `.env` + `docker compose up` deja todo funcionando; y existe un deploy público con HTTPS.
