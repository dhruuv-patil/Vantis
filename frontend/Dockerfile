# syntax=docker/dockerfile:1

# Stage 1: Dependencies - install only production and build dependencies
FROM node:20-alpine AS deps
RUN apk add --no-cache libc6-compat
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

# Stage 2: Builder - build Next.js with standalone output
FROM node:20-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# Build-time environment variables for Next.js inlining
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ARG NEXT_PUBLIC_APP_NAME=Vantis
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}
ENV NEXT_PUBLIC_APP_NAME=${NEXT_PUBLIC_APP_NAME}

ENV NEXT_TELEMETRY_DISABLED=1
ENV NODE_ENV=production

RUN npm run build

# Stage 3: Runner - minimal production image
FROM node:20-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

# Create non-root system group and user, make /app owned by flowops
RUN addgroup -S flowops && adduser -S -G flowops flowops && chown flowops:flowops /app

# Copy standalone output, static assets, and public directory
# Using COPY --chown avoids recursive chown overhead and layer duplication
COPY --from=builder --chown=flowops:flowops /app/public ./public
COPY --from=builder --chown=flowops:flowops /app/.next/standalone ./
COPY --from=builder --chown=flowops:flowops /app/.next/static ./.next/static

USER flowops

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://127.0.0.1:3000/ || exit 1

CMD ["node", "server.js"]
