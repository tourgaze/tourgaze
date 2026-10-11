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
# writable ownership (otherwise H2 can't create /data/db), and the app may write
# next to its jar (e.g. a JVM crash report) without root.
RUN addgroup --system tourgaze && adduser --system --ingroup tourgaze tourgaze \
    && mkdir -p /data /app && chown -R tourgaze:tourgaze /data /app
USER tourgaze:tourgaze

WORKDIR /app

# Copy the fat JAR (built on the host). Glob tolerates the version in the name.
COPY --chown=tourgaze:tourgaze server/target/tourgaze-server-*.jar application.jar

# ── No AOT cache in this image — on purpose ──────────────────────────────────
# 1.4.2 trained a JEP 483/514 AOT cache here, at image build time. That cache
# carries code for the BUILD machine's CPU: built on a CI runner with Temurin
# 25.0.4.1, it made the JVM die with SIGILL ~4s after start on CPUs without the
# runner's instruction set (e.g. a 12th-gen Intel i7), instead of being ignored.
# An image runs on CPUs we don't know, and ~1.3s faster cold start isn't worth
# that for a long-running server. (The desktop build trains its cache on the
# machine that builds it, see server/pom.xml.)

# H2 file DB + media/cache/tiles all live under the data dir; mount it as a volume.
ENV TOURGAZE_DATA_DIR=/data
VOLUME /data

# Trust X-Forwarded-Proto/-Host from a reverse proxy on a private network, so the
# app sees the public https://… address it is served under and doesn't mistake the
# browser's own requests for a foreign site (e.g. Caddy serving https://nas.local).
ENV SERVER_FORWARDHEADERSSTRATEGY=native

EXPOSE 8085

ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "application.jar", "--app.start-browser=false"]
