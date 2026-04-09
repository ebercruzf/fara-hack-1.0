# PITCH — Fara-Hack 1.0 (3-minute demo script)

**Author:** Eber Cruz | **Version:** 1.0.0

> Submission for **AgentX Hackathon 2026** · `#AgentXHackathon`
> Target length: **180 seconds** · Language: **English** · Tag in YouTube
> title and description.

---

## The hook (0:00 – 0:20)

> *"Most agentic AI submissions are LLM wrappers in Python with no answer
> to the question: 'what happens when the external system fails?'.
> Fara-Hack 1.0 answers that question with chaos tests you can run
> yourself. We built a Sovereign Agentic Runtime in Java 25 with Virtual
> Threads, NATS JetStream, and four specialized agents that automate
> bug-report triage end to end — from a multimodal report to a resolved
> ticket — without a human in the loop, and **with a kill-switch on every
> dangerous action**."*

---

## The problem (0:20 – 0:40)

> *"Engineering teams lose 15 to 60 minutes per bug report between the
> reporter clicking submit and the on-call engineer getting paged. That
> human latency creates duplicate tickets, missing context, and silent
> regressions. We automate that gap with four agents that talk to each
> other through an event bus, not through method calls."*

---

## The four agents (0:40 – 1:30)

Show the architecture diagram from `docs/internal/ARCHITECTURE-AGENTX-2026.md` §3.3
on screen.

> *"Agent one — **Vision Forensic** — runs Qwen3.5 35B locally via Ollama.
> It accepts the bug report and an optional screenshot, and pulls the
> error banners and stack traces straight out of the image."*

> *"Agent two — **Triage Architect** — is the reasoning brain. It uses
> our `IntentResolver` to decompose the report into a validated execution
> plan, and our `SentinelAuditor` blocks the plan if it contains anything
> dangerous. Severity, root cause, affected modules — all structured JSON,
> no free-form prose."*

> *"Agent three — **Code Surgeon** — is the differentiator. It reads the
> affected source file from a mounted repo and proposes a unified diff
> patch, capped at 50 lines. Then the `SentinelAuditor` runs **six
> audits** on the patch — flow, boundaries, errors, concurrency,
> resources, idempotency. If any audit fails, the patch is discarded and
> the system gracefully falls back to summary-only mode. **The user never
> sees a 500.**"*

> *"Agent four — **Choreography Coordinator** — dispatches everything to
> MCP sidecars over the NATS bus, tracks the ticket lifecycle, and
> notifies the original reporter when the ticket transitions to RESOLVED.
> Idempotent by `Nats-Msg-Id`, with circuit breaker and DLQ on every
> sidecar."*

---

## The live demo (1:30 – 2:30)

Cut to the browser. The Angular UI is open at `http://localhost:8080/`.

> *"Watch this. I submit a bug with a screenshot. The WebSocket Live Feed
> lights up — you're watching every span the agents emit, in real time."*

Paste a real bug report (e.g., NullPointerException in checkout). Attach
the prepared screenshot.

> *"Vision Forensic extracts the stack frame from the image. Triage
> Architect classifies it as P1, identifies the affected module, and
> drafts the plan. Sentinel audits the plan — green. Code Surgeon
> proposes a 12-line patch. Sentinel audits the patch on six dimensions
> — green again. Choreography Coordinator pushes the ticket to the MCP
> ticketing sidecar, fans out the email and Slack notifications in
> parallel via Virtual Threads, and the ticket appears in the dashboard."*

Switch to the dashboard tab. Click **"Mark Resolved"**.

> *"And here's what nobody else demos: I close the ticket. Within 30
> seconds, the `TriageStatusWatcher` — a `SovereignActor` polling on its
> own Virtual Thread — detects the transition, fires the resolution
> event, and the original reporter gets a notification with the
> commit-level forensic snapshot. Full loop, autonomous."*

---

## The chaos demo (2:30 – 2:50)

Open a terminal next to the browser.

```bash
./scripts/demo.sh --scenario=saturation
```

> *"Now watch what happens when a sidecar dies mid-mission. The
> Coordinator detects the saturation at 80% queue depth, marks the
> sidecar `unavailable`, rejects new requests with `SIDECAR_SATURATED`
> **before** they hit the black hole, falls back to the filesystem
> bridge, and the mission still completes. Liveness is not readiness —
> we treat them as different signals. **This is what production-ready
> means.**"*

---

## The close (2:50 – 3:00)

> *"Java 25, native image, 30 megabytes, distroless. NATS-native
> distributed tracing with OpenTelemetry Semantic Conventions. Four
> agents, six guardrails, ten chaos tests. Zero Python, zero hand-waving.
> Fara-Hack 1.0 — production-ready agentic AI you can `docker compose
> up` in 90 seconds. Hashtag AgentX Hackathon."*

---

## On-screen overlay cues

| Time | Overlay |
|---|---|
| 0:00 | `Fara-Hack 1.0 · Sovereign Agentic Runtime · #AgentXHackathon` |
| 0:40 | Architecture diagram (4 agents + NATS + sidecars) |
| 1:30 | Browser screen recording starts |
| 2:30 | Terminal screen split-view |
| 2:55 | `github.com/<user>/fara-hack-1.0 · MIT License` |

## What NOT to say

- ❌ "We use OpenTelemetry SDK" → we use `AgentSpan` with OTel Semantic
  Conventions; the OTel Collector is V1.1 roadmap.
- ❌ "We integrate with Cloud Trace today" → roadmap.
- ❌ "We have 10,000 concurrent users" → we tested 10,000 idle sessions
  per node; concurrent triages are LLM-bound.
- ❌ Any feature that is not visible in the 3-minute video.

## Demo bug — exact reproduction recipe (memorizar antes de grabar)

El bug que vamos a usar en el video está **inyectado quirúrgicamente**
en `mock-eshop/src/Services/Catalog.API/Services/DiscountService.cs`
**en la línea 142**. Esa es la única línea con el bug; las otras 154
son contexto realista para que el LLM razone como si fuera un repo
.NET de producción.

### El bug

```csharp
// línea 141:
//     // ⚠ BUG: NullReferenceException here when `code` is null
// línea 142:
        return originalPrice * (1 - (code.Percentage / 100));
```

`GetDiscountByCodeFromDb(codeName)` devuelve `null` cuando el código
no existe en la tabla `Promotions`. El método sigue adelante y accede
a `code.Percentage` → **NullReferenceException** → 500 al cliente.

### El bug report a copiar en la UI durante el video

| Campo | Valor |
|---|---|
| Title | `NullReferenceException in checkout when applying discount code` |
| Reporter | `alice.qa@eshop.test` |
| Description | `Customer reports applying discount code at checkout throws 500. Repro: add item > apply SAVE10 > continue to payment. Started after deploy v2.14.3. Affecting ~30% of checkouts.` |
| Stack trace | `System.NullReferenceException: Object reference not set to an instance of an object.`<br>`  at Microsoft.eShop.Services.Catalog.API.Services.DiscountService.ApplyDiscount(String codeName, Decimal originalPrice) in /repo/src/Services/Catalog.API/Services/DiscountService.cs:line 142`<br>`  at Microsoft.eShop.Services.Catalog.API.Controllers.CatalogController.Checkout(String discountCode, Decimal price)` |

### Lo que el agente DEBE hacer en vivo (verificar antes de grabar)

1. **Triage Coordinator** clasifica `severity: P1` (impacto a 30% de
   checkouts, 5xx en flujo crítico).
2. **Forensic Analyst** corre en paralelo, devuelve
   `duplicateOf: null`, `suggestedOwner: null` (no hay registry real).
3. **Mitigation Engineer** corre `fs_read("/repo/src/Services/Catalog.API/Services/DiscountService.cs")`,
   identifica la línea 142, y propone un unified diff que añade un
   guard `if (code == null) return originalPrice;` antes de la línea
   buggy. Ese patch va al `SentinelAuditor`.
4. **Triage Broker** compose el email con asunto
   `[P1] NullReferenceException in checkout when applying discount code`
   y lo dispara a `the on-call engineer (ONCALL_EMAIL)` vía Gmail SMTP.

### Línea de pitch quirúrgica para este demo

> *"Look at the live feed: Mitigation Engineer just opened
> `/repo/src/Services/Catalog.API/Services/DiscountService.cs`,
> navigated to line 142, and proposed a 6-line patch that adds the
> missing null guard. The Sentinel Auditor approved it on six
> dimensions — flow, boundaries, errors, concurrency, resources,
> idempotency. The patch is now attached to ticket FH-2 and the
> on-call engineer received the email three seconds ago."*

### Talking point — Theoretical Foundation (G-Master Evolution FSM-Graph)

Cuando hablés de la arquitectura del pipeline, **diferenciate del resto
de los equipos** mencionando que tu modelo viene de una publicación
técnica propia y tiene un nombre formal. Línea exacta a usar en el
video (English):

> *"Our agent orchestration is based on the **G-Master Evolution
> architecture** — a Deterministic Two-Layer Control model with a
> Directed State Graph that we published as part of the BetSmart
> framework. The four agents map one-to-one to FSM states:
> POST_COMPILE handles intake, POST_READ runs forensic analysis,
> POST_PATCH proposes mitigations, and the Repair Cycle edge feeds
> rejected patches back to the audit loop. This is what makes our
> system deterministic and auditable in a way that scripted LLM
> wrappers can never be."*

**Mostrar en pantalla mientras lo decís:** la imagen
`docs/img/diagrams/fsm-graph.png` con sus 5 estados
y los 2 loops de Repair / Multi-Patch.

**Si el jurado pregunta "is that public theory or your own?":**

> *"It's our own published framework — see fararoni.dev/publicacion/betsmartmar-gmaster.
> We didn't pull this from LangGraph or LangChain — we wrote the
> theoretical foundation first and then built the agents to match it."*

Eso es **diferenciador único**: ningún otro equipo va a tener una
publicación técnica propia detrás de su orquestación. Cubre los
criterios de **Scalability** (modelo formal escalable) y
**Context Engineering** (estados explícitos vs flujo implícito).

### Talking point — `TicketStore.java` (in-memory + email-as-bridge)

Cuando hablés de cómo creamos los tickets sin Jira/Linear real, decí
esto (es 100% verificable contra el código):

> **1. La Realidad: `TicketStore.java`**
>
> En lugar de llamar a los endpoints de Atlassian (que requerirían
> OAuth2, manejo de tokens y latencia de red que podría arruinar el
> demo en vivo), el sistema hace lo siguiente:
>
> **Generación de IDs:** El controller asigna IDs con formato
> profesional `FH-N` (ej. `FH-102`) mediante un `AtomicLong`
> thread-safe, replicando el patrón estándar de project keys de
> Jira como `ESHOP-123` o `INTL-45`. Implementación en
> `TicketStore.create()` línea 40.
>
> **Persistencia:** Los tickets viven en un `ConcurrentHashMap<String, Ticket>`
> dentro de `TicketStore.java`. El header del archivo declara
> explícitamente que la swap a ArcadeDB persistente está implementada
> en `fararoni-core/.../persistence/arcadedb` y queda como roadmap
> V1.1 — honestidad documental que evita over-selling.
>
> **Estructura del ticket:** El campo `body` del record `Ticket`
> sigue una estructura Markdown determinista compuesta por el
> controller en Java: `## Summary`, `## Description`, `## Stack Trace`
> (en code-block), y opcionalmente `## Sentinel-Verified Patch`
> con el unified diff cuando el `SentinelAuditor` aprobó el parche.
> Es exactamente la estructura que un on-call engineer espera ver
> en un ticket de Jira.
>
> **Visibilidad:** El ticket "cobra vida" cuando el `triage-broker`
> agent invoca el tool nativo `email_send` con el resumen
> estructurado, llegando como correo real vía Gmail SMTP a la
> dirección configurada en `ONCALL_EMAIL`. El email completa el
> bridge entre el estado in-memory del proceso Java y el mundo
> del on-call engineer.

**Por qué esta narrativa es defendible al 100%:**

| Claim | Verificación en código |
|---|---|
| Format `FH-N` con AtomicLong | `TicketStore.java:40` literal |
| Thread-safe | `ConcurrentHashMap` línea 27 + `AtomicLong` línea 28 |
| Body estructurado Markdown | `BugReportController:610-614` (`## Summary` + `## Description` + `## Stack Trace` + `mitigationBlock`) |
| `mitigationBlock` con diff | Solo presente cuando `sentinelVerified = true`, ver `BugReportController:540-543` |
| Email real Gmail SMTP | Logs `[EMAIL_SEND] Correo enviado a: ...` |
| Roadmap a ArcadeDB | Comentario explícito en `TicketStore.java:14-21` |

### Talking point — Concurrent Agentic Orchestration (no decir "DAG engine")

Cuando hablés de cómo funciona el pipeline en el video, **NO digas**
*"usamos el motor de DAGs de Fararoni Core"*. Eso suena a marketing y
además no es 100% preciso (no usamos `SovereignMissionEngineV2`, ver
`ROADMAP.md` §V1.1).

**Decí esto** que suena mucho más Senior SRE y es verbatim defendible
contra el código:

> *"Implementamos una **Orquestación Agéntica Concurrente** basada en
> un modelo de **DAG Imperativo**. Aprovechamos la potencia de **Java
> 25** y **Virtual Threads** para ejecutar las fases de análisis
> forense y propuesta de mitigación **en paralelo**, garantizando un
> triaje de alta velocidad sin la sobrecarga de un motor de
> orquestación externo."*

**Versión inglés (para el video):**

> *"We implemented Concurrent Agentic Orchestration based on an
> Imperative DAG model. We leverage Java 25 Virtual Threads to run
> the forensic analysis and mitigation proposal phases in parallel,
> achieving high-speed triage without the overhead of an external
> orchestration engine."*

**Por qué cada palabra de esa frase es 100% honesta y verificable:**

| Palabra | Verificación en código |
|---|---|
| "Concurrent Agentic" | 4 agentes (`triage-coordinator`, `forensic-analyst`, `mitigation-engineer`, `triage-broker`) cada uno via `DirectAgentExecutor` con tool calling |
| "Imperative DAG" | El DAG vive en `BugReportController.processReportAsync` (Java imperativo), no en YAML mission template ni en `SovereignMissionEngineV2` |
| "Java 25 Virtual Threads" | `Thread.ofVirtual().name("forensic-...").start(...)` y `Thread.ofVirtual().name("mitigation-...").start(...)` — verificable en logs por mismo timestamp de finalización |
| "in parallel" | Los traces `STEP_2_5_FORENSIC` y `STEP_6_MITIGATION` salen con el **mismo timestamp ISO** en el WS Live Feed (prueba visual del paralelismo) |
| "without external orchestration overhead" | Cero llamadas a `executeMission`, cero `MissionTemplate` cargado, cero `IntentResolver`. Solo `runDirectAgent(...)` desde Java directo |

**Por qué NO decir "motor de DAGs externo":**
- Sería sugerir que usamos `SovereignMissionEngineV2` (no lo usamos)
- O peor, sugerir Airflow/Temporal/Step Functions (definitivamente no)
- **"Imperative DAG"** = el grafo está codificado en Java imperativo,
  cada arista es un `Thread.ofVirtual().start()` o un `runDirectAgent`
  call. Es **deliberadamente** así para evitar la sobrecarga de un
  motor externo.

**Bonus técnico** (si el jurado pregunta "por qué no un motor de
orquestación dedicado"):

> *"External orchestration engines (Airflow, Temporal, Step Functions)
> are valuable when you need durable execution across hours/days or
> when the workflow author is non-technical. Our triage pipeline runs
> in 90-180 seconds total, the workflow shape is fixed, and the
> author is the same engineer who ships the agents. An imperative
> Java orchestration with Virtual Threads gives us sub-millisecond
> step transitions, no serialization overhead, no extra container,
> and the kill-switch + fallback paths live next to the happy path
> in the same source file. Production-ready resilience without
> production-grade infrastructure."*

### Talking point — Severity-aware notification gating

Cuando demuestres el sistema, mencioná esta feature explícitamente
porque diferencia a fara-hack de un "wrapper de LLM que manda emails
por todo":

> *"Notice how the Triage Broker doesn't blindly email every report.
> It honors a severity threshold: P0 and P1 get the on-call email,
> P2 and P3 stay in the ticket queue without paging. This is the
> standard SRE practice — selective paging prevents alert fatigue.
> Implemented declaratively in the broker agent's YAML, no code
> required."*

**Demo opcional del gating:** submitir UN bug con lenguaje crítico
("production outage", "data loss") → P1 → email llega. Submitir OTRO
con lenguaje suave ("typo in label", "minor styling issue") → P3 →
ticket creado pero sin email. **Same code path, different outcome
based on inferred severity.**

Es una feature de 4 líneas en el YAML del broker que el jurado
puede leer en `workspace/.fararoni/config/agentes/triage-broker-agent.yaml`
regla 3 del `systemPrompt`.

---

## Pre-record checklist

- [ ] All 3 BLOCKERS in `docs/internal/PENDIENTES-SMOKE-TEST-2026-04-08.md` are green
- [ ] `docker compose down -v && docker compose up --build` runs clean
- [ ] Ollama is running on the host with `qwen3.5:35b-a3b` pulled
- [ ] `mock-eshop/src/Services/Catalog.API/CatalogController.cs` exists
- [ ] Bug report fixture and screenshot fixture ready in clipboard
- [ ] OBS / screen recorder configured at 1920×1080, 30 fps
- [ ] Browser zoom 110% so the WS Live Feed text is readable
- [ ] Audio: lavalier mic, levels checked, room noise <-50 dB
