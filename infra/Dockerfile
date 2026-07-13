# TourGaze — single-container image (matros convention).
# The fat JAR already bundles the built SPA under /static, so this one image
# serves both the REST API and the UI on port 8085.
#
# Build the JAR first (from the project root), then build the image:
#   cd frontend && npm ci && npm run build
#   mvn -f server/pom.xml clean package -DskipTests
#   docker compose -f infra/docker-compose.yml up --build
FROM eclipse-temurin:25-jre-noble

# Headless: no AWT/desktop needed (image rescale uses commons-imaging, pure Java).
ENV JAVA_OPTS="-Djava.awt.headless=true"

# Run as a non-root user. Create the data dir AND the /app workdir and hand both
# to that user *before* dropping privileges: a fresh (named) volume inherits
# writable ownership (otherwise H2 can't create /data/db), and /app must be
# tourgaze-owned so the AOT-cache training step below can write into it.
RUN addgroup --system tourgaze && adduser --system --ingroup tourgaze tourgaze \
    && mkdir -p /data /app && chown -R tourgaze:tourgaze /data /app
USER tourgaze:tourgaze

WORKDIR /app

# Copy the fat JAR (built on the host). Glob tolerates the version in the name.
COPY --chown=tourgaze:tourgaze server/target/tourgaze-server-*.jar application.jar

# ── AOT cache (JEP 483/514, Java 24+) ────────────────────────────────────────
# A stronger successor to plain AppCDS: one training boot with
# -Dspring.context.exit=onRefresh loads the app's classes then exits, and
# -XX:AOTCacheOutput records them AND their linked/verified state into a cache the
# real launch memory-maps instead of re-loading, re-verifying and re-linking —
# worth ~1.3s off cold start here. The cache is pinned to THIS image's JDK, so it
# is generated in-image (with the container's Java 25), never copied from the host
# build. A throwaway data dir keeps the training H2/Flyway files out of the /data
# volume. A stale/mismatched cache is silently ignored (warning, not a crash), so
# this can never break startup.
RUN java -XX:AOTCacheOutput=app.aot \
        -Dspring.context.exit=onRefresh \
        -Djava.awt.headless=true \
        -jar application.jar \
        --server.port=0 --app.start-browser=false --app.backup.on-startup=false \
        --tourgaze.data-dir=/tmp/cds-train \
    && rm -rf /tmp/cds-train

# H2 file DB + media/cache/tiles all live under the data dir; mount it as a volume.
ENV TOURGAZE_DATA_DIR=/data
VOLUME /data

EXPOSE 8085

ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-Djava.awt.headless=true", "-jar", "application.jar", "--app.start-browser=false"]
