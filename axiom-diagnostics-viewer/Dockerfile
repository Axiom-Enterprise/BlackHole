# Axiom Diagnostics Viewer — self-hosted report viewer.
# Build context is this module directory; the shadow jar must be built first:
#   ../gradlew :axiom-diagnostics-viewer:shadowJar
#   docker build -t axiom-diagnostics-viewer:latest axiom-diagnostics-viewer
FROM eclipse-temurin:25-jre

WORKDIR /app

# curl is used by the container healthcheck below (the base image ships neither curl nor wget).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY build/libs/axiom-diagnostics-viewer-*-all.jar /app/viewer.jar

# Overridable at run time. PORT/PUBLIC_URL/TTL_MINUTES are read by the app from the env.
# Links never live longer than 60 minutes; the app clamps TTL_MINUTES to that ceiling.
ENV PORT=8080 \
    PUBLIC_URL=http://localhost:8080 \
    TTL_MINUTES=60

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PORT}/health" || exit 1

ENTRYPOINT ["java", "-jar", "/app/viewer.jar"]
