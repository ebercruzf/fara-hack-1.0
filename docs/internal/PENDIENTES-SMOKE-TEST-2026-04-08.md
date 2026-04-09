# Pendientes — Smoke Test "Humo Blanco" (8 abril mañana)

**Fecha objetivo:** 2026-04-08 antes de grabar el video del demo
**Estado:** 📋 Pendiente — verificar antes del submission
**Documento padre:** `./ADR-001-SOC-REVERSE-PROXY.md`

Checklist de verificación obligatoria **antes** de grabar el video y
hacer el submission al concurso. Cada item es un "smoke test" que
toma <5 minutos y previene un fallo embarazoso en vivo.

---

## 🔴 BLOQUEANTES — los 3 items críticos

### 1. CORS — `anyHost()` desde el contenedor Nginx funciona end-to-end

**Archivo:** `src/main/java/dev/fararoni/core/hack/api/BugReportController.java`
**Línea conceptual:** `cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()))`

**Riesgo concreto:** En desarrollo local con `ng serve` (proxy
config) el origen del browser es `localhost:4200` y el backend es
`localhost:8080`. En el Docker compose, el browser ve `localhost:8080`
(el puerto del contenedor `web` Nginx) y Nginx hace proxy interno a
`fara-hack:8080` (red interna del compose). El header `Origin` que ve
Javalin puede ser distinto al `Host` y disparar un preflight CORS
inesperado.

**Cómo verificar (smoke test, ~2 min):**

```bash
# Levantar el stack
cd /Users/ecruz/Documents/Proyectos/DevLlM/CoreVersion2/fara-hack-1.0
docker compose up -d --build
sleep 5

# Test de preflight CORS desde el origen del browser
curl -i -X OPTIONS http://localhost:8080/api/triage/report \
  -H "Origin: http://localhost:8080" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: Content-Type"

# Esperado:
# HTTP/1.1 200 OK
# Access-Control-Allow-Origin: http://localhost:8080
# Access-Control-Allow-Methods: ...
# Access-Control-Allow-Headers: Content-Type
```

**Fix si falla:**

- Opción A: Eliminar la regla CORS por completo (Nginx hace mismo
  origen, no debería hacer falta CORS si todo va por
  `http://localhost:8080`)
- Opción B: Cambiar `anyHost()` a `allowHost("localhost:8080",
  "localhost:4200")` explícito
- Opción C: Configurar Nginx para añadir headers CORS en lugar de
  delegarlos al backend (más limpio si Nginx es la fachada)

**Recomendación:** opción A. Si Nginx es la fachada y todo es
same-origin desde el punto de vista del browser, no necesitamos CORS
en Javalin.

---

### 2. Nginx Upgrade — el WebSocket NO se cierra a los pocos segundos

**Archivo:** `web/nginx.conf` líneas 39-49

**Riesgo concreto:** Los headers `Upgrade $http_upgrade` y
`Connection "upgrade"` están escritos correctamente, pero hay 2 cosas
que pueden cerrar el socket prematuramente:

1. **`proxy_read_timeout` muy corto** — el default de Nginx es 60s.
   Si no hay tráfico en 60s, Nginx cierra. Yo puse `3600s` en el
   config pero hay que **verificar que se aplica al location /ws/
   y no al fallback genérico**.
2. **Javalin idle timeout** — Jetty (que va dentro de Javalin) tiene
   un default de 30s para conexiones idle. Hay que verificar si nuestra
   instancia lo respeta.

**Cómo verificar (smoke test, ~3 min):**

```bash
# Terminal 1: levantar stack
docker compose up -d --build && sleep 5

# Terminal 2: enviar bug report y obtener correlationId
CID=$(curl -s -X POST http://localhost:8080/api/triage/report \
  -H "Content-Type: application/json" \
  -d '{
    "reporterEmail":"alice@example.com",
    "title":"Smoke test WS persistence",
    "description":"Probando que el socket aguanta 90 segundos abierto"
  }' | python3 -c "import sys,json; print(json.load(sys.stdin)['correlationId'])")

echo "correlationId=$CID"

# Terminal 3: conectar wscat (npm install -g wscat) y dejar 90s abierto
wscat -c "ws://localhost:8080/ws/events?correlationId=$CID"

# Esperado:
# Connected (press CTRL+C to quit)
# < {"type":"CONNECTED",...}
# < {"type":"TRACE","step":"STEP_1_RECEIVED",...}
# ... (eventos llegan)
# < {"type":"DONE",...}
#
# DESPUÉS de DONE, dejar la conexión 90 segundos.
# Si después de 90s sigue abierto y wscat no muestra "Disconnected",
# el upgrade está bien.
```

**Fix si se cierra antes de 90s:**

- Verificar que `nginx.conf` tiene `proxy_read_timeout 3600s` en el
  bloque `location /ws/` (no fuera)
- Añadir `proxy_send_timeout 3600s` también
- En el backend, configurar Javalin con
  `cfg.jetty.modifyServletContextHandler(sch -> sch.getMaxFormContentSize(...))`
  no aplica — pero **sí buscar `wsIdleTimeout`** en la API de Javalin
  6.x si existe
- Como red de seguridad: el `triage-ws.service.ts` ya tiene
  `closeObserver` que dispara reconnect implícito vía
  `connect(correlationId)` — pero requiere que el componente
  detecte el close y llame de nuevo

---

### 3. Bind Mount — el repo eShop es accesible desde el contenedor

**Archivo:** `docker-compose.yml` línea 79
**Variable env:** `ESHOP_REPO_PATH` (default `./mock-eshop`)

**Riesgo concreto:** Si el path no existe en el host, Docker Compose
**crea un directorio vacío** automáticamente (comportamiento por
default de los bind mounts). Resultado: el agente Code Surgeon abre
`/repo/src/Services/Catalog.API/...` y obtiene `FileNotFoundException`
silencioso. **El paso 6 nunca dispara y nadie se entera** porque el
kill-switch lo absorbe.

**Cómo verificar (smoke test, ~3 min):**

```bash
# Caso 1: con placeholder mock-eshop
mkdir -p mock-eshop/src/Services/Catalog.API
echo "// stub for smoke test" > mock-eshop/src/Services/Catalog.API/CatalogController.cs

docker compose up -d --build
sleep 3

# Verificar que el container ve el archivo
docker compose exec fara-hack ls -la /repo/src/Services/Catalog.API/
# Esperado: CatalogController.cs visible

docker compose exec fara-hack cat /repo/src/Services/Catalog.API/CatalogController.cs
# Esperado: "// stub for smoke test"

# Caso 2: con repo eShop real (si lo tienes clonado)
ESHOP_REPO_PATH=/path/to/eShop docker compose up -d --build
sleep 3
docker compose exec fara-hack ls /repo
# Esperado: ver la estructura real del eShop (.github, src, etc)
```

**Fix si no se ve:**

- Verificar permisos del path en macOS (Docker Desktop necesita
  acceso a la carpeta — System Settings → Privacy → Files & Folders →
  Docker)
- Verificar que el path es absoluto en `.env` (relativo a
  `docker-compose.yml`)
- En Linux, verificar que el usuario `nonroot` (uid 65532) del
  container puede leer el path → si no, `chmod -R o+rX /path/to/eShop`

---

## 🟡 ADICIONALES — verificación recomendada (no bloqueante)

### 4. Compilación limpia del backend

```bash
cd /Users/ecruz/Documents/Proyectos/DevLlM/CoreVersion2/Llm-fararoni-v2
mvn -pl fararoni-core -am install -DskipTests
# Esperado: BUILD SUCCESS, fararoni-core-1.0.0.jar en ~/.m2/

cd ../fara-hack-1.0
mvn -B clean package -DskipTests
# Esperado: BUILD SUCCESS, target/fara-hack-1.0.jar
```

### 5. Tests del Sentinel pasan

```bash
cd /Users/ecruz/Documents/Proyectos/DevLlM/CoreVersion2/fara-hack-1.0
mvn -B test
# Esperado: 8 tests, 0 failures
```

### 6. Frontend Angular compila

```bash
cd /Users/ecruz/Documents/Proyectos/DevLlM/CoreVersion2/fara-hack-1.0/web
npm install --no-audit --no-fund
npm run build
# Esperado: dist/fara-hack-web/browser/ con index.html + main-*.js
```

### 7. Health check de los 3 contenedores

```bash
docker compose up -d --build
sleep 10
docker compose ps
# Esperado: 3 containers UP (web, fara-hack, nats)

curl http://localhost:8080/api/health
# Esperado: {"status":"healthy"}

curl http://localhost:8080/
# Esperado: HTML del index.html de Angular
```

### 8. Aplicar corrección §11 del archivo de decisiones (sidecar Apache 2.0)

**Bloqueante de coherencia narrativa**, no de funcionamiento. Ver
`./DECISIONES-IMPLEMENTACION-2026-04-07.md` §11 para el plan paso a
paso. Si no hay tiempo el 8 abril, dejar el `MinimalMcpBridge` actual
y eliminar la mención al sidecar Apache 2.0 del README.

---

## 🟢 PRE-VIDEO — checklist de grabación

Antes de presionar REC en YouTube:

- [ ] Los 3 items 🔴 BLOQUEANTES verificados como verdes
- [ ] `docker compose down -v && docker compose up --build` corre
  desde cero sin errores manuales
- [ ] El form del UI envía bug reports correctamente
- [ ] El terminal del Reasoning Trace muestra al menos 8 líneas en
  vivo (de STEP_1 a DONE)
- [ ] El botón "Mark Resolved" cambia el estado del ticket y el
  watcher dispara STEP_5_REPORTER_NOTIFIED en el trace
- [ ] El demo está en inglés (interfaz, voz del video)
- [ ] El video está bajo 3 minutos
- [ ] El tag `#AgentXHackathon` está en título Y descripción de
  YouTube

---

## 🚀 POST-DEMO — checklist de submission

- [ ] Repo público en GitHub con MIT
- [ ] `LICENSE` archivo presente en raíz del repo
- [ ] `README.md`, `AGENTS_USE.md`, `SCALING.md`, `QUICKGUIDE.md`
  presentes
- [ ] `docker-compose.yml`, `Dockerfile`, `.env.example` presentes
- [ ] Link del video YouTube en el README
- [ ] Push de la branch principal al repo público
- [ ] Submission form del concurso completado con el link al repo

---

## Sello

Lista de verificación creada el 2026-04-07 noche.
Para usar la mañana del 2026-04-08 antes del demo y la grabación.
Cada item tiene su comando de smoke test ejecutable y su fix
documentado.
