# TourGaze — single-container image (matros convention).
# The fat JAR already bundles the built SPA under /static, so this one image
# serves both the REST API and the UI on port 8085.
#
# Build the JAR first (from the project root), then build the image:
#   cd frontend && npm ci && npm run build
#   mvn -f server/pom.xml clean package -DskipTests
#   docker compose -f infra/docker-compose.yml up --build
FROM eclipse-temurin:21-jre-noble

# Headless: no AWT/desktop needed (image rescale uses commons-imaging, pure Java).
ENV JAVA_OPTS="-Djava.awt.headless=true"

# Run as a non-root user. Create the data dir and hand it to that user *before*
# dropping privileges so a fresh (named) volume inherits writable ownership —
# otherwise H2 can't create /data/db.
RUN addgroup --system tourgaze && adduser --system --ingroup tourgaze tourgaze \
    && mkdir -p /data && chown -R tourgaze:tourgaze /data
USER tourgaze:tourgaze

WORKDIR /app

# Copy the fat JAR (built on the host). Glob tolerates the version in the name.
COPY --chown=tourgaze:tourgaze server/target/tourgaze-server-*.jar application.jar

# H2 file DB + media/cache/tiles all live under the data dir; mount it as a volume.
ENV TOURGAZE_DATA_DIR=/data
VOLUME /data

EXPOSE 8085

ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "application.jar", "--app.start-browser=false"]
