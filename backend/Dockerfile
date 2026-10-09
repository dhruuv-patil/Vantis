# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build

# Pre-fetch dependencies in a separate layer to maximize Docker cache reuse
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# Copy application sources and package the production executable JAR
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create unprivileged system group and user 'flowops'
RUN addgroup -S flowops && adduser -S -G flowops -h /app flowops

# Copy only the compiled fat JAR with unprivileged ownership (no chown -R)
COPY --chown=flowops:flowops --from=build /build/target/flowops-api.jar app.jar

# Container-aware JVM memory & GC configuration:
# - MaxRAMPercentage=75 leaves headroom for Metaspace, threads, native memory, and OS buffers
# - InitialRAMPercentage=25 prevents excessive heap pre-allocation
# - UseG1GC ensures low-pause, high-throughput garbage collection
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=25 -XX:+UseG1GC"

EXPOSE 8080

USER flowops

# Healthcheck using built-in BusyBox wget to avoid bloating image with curl or extra packages
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:8080/health/ready || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
