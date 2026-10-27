# Backend image (docs/16-deployment-and-operations.md §5). Built from the backend/ directory.
#   docker build -t vaultchat-backend .                  → runtime image (default)
#   docker build --target migrate -t vaultchat-migrate . → one-shot `prisma migrate deploy`
# No secrets are baked in: configuration comes from the environment at run time.

FROM node:22.22.0-bookworm-slim AS build
WORKDIR /app
# Schema first: `npm ci` runs `prisma generate` (postinstall).
COPY package.json package-lock.json prisma.config.ts ./
COPY prisma ./prisma
RUN npm ci
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

# Migrations need the Prisma CLI (a dev dependency); run with MIGRATE_DATABASE_URL set.
FROM build AS migrate
USER node
CMD ["npx", "--no-install", "prisma", "migrate", "deploy"]

FROM build AS prune
RUN npm prune --omit=dev --ignore-scripts

FROM node:22.22.0-bookworm-slim AS runtime
ENV NODE_ENV=production
WORKDIR /app
COPY --from=prune /app/package.json ./
COPY --from=prune /app/node_modules ./node_modules
COPY --from=prune /app/dist ./dist
# Migrations are read at run time by /readyz to detect pending migrations.
COPY --from=prune /app/prisma/migrations ./prisma/migrations
USER node
EXPOSE 3000 3001
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["node", "-e", "fetch('http://127.0.0.1:' + (process.env.PORT || 3000) + '/healthz').then((r) => process.exit(r.ok ? 0 : 1), () => process.exit(1))"]
CMD ["node", "dist/server.js"]
