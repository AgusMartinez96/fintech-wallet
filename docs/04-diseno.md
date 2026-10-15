# 04 — Brief de diseño

> Fuente: decisiones de producto (identidad "fintech sobrio azul/verde", modo claro y oscuro). **⚠️ SUPUESTO** = decisión propia donde el plan no definía nada.
> UI en español (es-AR). Nombre provisional de la marca: **FintechWallet** ⚠️ SUPUESTO.

## 1. Principios
1. **Confianza y claridad**: sobrio, mucho espacio en blanco, un solo color de acción por pantalla.
2. **El dinero se lee primero**: los montos son el elemento más grande y legible.
3. **Estado siempre visible**: toda transferencia muestra un estado entendible y el motivo cuando falla.
4. **Accesible**: contraste AA como mínimo, foco visible, no depender solo del color.

## 2. Paleta (hex) y uso

Se implementa como variables CSS (`--color-*`) en `:root` y `[data-theme="dark"]`, y se mapea en `tailwind.config` (`colors: { primary: 'var(--color-primary)', ... }`). El tema inicial sigue `prefers-color-scheme`; el usuario puede alternarlo y se guarda en `localStorage`.

| Token | Claro | Oscuro | Uso |
| --- | --- | --- | --- |
| `--color-bg` | `#F8FAFC` | `#0B1220` | Fondo de la app |
| `--color-surface` | `#FFFFFF` | `#111A2E` | Tarjetas, tablas, modales |
| `--color-surface-2` | `#F1F5F9` | `#16223A` | Inputs, filas alternas, skeletons |
| `--color-border` | `#E2E8F0` | `#1F2A44` | Bordes y separadores |
| `--color-text` | `#0F172A` | `#E5E7EB` | Texto principal |
| `--color-text-muted` | `#475569` | `#94A3B8` | Texto secundario, etiquetas |
| `--color-primary` | `#1D4ED8` | `#60A5FA` | Botón principal, enlaces, foco, ítem activo |
| `--color-primary-hover` | `#1E40AF` | `#93C5FD` | Hover del primario |
| `--color-on-primary` | `#FFFFFF` | `#0B1220` | Texto sobre primario |
| `--color-primary-soft` | `#DBEAFE` | `#16294D` | Fondos suaves, "en proceso" |
| `--color-success` | `#15803D` | `#4ADE80` | Ingresos, `COMPLETED`, confirmaciones |
| `--color-success-soft` | `#DCFCE7` | `#10301F` | Fondo de badge/alerta de éxito |
| `--color-danger` | `#B91C1C` | `#F87171` | Errores, `REJECTED`, `FAILED` |
| `--color-danger-soft` | `#FEE2E2` | `#3A1517` | Fondo de error |
| `--color-warning` | `#B45309` | `#FBBF24` | `COMPENSATED`, `COMPENSATING`, `FAILED_MANUAL_REVIEW`, cuentas bloqueadas |
| `--color-warning-soft` | `#FEF3C7` | `#3A2A0A` | Fondo de aviso |
| `--color-focus-ring` | `#1D4ED8` | `#93C5FD` | Anillo de foco (2 px + 2 px de offset) |

Reglas:
- Ingresos en el historial: `--color-success` con signo `+`; egresos: texto normal con signo `−`. El color nunca es el único indicador (siempre hay signo e ícono).
- Rojo solo para errores y rechazos; no se usa para un egreso común.
- Contraste: texto sobre fondo ≥ 4.5:1 (AA). Re-verificar cada par con una herramienta al implementar.

## 3. Tipografía
- Fuente: **Inter** (variable, `font-display: swap`), con *fallback* `system-ui, -apple-system, "Segoe UI", sans-serif`. ⚠️ SUPUESTO.
- Montos y cifras: `tabular-nums` para que se alineen.
- Formato de números: `Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS' })` → `$ 1.234,56`. Fechas: `Intl.DateTimeFormat('es-AR')` en la zona horaria del navegador (el backend entrega UTC).

| Estilo | Tamaño / interlineado | Peso | Uso |
| --- | --- | --- | --- |
| `display` | 36 / 40 px | 700 | Saldo en la tarjeta de Inicio |
| `h1` | 24 / 32 px | 600 | Título de pantalla |
| `h2` | 18 / 28 px | 600 | Secciones |
| `body` | 16 / 24 px | 400 | Texto general (mínimo 16 px en inputs para evitar zoom en iOS) |
| `small` | 14 / 20 px | 400/500 | Etiquetas, metadatos, tablas |
| `caption` | 12 / 16 px | 500 | Badges, ayudas |

## 4. Espaciado, forma y elevación
- Escala de espaciado de Tailwind (base 4 px); gutters de página 16 px en móvil y 24 px en escritorio; ancho máximo de contenido 960 px (admin: 1200 px).
- Radios: `8 px` controles, `12 px` tarjetas, `9999 px` badges.
- Sombras: solo en tarjetas elevadas y modales (`shadow-sm`/`shadow-lg`); en modo oscuro se reemplazan por borde.
- Iconos: **lucide-react**, 20 px, trazo 1.75. ⚠️ SUPUESTO.

## 5. Componentes reutilizables y variantes

| Componente | Variantes / estados | Notas |
| --- | --- | --- |
| `Button` | `primary`, `secondary` (borde), `ghost`, `danger`; tamaños `md` (44 px alto), `lg`; estados `loading` (spinner + deshabilitado), `disabled` | Objetivo táctil mínimo 44 px |
| `TextField` | `default`, `error` (mensaje debajo + `aria-invalid`), `success`, `disabled` | Etiqueta siempre visible, no solo *placeholder* |
| `AmountInput` | prefijo `$`, `inputmode="decimal"`, formato es-AR al perder foco, máx. 2 decimales | Muestra el límite aplicable como ayuda |
| `AliasInput` | estados `idle`, `checking`, `found`, `not-found`, `self` | Debounce 400 ms |
| `BalanceCard` | `loading` (skeleton), `loaded`, `error`; alias y CBU enmascarado (`****1234`) con "Copiar" | Saldo en `display` |
| `StatusBadge` | ver tabla 5.1 | Ícono + texto + color |
| `TransferStepper` | pasos *Seguridad → Débito → Crédito → Listo*; estados `done`, `active`, `pending`, `error`, `reverted` | Se actualiza con el polling; `aria-live="polite"` |
| `MovementRow` | `credit` (+, verde), `debit` (−), `reversal` (↩, ámbar), `deposit` | Alias contraparte, fecha, monto, saldo posterior (desktop) |
| `DataTable` | desktop: tabla con paginación; móvil: lista de tarjetas | |
| `Pagination` | anterior/siguiente + "Página X de Y" | |
| `Toast` | `success`, `error`, `info`; autocierre 5 s, `role="status"`/`"alert"` | |
| `Modal` | confirmación (bloquear cuenta, confirmar transferencia), foco atrapado y `Esc` | |
| `EmptyState` | ícono + texto + acción | "Todavía no hay movimientos" |
| `Skeleton` | línea, tarjeta, fila | Animación `pulse`, respeta `prefers-reduced-motion` |
| `ErrorBanner` | con botón "Reintentar" | Errores de red/5xx |
| `ThemeToggle` | claro / oscuro / sistema | |
| `NavBar` / `BottomNav` | escritorio: barra superior; móvil: barra inferior | |
| `Switch` | reglas de fraude | Con etiqueta accesible |

### 5.1 Estados de transferencia → badge

| Estado | Texto UI | Color | Ícono |
| --- | --- | --- | --- |
| `PENDING`, `FRAUD_APPROVED`, `DEBITED` | En proceso | `primary-soft` / `primary` | reloj (animado) |
| `COMPLETED` | Completada | `success-soft` / `success` | check |
| `REJECTED` | Rechazada por seguridad | `danger-soft` / `danger` | escudo con X |
| `FAILED` | Fallida | `danger-soft` / `danger` | X |
| `COMPENSATING` | Devolviendo dinero | `warning-soft` / `warning` | flecha circular |
| `COMPENSATED` | Devuelta | `warning-soft` / `warning` | flecha de retorno |
| `FAILED_MANUAL_REVIEW` | En revisión | `warning-soft` / `warning` | alerta |

Motivos de falla (`failure_reason` → texto): `FRAUD_REJECTED` "Rechazada por reglas de seguridad", `INSUFFICIENT_FUNDS` "Saldo insuficiente", `ACCOUNT_BLOCKED` "Tu cuenta está bloqueada", `DESTINATION_UNAVAILABLE` "La cuenta destino no está disponible", `NOT_OWNER` "Cuenta de origen inválida", `CURRENCY_MISMATCH` "Moneda no compatible", `SAGA_TIMEOUT` "No pudimos procesarla a tiempo. No se movió dinero.".

## 6. Cómo se ve cada pantalla

### 6.1 Login / Registro
Pantalla de Keycloak con tema propio mínimo (logo, color primario, fondo `--color-bg`). Textos en español (locale `es`).

### 6.2 Inicio (`/`)
- Encabezado con saludo "Hola, {nombre}" y `ThemeToggle`.
- `BalanceCard` grande: "Saldo disponible", monto en `display`, alias y CBU enmascarado con "Copiar".
- Fila de acciones: **Transferir** (primario), **Cargar saldo** (secundario), **Historial** (ghost).
- "Últimos movimientos": 5 `MovementRow`; enlace "Ver todo".
- Si el saldo es 0 y no hay movimientos: `EmptyState` "Cargá saldo demo para probar".

### 6.3 Transferir (`/transfer`)
- Formulario en una columna: `AliasInput`, `AmountInput`, descripción opcional (140 car., contador).
- Botón "Revisar" → resumen en `Modal` (destinatario, monto, saldo restante estimado) → "Confirmar".
- Errores por campo en línea; error general en `ErrorBanner`.
- Muestra "Saldo disponible: $ X" bajo el monto.

### 6.4 Detalle de transferencia (`/transfers/:id`)
- Cabecera con monto, destinatario y `StatusBadge`.
- `TransferStepper` vertical en móvil, horizontal en escritorio.
- Mensaje contextual (éxito, rechazo, devolución) con fondo suave.
- Metadatos: fecha, id (copiable), descripción.
- Si sigue en proceso tras 60 s: aviso informativo y botón "Volver al historial".

### 6.5 Historial (`/history`)
- Pestañas "Movimientos" y "Transferencias".
- Movimientos: lista de `MovementRow`, paginada de a 20.
- Transferencias: filtro por estado (chips: Todas, Completadas, Rechazadas, Fallidas, Devueltas); cada fila abre el detalle.

### 6.6 Cargar saldo demo (`/deposit`)
- `AmountInput` con atajos (10.000 / 25.000 / 50.000), texto "Máximo 50.000 por carga. Saldo máximo 200.000.", botón "Cargar". Éxito → toast + vuelta a Inicio.

### 6.7 Mi cuenta (`/account`)
- Alias (botón "Editar" una sola vez; después `disabled` con tooltip), CBU (enmascarado con "Mostrar"), estado (badge `Activa`/`Bloqueada`).

### 6.8 Admin
- Layout con menú lateral (escritorio) o selector superior (móvil): Transferencias, Fraude, Cuentas, Conciliación.
- **Transferencias:** `DataTable` con filtro de estado; fila `FAILED_MANUAL_REVIEW` con botón "Reanudar".
- **Fraude:** pestaña Evaluaciones (score, decisión, motivos) y pestaña Reglas (tarjetas con `Switch`, peso y umbral editable).
- **Cuentas:** tabla con alias, estado, saldo; acción "Bloquear/Desbloquear" con confirmación; botón "Depositar".
- **Conciliación:** botón "Ejecutar"; tres cifras (suma de saldos, en vuelo, diferencia) y banner verde `ok` / rojo con tabla de discrepancias.

## 7. Comportamiento en celular
- **Mobile first.** Breakpoints Tailwind: base (< 640 px), `sm` 640, `md` 768, `lg` 1024.
- Navegación: **barra inferior** fija con *Inicio, Transferir, Historial, Cuenta* (+ *Admin* si corresponde); en `md+` pasa a barra superior.
- Una sola columna; botones principales a ancho completo (respetando `env(safe-area-inset-bottom)`).
- Tablas → listas de tarjetas (2–3 datos clave por fila y el resto en el detalle).
- Inputs con `inputmode`/`autocomplete` correctos; fuente de input ≥ 16 px.
- `viewport` con `width=device-width, initial-scale=1`; sin scroll horizontal; objetivos táctiles ≥ 44 px.
- Banner "Sin conexión" cuando `navigator.onLine` es falso; el formulario de transferencia se bloquea.
- Animaciones desactivadas con `prefers-reduced-motion`.

## 8. Accesibilidad
- Contraste AA, foco visible en todo elemento interactivo, orden de tabulación lógico.
- Etiquetas asociadas a inputs; errores con `aria-describedby` y `role="alert"`.
- `TransferStepper` y estados en vivo con `aria-live="polite"`.
- Montos con `aria-label` con el valor formateado.
- Idioma del documento `lang="es-AR"`.
