# Diagrama de Secuencia — Fara-Hack 1.0

**Author:** Eber Cruz | **Version:** 1.0.0

> Diagrama de secuencia **end-to-end** del flujo de triaje SRE.
> Refleja el comportamiento real verificado en logs el 2026-04-08.
> Renderizable directamente en GitHub, GitLab, Mermaid Live Editor, o
> draw.io (Arrange → Insert → Advanced → Mermaid).

---

## 1. Diagrama principal — flujo completo (5 mandatory steps + multimodal + parallel branch)

```mermaid
sequenceDiagram
    autonumber
    actor Reporter as Reporter<br/>(browser)
    participant UI as Angular SPA<br/>(:8080 via Nginx)
    participant CTRL as BugReportController<br/>(Javalin :8080 internal)
    participant WS as WebSocket Live Feed<br/>(/ws/events)
    participant VISION as LlmTriageClient<br/>(Vision Adapter)
    participant DAE as DirectAgentExecutor<br/>(core)
    participant TC as triage-coordinator<br/>(agent)
    participant FA as forensic-analyst<br/>(agent — parallel)
    participant ME as mitigation-engineer<br/>(agent — parallel)
    participant SDA as SentinelDiffAdapter<br/>(audit, experimental)
    participant TB as triage-broker<br/>(agent)
    participant TS as TicketStore<br/>(in-memory)
    participant SMTP as Gmail SMTP<br/>(EmailTransportService)
    participant WATCH as TriageStatusWatcher<br/>(SovereignActor poll 30s)

    Note over Reporter,UI: Step 1 — Submit via UI

    Reporter->>UI: Llena form (title, desc, stack, opt. screenshot)
    UI->>CTRL: POST /api/triage/report (multipart)
    CTRL->>CTRL: validate + generate correlationId (UUID)
    CTRL-->>UI: 202 Accepted {correlationId, wsUrl}
    UI->>WS: WebSocket connect (?correlationId=…)
    WS-->>UI: CONNECTED envelope
    UI-->>Reporter: muestra "Procesando…" + Live Feed

    Note over CTRL,VISION: Step 0 — Vision Adapter (only if attachment)

    alt report.hasAttachment() && llm.isEnabled()
        CTRL->>VISION: triage(report, base64, mime)
        VISION->>VISION: build OpenAI vision payload (image_url)
        VISION->>SMTP: (uses Ollama, not SMTP — diagram label simplified)
        Note right of VISION: POST /v1/chat/completions<br/>model=qwen3.5:35b-a3b<br/>multimodal text + image
        VISION-->>CTRL: LlmTriageResult{severity, summary, areas}
        CTRL->>WS: publishTrace STEP_0_VISION
    end

    Note over CTRL,DAE: Step 2 — Triage Coordinator (sequential gate)

    CTRL->>CTRL: build coreInput (bug report + visionEvidence)
    CTRL->>WS: publishTrace STEP_2_REASONING "Spawning 4-agent pipeline"
    CTRL->>DAE: runDirectAgent(triage-coordinator, coreInput)
    DAE->>DAE: filter tools to allowedTools=[fs_read, code_search]
    DAE->>TC: execute(systemPrompt, coreInput, tools)
    Note right of TC: tool_choice:auto<br/>POST /v1/chat/completions
    TC-->>DAE: tool_call: fs_read("/repo/...")
    DAE->>DAE: ToolExecutor.executeTool(fs_read)
    DAE-->>TC: tool result: file content (6955 chars)
    TC-->>DAE: final content (severity, summary, signals)
    DAE-->>CTRL: triageJson
    CTRL->>WS: publishTrace STEP_2_REASONING "Coordinator output"

    Note over CTRL,ME: Step 2.5 + 6 — Forensic ║ Mitigation (Virtual Threads parallel)

    CTRL->>CTRL: spawn Thread.ofVirtual().name("forensic-…")
    CTRL->>CTRL: spawn Thread.ofVirtual().name("mitigation-…")
    CTRL->>WS: publishTrace STEP_2_5_FORENSIC "parallel branch"

    par Forensic branch
        CTRL->>DAE: runDirectAgent(forensic-analyst, contextWithCoord)
        DAE->>FA: execute(systemPrompt, ctx, tools)
        Note right of FA: tools allowed = [arcadedb_query,<br/>forensic_memory_search]<br/>0/2 registered → graceful
        FA-->>DAE: text response (no tool calls available)
        DAE-->>CTRL: forensicJson
    and Mitigation branch
        CTRL->>DAE: runDirectAgent(mitigation-engineer, contextWithCoord)
        DAE->>ME: execute(systemPrompt, ctx, tools=[fs_read])
        ME-->>DAE: tool_call: fs_read("/repo/.../DiscountService.cs")
        DAE-->>ME: file content (the buggy file with line 142)
        ME-->>DAE: final content (mitigation proposal)
        DAE-->>CTRL: mitigationJson
    end

    CTRL->>WS: publishTrace STEP_2_5_FORENSIC "Forensic done"
    CTRL->>WS: publishTrace STEP_6_MITIGATION "Mitigation done"

    Note over CTRL,SDA: Step 6 audit — Sentinel verdict (experimental)

    alt config.mitigationEnabled && mitigationJson parses
        CTRL->>SDA: extractJsonBlock(mitigationJson) → audit(patch)
        alt patch is APPROVED
            SDA-->>CTRL: Verdict.Approved{linesChanged, filesTouched}
            CTRL->>CTRL: sentinelVerified = true
            CTRL->>WS: publishTrace STEP_6_MITIGATION "APPROVED"
        else patch is REJECTED
            SDA-->>CTRL: Verdict.Rejected{rule, reason, evidenceLine}
            CTRL->>WS: publishTrace STEP_6_MITIGATION "REJECTED"
            Note right of CTRL: kill-switch — patch discarded,<br/>ticket goes summary-only
        end
    else mitigation returned prose / unparseable
        CTRL->>WS: publishTrace STEP_6_MITIGATION "skipping audit (graceful)"
    end

    Note over CTRL,SMTP: Step 3+4 — Triage Broker dispatches notifications

    CTRL->>DAE: runDirectAgent(triage-broker, brokerInput)
    DAE->>TB: execute(systemPrompt, brokerInput, tools=[email_send,...])
    TB-->>DAE: tool_call: email_send(to, subject, body)
    DAE->>SMTP: EmailTransportService.sendEmail(to, subj, body)
    Note right of SMTP: Gmail SMTP :587 STARTTLS<br/>credentials from MAIL_* env vars
    SMTP-->>DAE: 250 OK + messageId
    DAE-->>TB: tool result: "CORREO ENVIADO"
    TB-->>DAE: final content
    DAE-->>CTRL: brokerJson
    CTRL->>WS: publishTrace STEP_4_NOTIFY "Broker dispatched"

    CTRL->>TS: store.create(cid, reporter, title, body, severity, …)
    TS-->>CTRL: Ticket{ticketId="FH-N", state=OPEN, …}
    CTRL->>WS: publishTrace STEP_3_TICKET "FH-N created"

    CTRL->>WS: publishTrace DONE "Pipeline complete"
    UI-->>Reporter: Live Feed muestra DONE + ticket FH-N

    Note over WATCH,SMTP: Step 5 — Notify reporter on RESOLVED (async)

    loop every 30 seconds
        WATCH->>TS: store.findResolved(since=lastCheck)
        alt new RESOLVED transitions
            TS-->>WATCH: List<Ticket>
            WATCH->>WATCH: onTicketResolved callback
            WATCH->>SMTP: notify reporter (email_send)
            SMTP-->>WATCH: 250 OK
            WATCH->>WS: publishTrace STEP_5_REPORTER_NOTIFIED
            UI-->>Reporter: Live Feed muestra "Tu bug fue resuelto"
        end
    end
```

---

## 2. Diagrama secundario — solo el happy path (versión condensada para el demo video)

```mermaid
sequenceDiagram
    autonumber
    actor Reporter
    participant UI as Angular UI
    participant API as BugReportController
    participant Vision as Vision Adapter<br/>(qwen3.5 multimodal)
    participant Pipeline as 4-Agent Pipeline<br/>(Java Virtual Threads)
    participant Store as TicketStore
    participant Mail as Gmail SMTP
    participant Watcher as Status Watcher

    Reporter->>UI: Submit bug + screenshot
    UI->>API: POST /api/triage/report
    API-->>UI: 202 + correlationId
    UI->>API: WebSocket /ws/events

    API->>Vision: pre-process screenshot
    Vision-->>API: technical summary

    API->>Pipeline: triage-coordinator → (forensic ║ mitigation) → triage-broker
    Pipeline->>Mail: email_send to on-call
    Mail-->>Pipeline: 250 OK
    Pipeline->>Store: create ticket FH-N
    Pipeline-->>API: pipeline complete

    API-->>UI: WS DONE event
    UI-->>Reporter: muestra ticket FH-N + Live Feed completo

    Note over Watcher: 30s polling loop

    Watcher->>Store: any RESOLVED?
    Store-->>Watcher: FH-N closed
    Watcher->>Mail: email reporter "your bug is fixed"
    Mail-->>Watcher: 250 OK
    Watcher->>API: WS STEP_5
    API-->>UI: Live Feed muestra resolución
    UI-->>Reporter: notificación final
```

---

## 3. Notas sobre el diagrama

### 3.1 Por qué hay 2 versiones

- El **diagrama 1** es la verdad operativa completa — todos los actores,
  todos los participantes, los pasos opcionales, los ramales de error.
  Sirve para auditoría, debugging, y para el `AGENTS_USE.md` evidencia
  de observabilidad §6.
- El **diagrama 2** es la versión "happy path" condensada para el video
  demo de 3 minutos. El jurado tiene 180 segundos — necesita ver el
  flujo claro, no los 50 mensajes del completo.

### 3.2 Convenciones

- `Note right of X` — comentarios técnicos sobre el actor adyacente
- `alt … else … end` — branching condicional (Vision opcional, Sentinel
  audit success/fail)
- `par … and … end` — ejecución paralela real (Virtual Threads)
- `loop … end` — polling periódico del watcher
- Pasos numerados con `autonumber` para citar líneas del diagrama
  desde otros docs

### 3.3 Lo que NO está en el diagrama (deliberadamente)

- **HiveMind del core (7 swarm agents)** — está cargado al boot pero
  NO se invoca desde el flujo de fara-hack. Lo reemplazamos por
  `DirectAgentExecutor` directo.
- **NATS bus interno** — corre como servicio compose pero el pipeline
  fara-hack no lo usa para orquestación, solo para journaling de los
  agentes del HiveMind (que están dormidos). El "Sovereign Event Bus"
  del pitch es aspiracional para V1.1.
- **ArcadeDB** — existe como `ArcadeDbService` en el core pero no se
  instancia. Persistencia es in-memory.
- **MCP sidecars (server-github, server-slack)** — diseñados en
  `DECISIONES-APROBADAS-2026-04-07.md` pero NO levantados en el
  compose actual. La integración real es Gmail SMTP únicamente.

### 3.4 Tiempos típicos por step (medidos 2026-04-08)

| Step | Latencia (sec) | Bloqueante / async |
|---|---|---|
| 1 — Submit + 202 response | < 0.1 s | sync |
| 0 — Vision adapter (con imagen) | 5-15 s | sync (blocking processReportAsync) |
| 2 — Triage Coordinator | 20-40 s | sync |
| 2.5 + 6 — Forensic ║ Mitigation | max(20-60, 30-80) = 30-80 s | parallel Virtual Threads |
| Sentinel audit (cuando aplica) | < 50 ms | sync |
| 3+4 — Triage Broker + email | 15-30 s | sync |
| **Total submit → email entregado** | **~2-3 min** | (LLM-bound) |
| 5 — Watcher detecta RESOLVED | hasta 30 s | async polling |

---

## 4. Cómo capturar evidencia del diagrama corriendo

```bash
# 1. Tail los logs estructurados durante una corrida
docker compose logs -f fara-hack | grep -E "STEP_|DIRECT-AGENT|EMAIL_SEND|SENTINEL"

# 2. Capturar el Live Feed via wscat (si lo tenés instalado)
wscat -c "ws://localhost:8080/ws/events?correlationId=<el cid del POST>"

# 3. Snapshot final del store
curl -s http://localhost:8080/api/triage/tickets | python3 -m json.tool

# 4. Verificar el bind mount de mock-eshop desde dentro del container
docker compose exec fara-hack ls -la /repo/src/Services/Catalog.API/Services/

# 5. Forzar Step 5 marcando un ticket como resolved
curl -X POST http://localhost:8080/api/triage/tickets/FH-1/resolve
# Esperar hasta 30s y revisar bandeja del reporter
```

---

## 5. Documentos relacionados

- [`FLUJO-FARA-HACK.md`](FLUJO-FARA-HACK.md) — descripción narrativa del flujo
- [`MANUAL-USUARIO.md`](MANUAL-USUARIO.md) — pasos de usuario para correr y validar
- [`ARCHITECTURE-AGENTX-2026.md`](ARCHITECTURE-AGENTX-2026.md) — 9-section AgentX submission doc
- [`PITCH.md`](PITCH.md) — script del demo video con timing y overlays

`#AgentXHackathon`
