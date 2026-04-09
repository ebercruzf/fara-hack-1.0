# QUICKGUIDE — Fara-Hack 1.0

The fastest path from zero to a running demo. **3 steps, ~5 minutes.**

## Prerequisites

You only need on your host:

- **JDK 25** (Eclipse Temurin recommended) — for building the backend jar
- **Maven 3.9+** — for the build
- **Docker + Docker Compose** — for running the stack

That's it. **No Node.js, no Angular CLI, no Python on your host.**
The Angular frontend builds inside its Docker container automatically.

> Why JDK + Maven on the host? Because the backend fat jar is built
> locally and copied into a distroless container — this drops the
> Docker build time from 3 minutes to 10 seconds and gives judges a
> jar they can audit *before* running anything in Docker.

## Steps

### 1. Clone

```bash
git clone https://github.com/<owner>/fara-hack.git
cd fara-hack
```

### 2. Build the backend fat jar (one-time, ~30 seconds)

```bash
mvn -B package -DskipTests
```

This produces `target/fara-hack-1.0.jar` (~182 MB shaded fat jar
containing the Sovereign Agentic Runtime + all dependencies).

### 2.b — `fararoni-core` + `fararoni-enterprise-transport` dependencies

Fara-Hack 1.0 depends on two public libraries from
[fararoni-ecosystem](https://github.com/ebercruzf/fararoni-ecosystem)
(Apache 2.0):

- **fararoni-core** — Sovereign Agentic Runtime, LLM dispatcher, tool registry
- **fararoni-enterprise-transport** — NATS JetStream event bus (SPI auto-detected, priority 100)

Both resolve via the `${fararoni.core.version}` property in `pom.xml`.
**You have TWO ways to make Maven find these artifacts** — pick
whichever is easier for your environment:

#### Option A — JitPack (default, zero local install)

The `pom.xml` already declares JitPack as a repository and pulls
`fararoni-core` + `fararoni-enterprise-transport` from the public
[ebercruzf/fararoni-ecosystem](https://github.com/ebercruzf/fararoni-ecosystem)
repo. **No manual changes are needed** — just run `mvn -B package -DskipTests`.

The dependency is pinned to commit `9f16edb` of the
`feature/v1.2.0-agentic-executor` branch for reproducibility:

```xml
<fararoni.core.version>9f16edb</fararoni.core.version>
```

> **Tip:** to verify JitPack has the artifact ready, open in your
> browser:
> [https://jitpack.io/com/github/ebercruzf/fararoni-ecosystem/fararoni-core/9f16edb/fararoni-core-9f16edb.pom](https://jitpack.io/com/github/ebercruzf/fararoni-ecosystem/fararoni-core/9f16edb/fararoni-core-9f16edb.pom).
> If it returns XML (not "building..."), you are ready to `mvn package`.

#### Option B — Local install from source (offline reproducibility)

If you cannot reach JitPack or want offline reproducibility, clone
the public repo and install the jar to your local Maven cache once:

```bash
git clone https://github.com/ebercruzf/fararoni-ecosystem
cd fararoni-ecosystem
mvn -pl fararoni-core -am install -DskipTests
cd -
```

This places `fararoni-core-1.0.0.jar` in
`~/.m2/repository/dev/fararoni/fararoni-core/1.0.0/`. The current
`pom.xml` of fara-hack will pick it up automatically. **No edit
to `pom.xml` is required for this option** — it is the default.

> **Note for monorepo developers:** if you cloned from inside the
> private Fararoni monorepo (`Llm-fararoni-v2`), the same commands
> apply — just point at that directory instead.

### 3. Copy the environment template

```bash
cp .env.example .env
```

Defaults work out of the box. **Optional** edits if you want to:

```env
# Optional: change host port if 8080 is taken
HACK_WEB_PORT=8080

# Optional: GitHub PAT for the github MCP bridge (Step 3 ticketing)
GITHUB_TOKEN=ghp_...

# Optional: Slack bot token (Step 4 notifications)
SLACK_BOT_TOKEN=xoxb-...
```

If you skip the optional tokens, the system runs in **graceful
degradation mode**: tickets are stored in-memory, notifications are
logged but not sent externally, and the reasoning trace still
streams every step in real time. **All 7 pipeline steps remain
visible in the live trace.**

### 4. Run

```bash
docker compose up --build
```

First run takes ~3 minutes (Docker pulls Node 22, Nginx, Distroless
JDK, NATS — about 500 MB of base images).
Subsequent runs are <10 seconds.

You'll see:

```
fara-hack-nats   | INFO  Server is ready
fara-hack-app    | [HACK] Ready. Listening on http://0.0.0.0:8080
fara-hack-web    | nginx: starting nginx 1.27.x
```

## Verify it works

### Browser test

Open **http://localhost:8080** in your browser. You'll see:

- **Left panel:** form to submit a bug report (email, title,
  description, optional stack trace, optional file attachment)
- **Right panel:** terminal-styled "Reasoning Trace" panel
  (waiting for events)

Fill in the form and click **Submit bug report**. Watch the right
panel as the reasoning trace fills up with the 7 pipeline steps in
real time:

```
12:34:01  STEP_1_RECEIVED      [controller]   Bug report received from alice@example.com
12:34:01  STEP_2_TRIAGE        [operations-analyst]  Extracting signals...
12:34:02  STEP_2_TRIAGE        [operations-analyst]  Severity=P1, affectedModules=[...]
12:34:02  STEP_2_5_FORENSIC    [data-guardian]       Querying ForensicMemory...
12:34:02  STEP_3_TICKET        [integration-broker]  Ticket created: FH-1
12:34:03  STEP_4_NOTIFY        [integration-broker]  Notifying technical team
12:34:03  DONE                 [controller]          Pipeline complete. Ticket=FH-1
```

The ticket appears in the bottom-left list with severity badge and
"Mark Resolved" button. Clicking **Mark Resolved** triggers Step 5:
within ~30 seconds the trace shows
`STEP_5_REPORTER_NOTIFIED`.

### CLI test (alternative)

If you prefer terminal:

```bash
# Health
curl http://localhost:8080/api/health
# {"status":"healthy"}

# Submit
curl -X POST http://localhost:8080/api/triage/report \
  -H "Content-Type: application/json" \
  -d '{
    "reporterEmail":"alice@example.com",
    "title":"NullReferenceException in CatalogController",
    "description":"POST /items returns 500 when brand field is missing",
    "stackTrace":"System.NullReferenceException at CatalogController.cs:142"
  }'
# {"status":"accepted","correlationId":"...","wsUrl":"/ws/events?correlationId=..."}

# Watch tickets
curl http://localhost:8080/api/triage/tickets

# Resolve a ticket → triggers Step 5
curl -X POST http://localhost:8080/api/triage/tickets/FH-1/resolve

# Watch the live reasoning trace via WebSocket (needs wscat)
wscat -c "ws://localhost:8080/ws/events?correlationId=<your-cid>"
```

## Rebuild a single service (without downtime)

If you change configuration (e.g. `docker-compose.yml` or `.env`)
and only need to restart the backend:

```bash
docker compose up --build -d fara-hack
```

This rebuilds and recreates **only** the `fara-hack` container while
NATS and the web frontend keep running. Useful for quick iterations
without a full `docker compose down`.

## Monitor backend logs

To stream the backend logs in real time (like `tail -f`):

```bash
docker compose logs -f fara-hack
```

To show only the last 50 lines and then keep following:

```bash
docker compose logs -f --tail 50 fara-hack
```

Press `Ctrl+C` to stop watching. This does **not** stop the container.

> **Note:** Log timestamps are in UTC. If your host is in a different
> timezone (e.g. CST = UTC-6), subtract accordingly.

## Stop

```bash
docker compose down
```

To wipe state (NATS data, mock-eshop, etc.):

```bash
docker compose down -v
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `target/fara-hack-1.0.jar: not found` during build | You skipped step 2. Run `mvn -B package -DskipTests` first. |
| Port 8080 already in use | Edit `.env`, set `HACK_WEB_PORT=8081`, re-run `docker compose up --build` |
| `docker compose: command not found` | You have only the Docker CLI, not the compose plugin. Run `brew install docker-compose && mkdir -p ~/.docker/cli-plugins && ln -sfn /opt/homebrew/opt/docker-compose/bin/docker-compose ~/.docker/cli-plugins/docker-compose` |
| First build hangs at "npm install" | First run only — Angular pulls ~400 packages. Wait ~60s. |
| Browser shows blank page after submit | Check browser DevTools → Network → WS. The connection to `/ws/events` should be **101 Switching Protocols**. If not, Nginx upgrade headers may be missing — verify `web/nginx.conf` lines 38-49. |
| Reasoning trace never appears | The backend container may not have started yet. Run `docker compose logs fara-hack` and look for `[HACK] Ready. Listening on http://0.0.0.0:8080` |
| Ticket created but no notification log | Expected in graceful degradation mode without `GITHUB_TOKEN` or `SLACK_BOT_TOKEN`. The trace will say "skipped (no live bridge)" — this is the defensive `bridge.isAlive()` check in action and demonstrates the production-ready safety pattern. |
| `npm` errors with `EPERM uv_cwd` | Your shell is in a deleted directory. Run `cd ~` then retry. |

## Architecture in one diagram

```
                    ┌──────────────────────┐
   Browser  ──HTTP──▶  web (Nginx + Angular)
                    │       :80            │
                    │  - Static SPA bundle │
                    │  - /api proxy        │
                    │  - /ws upgrade       │
                    └─────────┬────────────┘
                              │
                    (compose internal network)
                              │
                    ┌─────────▼────────────┐
                    │  fara-hack (Javalin) │
                    │   :8080  expose only │
                    │  - REST              │
                    │  - WebSocket         │
                    │  - Triage pipeline   │
                    └─────────┬────────────┘
                              │
                    ┌─────────▼────────────┐
                    │ nats (JetStream)     │
                    │  :4222 expose only   │
                    └──────────────────────┘
```

**Only one port is exposed to the host:** the web container on
`${HACK_WEB_PORT:-8080}`. The backend and NATS are unreachable from
outside — they live in the compose internal network and are only
accessible via Nginx reverse proxy. This satisfies the AgentX
contest rule "only necessary ports exposed".

## Endpoints reference

| Endpoint | Type | Purpose |
|---|---|---|
| `GET /api/health` | REST | Liveness check |
| `GET /api/version` | REST | Version JSON |
| `POST /api/triage/report` | REST | Submit a bug report (Steps 1-4 + 6) |
| `GET /api/triage/tickets` | REST | List created tickets |
| `POST /api/triage/tickets/{id}/resolve` | REST | Mark RESOLVED (triggers Step 5) |
| `WS /ws/events?correlationId=X` | WebSocket | Live reasoning trace stream |

## Next

- [README.md](README.md) — Architecture overview and pitch
- [AGENTS_USE.md](AGENTS_USE.md) — What each agent does (with observability evidence)
- [SCALING.md](SCALING.md) — How it scales, decisions, assumptions
- [docs/architecture/ADR-001-SOC-REVERSE-PROXY.md](docs/architecture/ADR-001-SOC-REVERSE-PROXY.md) — Why the architecture is split
- [docs/architecture/TRIAGE-PIPELINE.md](docs/architecture/TRIAGE-PIPELINE.md) — The 7 steps in detail
