# syntax=docker/dockerfile:1.7
# ───────────────────────────────────────────────────────────────────
# Fara-Hack Web — Multistage Dockerfile
# Stage 1: Node 22 — build the Angular 20 production bundle
# Stage 2: Nginx alpine — serve static files + proxy /api and /ws
# Final image: ~25 MB
# ───────────────────────────────────────────────────────────────────

# ═══════════════════════════════════════════════════════════════════
# Stage 1 — Builder (Node 22 + Angular 20)
# ═══════════════════════════════════════════════════════════════════
FROM node:22-alpine AS builder

WORKDIR /build

# Layer caching: install deps first, then copy sources
COPY package.json ./
RUN npm install --no-audit --no-fund

COPY tsconfig.json tsconfig.app.json angular.json ./
COPY src ./src
COPY public ./public

RUN npx ng build --configuration production \
    && ls -lh dist/fara-hack-web/

# ═══════════════════════════════════════════════════════════════════
# Stage 2 — Runtime (Nginx serving static + reverse proxy)
# ═══════════════════════════════════════════════════════════════════
FROM nginx:1.27-alpine

LABEL org.opencontainers.image.title="Fara-Hack Web"
LABEL org.opencontainers.image.description="Angular 20 UI for Fara-Hack 1.0 (AgentX Hackathon)"
LABEL org.opencontainers.image.licenses="MIT"

COPY --from=builder /build/dist/fara-hack-web/browser/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1
