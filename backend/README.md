# Vault Chat backend

Node.js + TypeScript + Fastify + PostgreSQL (Prisma 7). Design: `../docs/02-architecture.md`,
API: `../docs/14-api-contracts.md`, status: `../docs/IMPLEMENTATION_PLAN.md`.

Current scope (Phase 1): configuration, logging, error handling, `/healthz`, `/readyz`, database
connectivity. No accounts or app features yet.

## Local setup

```bash
# 1. PostgreSQL (from the repo root; or use any local PostgreSQL 16)
cp deploy/.env.dev.example deploy/.env.dev        # set a local password
docker compose --env-file deploy/.env.dev -f deploy/docker-compose.dev.yml up -d

# 2. Backend
cd backend
cp .env.example .env                               # set DATABASE_URL to match
npm ci                                             # also runs `prisma generate`
npm run dev                                        # http://localhost:3000/healthz
```

## Scripts

| Script | What it does |
|---|---|
| `npm run dev` | Run with reload (tsx) |
| `npm run build` / `npm start` | Compile to `dist/` / run compiled server |
| `npm run typecheck` | TypeScript, no emit |
| `npm run lint` | ESLint |
| `npm test` | Vitest. Database integration tests run only when `TEST_DATABASE_URL` points to a **disposable** database |
| `npm run prisma:migrate:dev` | Create/apply migrations on a **development** database |
| `npm run prisma:migrate:deploy` | Apply committed migrations (release step). Never run against production without approval |

## Rules

- Secrets only in `.env` (git-ignored) or the deployment platform. `.env.example` holds placeholders.
- Never log request bodies on sensitive routes; add new sensitive field names to `src/logger.ts`.
- Every route declares a JSON schema; unknown fields are rejected.
- Errors to clients go through `AppError` / the shared handler — never raw messages or stacks.
