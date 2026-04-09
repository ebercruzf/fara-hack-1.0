# Manual de Usuario — Fara-Hack 1.0

**Author:** Eber Cruz | **Version:** 1.0.0

> **Audiencia:** cualquiera que quiera correr Fara-Hack desde cero
> (jurado del hackathon, contributor nuevo, vos mismo en una semana
> cuando no te acordés del setup).
> **Objetivo:** desde 0 hasta ver el primer email recibido, en menos
> de 15 minutos.

---

## Tabla de contenidos

1. [Requisitos previos](#1-requisitos-previos)
2. [Setup en 4 pasos](#2-setup-en-4-pasos)
3. [Cómo enviar un bug report](#3-cómo-enviar-un-bug-report)
4. [Cómo ver el Live Feed (reasoning trace)](#4-cómo-ver-el-live-feed-reasoning-trace)
5. [Cómo cerrar un ticket y disparar Step 5](#5-cómo-cerrar-un-ticket-y-disparar-step-5)
6. [Smoke tests de validación](#6-smoke-tests-de-validación)
7. [Troubleshooting](#7-troubleshooting)
8. [Apagar y limpiar](#8-apagar-y-limpiar)

---

## 1. Requisitos previos

| Requisito | Versión | Cómo verificar |
|---|---|---|
| Docker Desktop (mac/win) o Docker Engine (linux) | ≥ 24.0 | `docker --version` |
| Docker Compose | ≥ 2.20 | `docker compose version` |
| Ollama corriendo en el host | ≥ 0.4 | `curl http://localhost:11434/api/tags` |
| Modelo `qwen3.5:35b-a3b` pulleado | ~22 GB | `ollama list \| grep qwen3.5:35b-a3b` |
| RAM libre | ≥ 32 GB recomendado (24 GB para el modelo + 8 GB compose) | `free -h` (linux) o Activity Monitor (mac) |
| Espacio en disco | ≥ 10 GB libres | `df -h` |
| Puerto 8080 libre en el host | — | `lsof -i :8080` (no debe haber output) |

**Si te falta Ollama:**

```bash
# macOS
brew install ollama
ollama serve &
ollama pull qwen3.5:35b-a3b   # ~22 GB, tarda 10-30 min según red

# Linux
curl -fsSL https://ollama.com/install.sh | sh
sudo systemctl start ollama
ollama pull qwen3.5:35b-a3b
```

**Cuenta Gmail con App Password** (para que el agente envíe correos
reales):
1. Habilitar 2FA en https://myaccount.google.com/security
2. Generar App Password en https://myaccount.google.com/apppasswords
3. Copiar la contraseña de 16 caracteres (con espacios)
4. La usás en el `.env` paso siguiente

---

## 2. Setup en 4 pasos

```bash
# 1. Clonar
git clone https://github.com/<owner>/fara-hack-1.0
cd fara-hack-1.0

# 2. Copiar template de variables y completar
cp .env.example .env
$EDITOR .env
```

En `.env`, llenar **estas variables mínimas**:

```bash
# LLM (apunta a Ollama del host)
LLM_SERVER_URL=http://host.docker.internal:11434
LLM_MODEL_NAME=qwen3.5:35b-a3b
OPENAI_COMPAT_BASE_URL=http://host.docker.internal:11434/v1
OPENAI_COMPAT_MODEL=qwen3.5:35b-a3b

# Gmail (para que el agente notifique al on-call)
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=tu-cuenta@gmail.com
MAIL_PASSWORD=abcd efgh ijkl mnop          # ← App Password de 16 chars
MAIL_IMAP_HOST=imap.gmail.com
MAIL_IMAP_PORT=993
MAIL_SENDER=tu-cuenta@gmail.com
MAIL_SENDER_NAME=fara-agent

# A quién mandar la notificación (si se omite, usa el reporter email)
ONCALL_EMAIL=oncall@tu-empresa.com
```

```bash
# 3. Construir el jar local (no se incluye en el repo)
mvn -B package -DskipTests
# Esto produce target/fara-hack-1.0.jar (~191 MB)

# 4. Levantar el stack
docker compose up -d --build
```

**Verificación rápida** (debería responder en < 10 s):

```bash
# Los 3 contenedores arriba
docker compose ps

# Endpoint salud
curl http://localhost:8080/api/health
# Esperado: {"status":"healthy"}

# Página principal
open http://localhost:8080/
```

Si los 3 servicios están `Up` y `healthy`, ya estás listo.

---

## 3. Cómo enviar un bug report

### Opción A — desde la UI (recomendado para el demo)

1. Abrí http://localhost:8080/ en el browser
2. Llenar el formulario:
   - **Title:** una línea descriptiva del problema
   - **Reporter Email:** tu email (al que llegará la notificación de
     resolución cuando cierres el ticket en Step 5)
   - **Description:** lo que el usuario reporta
   - **Stack trace:** opcional pero recomendado para que el agente
     pueda extraer file paths reales
   - **Attachment:** opcional — si subís una imagen, se procesa con el
     vision adapter (qwen3.5 multimodal)
3. Clic en **Submit**
4. La página inmediatamente abre el Live Feed mostrando el reasoning
   en tiempo real

### Opción B — desde curl (para scripting / pruebas)

```bash
curl -X POST http://localhost:8080/api/triage/report \
  -H "Content-Type: application/json" \
  -d '{
    "reporterEmail": "alice.qa@eshop.test",
    "title": "NullReferenceException in checkout when applying discount code",
    "description": "Customer reports applying discount code SAVE10 at checkout throws 500. Started after deploy v2.14.3. Affecting ~30% of checkouts.",
    "stackTrace": "System.NullReferenceException at DiscountService.ApplyDiscount in /repo/src/Services/Catalog.API/Services/DiscountService.cs:line 142"
  }'

# Respuesta inmediata (< 200 ms):
# {"status":"accepted","correlationId":"<uuid>","wsUrl":"/ws/events?correlationId=<uuid>"}
```

Guardá el `correlationId` — lo usás para subscribir al Live Feed en el
siguiente paso.

### Lo que pasa en background (~2-3 min)

1. **Step 0 — Vision** (si hay attachment): qwen3.5 multimodal procesa
   la imagen y devuelve un summary técnico.
2. **Step 2 — Triage Coordinator**: clasifica severity P0–P3, lee el
   archivo afectado del repo `mock-eshop` vía `fs_read`.
3. **Step 2.5 + 6 — Forensic ║ Mitigation** (paralelo en Virtual
   Threads): forensic busca duplicados, mitigation propone fix.
4. **Step 6 audit (experimental)**: si el mitigation generó un patch,
   `SentinelDiffAdapter` lo audita en 6 dimensiones.
5. **Step 3+4 — Triage Broker**: crea ticket `FH-N` en `TicketStore` y
   manda email vía Gmail SMTP.
6. **DONE event** publicado al Live Feed.

---

## 4. Cómo ver el Live Feed (reasoning trace)

### Desde la UI

La UI Angular abre automáticamente la conexión WebSocket cuando enviás
el form. Verás un panel a la derecha con líneas tipo:

```
[10:42:15] STEP_1_RECEIVED   controller       Bug report received from alice.qa@eshop.test
[10:42:15] STEP_2_REASONING  triage-coord     Spawning 4-agent pipeline
[10:42:33] STEP_2_REASONING  triage-coord     Coordinator output (487 chars)
[10:42:33] STEP_2_5_FORENSIC forensic-analyst Spawning parallel branch
[10:43:21] STEP_2_5_FORENSIC forensic-analyst Forensic done
[10:43:48] STEP_6_MITIGATION mitigation-eng   Mitigation done
[10:43:48] STEP_6_MITIGATION sentinel-auditor Auditing candidate patch
[10:43:48] STEP_6_MITIGATION sentinel-auditor APPROVED — 3 lines, 1 files
[10:44:11] STEP_4_NOTIFY     triage-broker    Broker dispatched
[10:44:11] STEP_3_TICKET     integration-broker Ticket created: FH-2
[10:44:11] DONE              controller       Pipeline complete. Ticket=FH-2, severity=P1
```

### Desde la línea de comandos (sin UI)

```bash
# Tail los logs estructurados del backend
docker compose logs -f fara-hack | grep -E "STEP_|DIRECT-AGENT|EMAIL_SEND|SENTINEL"
```

### Desde wscat (websocket nativo)

```bash
# Si tenés wscat (npm install -g wscat o brew install websocat)
CID="<el correlationId del POST>"
wscat -c "ws://localhost:8080/ws/events?correlationId=$CID"
```

---

## 5. Cómo cerrar un ticket y disparar Step 5

El **Step 5 — Notify reporter on resolved** se dispara automáticamente
cuando un ticket transiciona a `RESOLVED`. El `TriageStatusWatcher` polea
cada 30 segundos y dispara el callback de notificación al reporter.

### Cerrar manualmente desde la API

```bash
# Listar tickets
curl -s http://localhost:8080/api/triage/tickets | python3 -m json.tool

# Cerrar un ticket por id
curl -X POST http://localhost:8080/api/triage/tickets/FH-1/resolve
# {"status":"resolved","ticketId":"FH-1"}
```

### Cerrar desde la UI

1. En el dashboard de tickets, clic en el ticket `FH-1`
2. Botón **"Mark Resolved"**
3. Esperar hasta 30 segundos
4. Revisar la bandeja del `reporterEmail` original — debería llegar un
   email con asunto `[FH-1] Your bug report is fixed` o similar

---

## 6. Smoke tests de validación

Los 5 smoke tests críticos. Los 3 primeros son **bloqueantes** — si
fallan, el demo no funciona.

### Test 1 — CORS preflight (smoke §1 del PENDIENTES)

```bash
curl -s -o /dev/null -w "OPTIONS /api/triage/report → HTTP %{http_code}\n" \
  -X OPTIONS http://localhost:8080/api/triage/report \
  -H "Origin: http://localhost:8080" \
  -H "Access-Control-Request-Method: POST"
# Esperado: HTTP 200
```

### Test 2 — WebSocket idle timeout (smoke §2 del PENDIENTES)

```bash
CID=$(curl -s -X POST http://localhost:8080/api/triage/report \
  -H "Content-Type: application/json" \
  -d '{"reporterEmail":"smoke@test","title":"WS idle test","description":"verify ws survives 90s"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['correlationId'])")
echo "cid=$CID"

# Si tenés wscat:
wscat -c "ws://localhost:8080/ws/events?correlationId=$CID"
# Mantenelo abierto > 90 segundos. Si no se cierra → ✅
```

### Test 3 — Bind mount del repo

```bash
# Desde dentro del container fara-hack
docker compose exec fara-hack ls -la /repo/src/Services/Catalog.API/Services/
# Esperado: ver DiscountService.cs (NO un directorio vacío)

docker compose exec fara-hack sh -c \
  "awk 'NR==141||NR==142' /repo/src/Services/Catalog.API/Services/DiscountService.cs"
# Esperado:
#         // ⚠ BUG: NullReferenceException here when `code` is null
#         return originalPrice * (1 - (code.Percentage / 100));
```

### Test 4 — Submit + email real (validación end-to-end completa)

```bash
curl -X POST http://localhost:8080/api/triage/report \
  -H "Content-Type: application/json" \
  -d '{
    "reporterEmail": "tu-email@gmail.com",
    "title": "NullReferenceException in checkout",
    "description": "Smoke test end-to-end",
    "stackTrace": "at DiscountService.ApplyDiscount in /repo/src/Services/Catalog.API/Services/DiscountService.cs:line 142"
  }'

# Esperar ~3 minutos. Verificar:
# 1. Logs muestran [EMAIL_SEND] Correo enviado a: <ONCALL_EMAIL>
# 2. La bandeja de ONCALL_EMAIL recibió el correo
# 3. curl /api/triage/tickets muestra el ticket FH-N
```

### Test 5 — Step 5 (notify reporter on resolved)

```bash
# Después del test 4, marcar como resolved
curl -X POST http://localhost:8080/api/triage/tickets/FH-1/resolve

# Esperar hasta 30 segundos
docker compose logs fara-hack --since=1m | grep STEP_5_REPORTER_NOTIFIED
# Esperado: ver el evento STEP_5

# Verificar la bandeja del reporterEmail original
```

---

## 7. Troubleshooting

### "fara-hack-web is unhealthy"

**Causa:** Nginx healthcheck contra `localhost` resuelve a `::1` (IPv6)
pero Nginx solo escucha en `0.0.0.0:80` (IPv4).

**Fix:** ya está aplicado en `web/Dockerfile` línea 41
(`wget http://127.0.0.1/`). Si lo seguís viendo, rebuild la imagen:

```bash
docker compose down
docker compose up -d --build
```

### "OPTIONS /api/triage/report → HTTP 404"

**Causa:** Javalin sin CORS rule registrada.

**Fix:** ya está aplicado en `BugReportController.start()` con
`cfg.bundledPlugins.enableCors(...)`. Verificar que el jar está actualizado:

```bash
stat -f "%Sm" target/fara-hack-1.0.jar  # mac
stat target/fara-hack-1.0.jar           # linux
# Si la fecha es vieja, mvn package + docker compose up --build
```

### "Pipeline tarda 5+ minutos"

**Causa:** Ollama swappeando entre modelos, GPU/CPU saturada, o el
modelo no está pulleado.

**Fix:**

```bash
# Verificar que el modelo está
ollama list | grep qwen3.5:35b-a3b

# Verificar que Ollama responde rápido
time curl -s http://localhost:11434/api/tags > /dev/null

# Verificar que el container ve a Ollama
docker compose exec fara-hack sh -c "wget -qO- http://host.docker.internal:11434/api/tags | head -c 200"
```

### "El email no llega Y la severity es P2 o P3"

**Causa: comportamiento esperado, NO es un bug.** El `triage-broker`
solo dispara `email_send` cuando `severity ∈ {P0, P1}`. Para `P2`/`P3`
devuelve el literal `NO_EMAIL` sin invocar la herramienta. Esto está
declarado en `workspace/.fararoni/config/agentes/triage-broker-agent.yaml`
regla 3 del `systemPrompt`:

```yaml
3. Email solo si severity ∈ {P0, P1}. Para P2/P3 devolvés el literal
   `NO_EMAIL` sin llamar a ninguna tool.
```

**Cómo verificar en logs:**

```bash
docker compose logs fara-hack --since=5m | grep "Broker dispatched"
# Si ves: "Broker dispatched (8 chars)" → 8 = len("NO_EMAIL"), severity P2/P3
# Si ves: "Broker dispatched (200+ chars)" → email payload real, severity P0/P1
```

**Por qué la regla:** evitar despertar al on-call de madrugada por
bugs cosméticos. Es una práctica SRE estándar — los runbooks de PagerDuty
recomiendan paginar solo por severidades altas.

**Si necesitás forzar email para todas las severities** (debug del demo):
editá `triage-broker-agent.yaml` regla 3 y restart:
```bash
docker compose restart fara-hack
```

**Cómo asegurar que tu próximo bug se clasifique como P0/P1:**
incluí lenguaje fuerte en el `description` que el coordinator
detecte como crítico:
- "**production outage**", "**data loss**", "**payment failure**" → P0
- "**500 error**", "**critical path**", "**major feature broken**",
  "**~30% of checkouts**" → P1

### "Email no llega Y severity es P0/P1"

**Causa probable:** Gmail App Password mal configurado o 2FA no activado.

**Fix:**

```bash
# Verificar las env vars dentro del container
docker compose exec fara-hack env | grep MAIL_

# Probar las credenciales con un cliente IMAP/SMTP de prueba
# o usar swaks (apt install swaks) para validar SMTP a mano:
swaks --to oncall@tu-empresa.com \
      --from $MAIL_USERNAME \
      --server smtp.gmail.com:587 \
      --auth LOGIN \
      --auth-user $MAIL_USERNAME \
      --auth-password "$MAIL_PASSWORD" \
      --tls
```

### "El agente devuelve prosa en vez de JSON"

**Estado conocido (V1.0):** el `mitigation-engineer` corre con
`qwen3.5:35b-a3b` y a veces devuelve prosa explicativa en su respuesta
final post-tool, en lugar del JSON estricto. Esto rompe el `Sentinel
audit` que lo skipea con "could not parse" graceful.

**Impacto:** ninguno en los 5 mandatory steps del hackathon. El email,
ticket, y notificación de resolución funcionan igual. Solo la auditoría
del patch (Step 6 differentiator) queda en modo degradado.

**Fix planeado V1.1:** parchar `OpenAICompatibleClient` del core para
soportar Ollama JSON mode (`response_format: {"type": "json_object"}`).
Ver `ROADMAP.md` §2.1.

### "ArcadeDB no persiste tickets"

**Estado conocido (V1.0):** `TicketStore` es in-memory
(`ConcurrentHashMap`). Reiniciar el container pierde los tickets.
`ArcadeDbService` existe en el core pero no se instancia.

**Fix planeado V1.1:** wirear `ArcadeDbService` en `HackBootstrap`. Ver
`ROADMAP.md` §2.4.

---

## 8. Apagar y limpiar

```bash
# Parar los containers, mantener volúmenes
docker compose down

# Parar + borrar volúmenes (NATS data, mcp-fs-sandbox)
docker compose down -v

# Limpiar todo (incluyendo imágenes)
docker compose down -v --rmi local

# Borrar el jar local
rm target/fara-hack-1.0.jar
```

---

## 9. Documentos relacionados

- [`FLUJO-FARA-HACK.md`](FLUJO-FARA-HACK.md) — flujo conceptual con flowchart Mermaid
- [`DIAGRAMA-SECUENCIA.md`](DIAGRAMA-SECUENCIA.md) — sequence diagram completo
- [`../ROADMAP.md`](../ROADMAP.md) — tablero de remates pendientes
- [`PITCH.md`](PITCH.md) — script del demo video de 3 minutos
- [`../QUICKGUIDE.md`](../QUICKGUIDE.md) — quickstart oficial del hackathon
- [`PENDIENTES-SMOKE-TEST-2026-04-08.md`](PENDIENTES-SMOKE-TEST-2026-04-08.md) — smoke test detallado pre-grabación

`#AgentXHackathon`
