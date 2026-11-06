# One image: the Vite dashboard is built, then served by Spring Boot as static files next to the API.
# Build:  docker build -t leash .
# Run:    docker run -p 8080:8080 --env-file .env leash

# ── 1. Dashboard ─────────────────────────────────────────────
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ── 2. Backend jar (tests run in CI/locally, not in the image build) ──
FROM maven:3.9-eclipse-temurin-21 AS jar
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /web/dist/ ./src/main/resources/static/
RUN mvn -q -B -DskipTests package

# ── 3. Runtime ───────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S leash && adduser -S leash -G leash
WORKDIR /app
COPY --from=jar /app/target/leash.jar ./leash.jar
USER leash
ENV PORT=8080 LEASH_DEV_ENDPOINTS=false
EXPOSE 8080
# Render's free instance has 512 MB: let the heap take most of it, leave room for metaspace and threads.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+UseSerialGC", "-jar", "/app/leash.jar"]
