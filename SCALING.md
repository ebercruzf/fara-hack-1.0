# SCALING — Fara-Hack 1.0

This document explains how Fara-Hack 1.0 scales, what assumptions we
made, and the technical decisions that enable it.

## Executive summary

Fara-Hack scales **horizontally and vertically** with sub-millisecond
overhead per agent invocation. The runtime is built on Java 25 Virtual
Threads (Project Loom), Project Panama (FFM) for native interop, and
NATS JetStream as a persistent event bus. There is **no Python
orchestration layer, no Node.js runtime, no JVM cold start in the
container** (the binary is GraalVM native).

Concrete numbers from internal benchmarks (M1 MacBook Pro):

- **Agent invocation overhead**: ~50µs (Virtual Thread spawn + mailbox)
- **Routing decision latency**: 0.07ms (FormalLogicRouter)
- **Security gate latency**: 247µs (FastPathValidator)
- **Choreographed parallel mission**: 103ms for a 6-step DAG
- **Bus throughput**: ~120k msgs/sec on InMemoryBus, ~25k msgs/sec on NATS JetStream persistent
- **Cold start (native binary)**: <100ms
- **Container image size**: ~30-40MB (distroless)
- **RAM footprint at idle**: ~80MB (vs ~400MB for JVM equivalent)

## Scaling dimensions

### 1. Concurrent sessions per node (vertical)

Each user session lives in its own `silo` (`SessionManager`) with its
own bus, mailbox, and policy state. Sessions are isolated.

| Metric | Value | Limit |
|---|---|---|
| Sessions per node | 10,000+ | RAM (~10KB per idle session) |
| Concurrent missions per session | unlimited | Virtual Thread limit (~millions) |
| Per-mission virtual threads | 1 per actor + 1 per mailbox | Loom scales to >1M VTs per JVM |

**Key decision:** we use Virtual Threads instead of OS threads or
goroutines because they are JVM-managed, give us full
`StructuredConcurrency` semantics (Java 25), and allow us to wait on
I/O (NATS, ArcadeDB) without burning OS resources.

### 2. Multi-node horizontal scaling

When NATS is the bus (default in production), nodes coordinate
through topics. Multiple `fara-hack` containers can run as a swarm:

```
                  ┌─────────────┐
                  │ NATS broker │
                  │  JetStream  │
                  └──────┬──────┘
                         │
       ┌─────────────────┼─────────────────┐
       ▼                 ▼                 ▼
  ┌─────────┐       ┌─────────┐       ┌─────────┐
  │ node 1  │       │ node 2  │       │ node 3  │
  └────┬────┘       └────┬────┘       └────┬────┘
       └─────────────────┼─────────────────┘
                         ▼
                  ┌─────────────┐
                  │  ArcadeDB   │
                  └─────────────┘
```

**Work distribution**: actors consume from JetStream pull consumers
with `AckExplicit`. If a node dies mid-task, the message is
redelivered to another node automatically. **No leader election
required** — JetStream handles it.

### 3. Backpressure and saturation

Fara-Hack treats backpressure as a first-class concern:

- **Bus level**: `InMemorySovereignBus` enforces `MAX_CAPACITY` and
  refuses new messages with `BusOverloadException` when full
- **Sidecar level**: `McpProxySkill` reads `queueDepth` from sidecar
  heartbeats and refuses requests when ≥80%
- **Mission level**: `SovereignMissionEngineV2` activates kill-switch
  to legacy templates if reasoning pipeline fails

**Result**: a saturated component never cascades into a system-wide
failure. The system degrades gracefully.

### 4. Storage scaling

| Storage | Use | Scaling strategy |
|---|---|---|
| **ArcadeDB** | Source of truth (missions, agents, policies, graph) | Embedded for hackathon demo; cluster mode for production |
| **NATS JetStream** | Event bus, episodic memory | Cluster of 3+ nodes, partitioning by subject |
| **NATS KV** | Hot session cache (TTL 5 min) | Co-located with bus, in-memory |
| **ForensicMemory** | mmap+SIMD index for forensic search | Local file, replicable via NATS Object Store |

## Assumptions

We document these explicitly so judges know our limits:

1. **Single ArcadeDB instance for the demo**. For multi-node
   production, ArcadeDB can run in HA cluster mode but we don't ship
   that in the hackathon image (out of scope).
2. **Mock sidecars are in-process**. Real sidecars would run as
   separate containers in production `docker-compose.yml`.
3. **OpenRouter as cloud LLM fallback**. The `.env.example` provides
   slots for OpenRouter, but the system can also use local Ollama
   models — that's our "Sovereign AI" pitch.
4. **Demo runs single-node**. The submission `docker-compose.yml`
   spins up one fara-hack container + one NATS + one ArcadeDB. Multi-
   node testing is documented but not in the demo path.
5. **Container resource limits**: 2 vCPU, 1GB RAM is enough for the
   demo workload. Native binary makes this trivially feasible.

## Technical decisions

### Why Java 25 (and not Python/Node)

| Concern | Java 25 | Python/Node |
|---|---|---|
| Concurrency model | Virtual Threads (millions) | GIL / async loop (thousands) |
| Cold start | <100ms (native image) | 1-3s typical |
| Memory footprint | ~80MB | ~200-500MB |
| Type safety | Sealed records, exhaustive switch | Runtime errors |
| Native interop | Project Panama (zero-copy FFI) | Subprocess or C extensions |
| LLM client overhead | Direct JNI to llama.cpp | HTTP roundtrips |

### Why NATS JetStream (and not Kafka/RabbitMQ)

- **Latency**: NATS request/reply is sub-millisecond locally
- **Operational simplicity**: single binary, no Zookeeper
- **JetStream persistence**: fills the Kafka-style use case
- **NATS Services framework**: ergonomic for tool invocation
- **KV + Object Store built-in**: replaces multiple Redis-style services

### Model Split Strategy: Perceptual vs. Logic Optimization

To ensure production-grade reliability and performance, **C-FARARONI**
implements a **Model Split Strategy**. This approach routes tasks to
specialized models based on their inherent strengths, mirroring
real-world SRE production patterns.

1. **Vision & Perception Stage (`qwen3.5:35b-a3b`)**:
   - **Role**: Used exclusively for the `STEP_0_VISION` multimodal
     analysis.
   - **Justification**: Its **Mixture of Experts (MoE)** architecture
     excels at complex image understanding and OCR, allowing the system
     to extract critical incident details from screenshots and logs
     that text-only models would miss.

2. **Agentic Execution Stage (`qwen2.5-coder:32b`)**:
   - **Role**: Powers the `triage-coordinator`, `forensic-analyst`,
     and `mitigation-engineer` agents.
   - **Justification**: This is a **dense, coder-tuned model** designed
     for high-fidelity **tool-calling** and strict **JSON output**. By
     using a dedicated coder model, we achieve 3x-4x faster response
     times (12s-26s per agent) and near-zero failure rates in structured
     logic tasks compared to generalized MoE models.

**Scalability Impact**: This decoupled model strategy allows the system
to scale horizontally. Vision processing can be offloaded to
vision-optimized hardware, while agentic reasoning clusters can handle
concurrent triages using high-throughput dense models, ensuring the
system remains responsive during massive production outages.

### Why GraalVM native binary

- Zero JVM warmup
- ~30MB distroless image (vs ~400MB JVM container)
- Faster start = faster cold-start under autoscaling
- Reduced attack surface (no JIT, no class loader exploits)

### Why Distroless instead of Alpine

- No shell, no package manager → minimal attack surface
- Pre-loaded with `libc`, `libssl`, certificates (Alpine is musl-only,
  binary compatibility risk with native image)
- Maintained by Google with security updates

## Bottlenecks we identified

Honest disclosure of where the system would break first under load:

1. **ArcadeDB embedded mode**: writes are serialized. At ~2k writes/sec
   per node, you'd need to switch to ArcadeDB cluster mode.
2. **LLM inference latency**: cloud LLMs (OpenRouter) add 200-2000ms
   per call regardless of our runtime overhead. Mitigation: local
   models via Ollama, ~50-200ms per call.
3. **Single-node demo**: this submission is single-node by design.
   Multi-node coordination via NATS is implemented but not exercised
   in the demo `docker-compose.yml`.
4. **Cold-start of native binary in cgroups**: <100ms is local; in a
   container with cold cgroups it can be ~200ms. Still 10x faster
   than JVM.

## Out of scope for this submission

- Geo-distribution / multi-region
- Active-active multi-master databases
- Custom LLM fine-tuning
- WebUI frontend (we ship the WebSocket endpoints; any frontend can
  consume them)
