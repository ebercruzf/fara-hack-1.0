# Architecture Diagram — Fara-Hack 1.0

**Author:** Eber Cruz | **Version:** 1.0.0

> High-level architecture showing the full system: Docker containers,
> NATS event bus, Model Split Strategy, and the 4-agent pipeline.
> Renderizable in GitHub, Mermaid Live Editor, or any Mermaid-compatible viewer.

---

## 1. System Architecture (Container View)

```mermaid
graph TB
    subgraph HOST["Host Machine (macOS / Linux)"]
        OLLAMA["Ollama LLM Server<br/>:11434"]
        QWEN35["qwen3.5:35b-a3b<br/>(MoE · Multimodal · Vision)"]
        QWEN25["qwen2.5-coder:32b<br/>(Dense · Coder-tuned · Tool-calling)"]
        OLLAMA --> QWEN35
        OLLAMA --> QWEN25
    end

    subgraph DOCKER["Docker Compose Network (isolated)"]
        subgraph WEB["web container<br/>Nginx + Angular 20<br/>:80 → host :8080"]
            SPA["Angular SPA"]
            PROXY["Nginx Reverse Proxy<br/>/api → fara-hack:8080<br/>/ws → fara-hack:8080"]
        end

        subgraph APP["fara-hack container<br/>Java 25 + Virtual Threads<br/>:8080 (internal)"]
            CTRL["BugReportController<br/>(REST + WebSocket)"]
            VISION["LlmTriageClient<br/>(Vision Adapter)"]
            DAE["DirectAgentExecutor"]
            GUARD["SovereignBusGuard<br/>(Circuit Breaker)"]
            TOOLS["ToolRegistry<br/>36 tools"]
            TICKET["TicketStore<br/>(in-memory)"]
            SMTP["EmailTransport<br/>(Gmail SMTP)"]
            AGENTS["4 Agent YAMLs<br/>/app/workspace/.fararoni/<br/>config/agentes/"]
            ESHOP["/repo (read-only)<br/>mock-eshop — C# eCommerce<br/>DiscountService.cs:142"]
        end

        subgraph NATS_SVC["nats container<br/>NATS JetStream<br/>:4222 (internal)"]
            JS["JetStream<br/>Persistent Streams"]
            CORE_NATS["Core NATS<br/>Pub/Sub + Queue Groups"]
        end
    end

    BROWSER(("Browser<br/>:8080")) --> SPA
    SPA <-->|"HTTP + WebSocket"| PROXY
    PROXY <-->|"internal network"| CTRL
    CTRL --> VISION
    CTRL --> DAE
    DAE <-->|"publish / subscribe"| GUARD
    GUARD <-->|"Primary (P:100)"| CORE_NATS
    GUARD -.->|"Standby (P:50)<br/>ChronicleQueueBus"| APP
    VISION -->|"/v1/chat/completions<br/>multimodal image+text"| OLLAMA
    DAE -->|"/v1/chat/completions<br/>tool-calling JSON"| OLLAMA
    CTRL --> TICKET
    CTRL --> SMTP
    DAE --> AGENTS
    DAE -->|"fs_read /repo"| ESHOP

    style QWEN35 fill:#e8f5e9,stroke:#2e7d32,color:#000
    style QWEN25 fill:#e3f2fd,stroke:#1565c0,color:#000
    style NATS_SVC fill:#fff3e0,stroke:#e65100,color:#000
    style GUARD fill:#fce4ec,stroke:#c62828,color:#000
```

---

## 2. Agent Pipeline Flow (with NATS Bus)

```mermaid
flowchart LR
    subgraph INGRESS["Ingress"]
        A["POST /api/triage/report<br/>(multipart: text + image)"]
    end

    subgraph VISION_STAGE["Step 0 — Vision<br/>qwen3.5:35b-a3b (MoE)"]
        B["LlmTriageClient<br/>multimodal OCR + analysis"]
    end

    subgraph COORD_STAGE["Step 2 — Coordinator<br/>qwen2.5-coder:32b (Dense)"]
        C["triage-coordinator<br/>extracts signals, classifies severity"]
    end

    subgraph PARALLEL["Steps 2.5 + 6 — Parallel Branch<br/>Java 25 Virtual Threads"]
        D1["forensic-analyst<br/>duplicate detection<br/>owner resolution"]
        D2["mitigation-engineer<br/>proposes patch<br/>sentinel audit"]
    end

    subgraph BROKER_STAGE["Steps 3 + 4 — Broker<br/>qwen2.5-coder:32b (Dense)"]
        E["triage-broker<br/>ticket_create + email_send"]
    end

    subgraph OUTPUT["Outputs"]
        F1["Ticket FH-N<br/>(filesystem / GitHub)"]
        F2["Email Alert<br/>(Gmail SMTP)"]
        F3["WebSocket Live Feed<br/>(Reasoning Trace)"]
    end

    A --> B
    B -->|"severity + summary<br/>via NATS pub"| C
    C -->|"spawn parallel"| D1 & D2
    D1 & D2 -->|"join results<br/>via NATS sub"| E
    E --> F1 & F2
    B & C & D1 & D2 & E -.->|"publishTrace<br/>via NATS → WebSocket"| F3

    style VISION_STAGE fill:#e8f5e9,stroke:#2e7d32,color:#000
    style COORD_STAGE fill:#e3f2fd,stroke:#1565c0,color:#000
    style PARALLEL fill:#f3e5f5,stroke:#6a1b9a,color:#000
    style BROKER_STAGE fill:#e3f2fd,stroke:#1565c0,color:#000
```

---

## 3. NATS Event Bus Detail

```mermaid
graph LR
    subgraph PUBLISHERS["Publishers"]
        P1["BugReportController"]
        P2["DirectAgentExecutor"]
        P3["triage-coordinator"]
        P4["forensic-analyst"]
        P5["mitigation-engineer"]
        P6["triage-broker"]
    end

    subgraph BUS["SovereignBusGuard (Military-Grade)"]
        direction TB
        PRIMARY["NatsSovereignBus<br/>Priority: 100<br/>NATS Core + JetStream"]
        STANDBY["ChronicleQueueBus<br/>Priority: 50<br/>Disk-backed buffer"]
        REPLAY["ReplayEngine<br/>Health check 5s<br/>Auto-replay on recovery"]
        PRIMARY -.->|"failover"| STANDBY
        STANDBY -.->|"replay"| REPLAY
        REPLAY -.->|"recover"| PRIMARY
    end

    subgraph SUBSCRIBERS["Subscribers"]
        S1["WebSocket Live Feed<br/>(Reasoning Trace)"]
        S2["MessagingChannelManager<br/>(Slack webhook)"]
        S3["Agent result topics<br/>(18 agents)"]
    end

    P1 & P2 & P3 & P4 & P5 & P6 --> PRIMARY
    PRIMARY --> S1 & S2 & S3

    style PRIMARY fill:#fff3e0,stroke:#e65100,color:#000
    style STANDBY fill:#fafafa,stroke:#757575,color:#000
    style REPLAY fill:#e8eaf6,stroke:#283593,color:#000
```

---

## 4. Model Split Strategy

```mermaid
graph LR
    subgraph INPUT["Incoming Bug Report"]
        IMG["Screenshot / Log image"]
        TXT["Title + Description + Stack Trace"]
    end

    subgraph SPLIT["Model Router (Orchestrator)"]
        ROUTER{"Task type?"}
    end

    subgraph VISION_M["Vision Path"]
        VM["qwen3.5:35b-a3b<br/>MoE · 41 layers · 21.9 GiB<br/>Multimodal OCR + Analysis"]
    end

    subgraph AGENT_M["Agentic Path"]
        AM["qwen2.5-coder:32b<br/>Dense · 65 layers · 18.1 GiB<br/>Tool-calling + JSON fidelity"]
    end

    subgraph RESULTS["Outputs"]
        R1["LlmTriageResult<br/>severity, confidence,<br/>technicalSummary"]
        R2["Agent Actions<br/>fs_read, ticket_create,<br/>email_send, diff_generate"]
    end

    IMG & TXT --> ROUTER
    ROUTER -->|"has image → STEP_0"| VM
    ROUTER -->|"agent execution → STEP 2-6"| AM
    VM --> R1
    AM --> R2

    style VM fill:#e8f5e9,stroke:#2e7d32,color:#000
    style AM fill:#e3f2fd,stroke:#1565c0,color:#000
    style ROUTER fill:#fff9c4,stroke:#f9a825,color:#000
```

---

## 5. Agent Configuration (4 YAML Prompts)

All agents live in `workspace/.fararoni/config/agentes/` and are loaded at boot by `AgentTemplateManager`.

| Agent | YAML | LLM | System Prompt (summary) | Allowed Tools |
|---|---|---|---|---|
| **triage-coordinator** | `triage-coordinator-agent.yaml` | qwen2.5-coder:32b | Classifies severity (P0-P3), extracts file paths from stack trace, emits strict JSON. "La severidad NO se negocia." | `fs_read`, `code_search` |
| **forensic-analyst** | `forensic-analyst-agent.yaml` | qwen2.5-coder:32b | Detects duplicate incidents, resolves code ownership, queries forensic memory for similar past reports. | `arcadedb_query`, `forensic_memory_search` |
| **mitigation-engineer** | `mitigation-engineer-agent.yaml` | qwen2.5-coder:32b | Proposes a unified diff patch (max 50 LOC, single file), audited by SentinelDiffAdapter before attaching to ticket. | `fs_read`, `sentinel_audit`, `diff_generate` |
| **triage-broker** | `triage-broker-agent.yaml` | qwen2.5-coder:32b | Dispatches ticket creation and email notification to on-call engineer. Final step of the pipeline. | `ticket_create`, `email_send`, `arcadedb_write`, `mcp_dispatch` |

### Mock e-Commerce Codebase

The agents have read-only access to a mock e-commerce codebase mounted at `/repo` inside the container (bind mount `./mock-eshop:/repo:ro`):

```
mock-eshop/src/Services/Catalog.API/
├── Controllers/CatalogController.cs
├── Models/DiscountCode.cs
└── Services/DiscountService.cs          ← bug at line 142 (NullReferenceException)
```

This simulates a medium-complexity C# e-commerce application (Microsoft eShop pattern). The `triage-coordinator` agent uses `fs_read` to inspect the source code referenced in stack traces.

### Frontend — fara-hack-web (Angular 20)

| Component | Technology | Purpose |
|---|---|---|
| **SPA Framework** | Angular 20.3 (standalone components) | Bug report form + Reasoning Trace panel |
| **WebSocket Client** | Native browser WebSocket API | Real-time live feed from `/ws/events?correlationId=X` |
| **HTTP Client** | Angular HttpClient | REST calls to `/api/triage/report`, `/api/triage/tickets` |
| **Reverse Proxy** | Nginx 1.27 Alpine | Serves SPA, proxies `/api` and upgrades `/ws` to WebSocket |
| **Build** | `ng build --configuration production` inside Docker (Node 22 Alpine) | Zero host dependencies |

### Ticket System — TicketStore

In-memory `ConcurrentHashMap<String, Ticket>` with atomic sequence counter (`FH-1`, `FH-2`, ...). Exposed via:
- `POST /api/triage/report` → creates ticket after pipeline completes
- `GET /api/triage/tickets` → lists all tickets with severity badge
- `POST /api/triage/tickets/{id}/resolve` → triggers Step 5 (reporter notification via `TriageStatusWatcher`)

## Legend

| Color | Meaning |
|---|---|
| Green | Multimodal / Vision model (`qwen3.5:35b-a3b`) |
| Blue | Coder / Agentic model (`qwen2.5-coder:32b`) |
| Orange | NATS Event Bus |
| Pink | Circuit Breaker / Failover |
| Purple | Parallel execution (Virtual Threads) |
