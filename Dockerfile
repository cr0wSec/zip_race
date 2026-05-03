# -----------------------------------
# Build phase - needs full JDK
# noble uses glibc -> good for sqlite-jdbc
FROM eclipse-temurin:25-jdk-noble AS builder

WORKDIR /build

# Copy only the Maven descriptor first to leverage Docker layer caching:
# dependencies are re-downloaded only if pom.xml changes, not on every code change.
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Pre-fetch dependencies. This layer is cached unless pom.xml changes.
# -B: batch mode, no user prompt. -q: quiet. Note: hide INFO logs
RUN ./mvnw -B -q dependency:go-offline

# Copy the source and build the JAR. Same flags, no tests.
COPY src ./src
RUN ./mvnw -B -q -DskipTests package

# -----------------------------------
# Runtime phase - only JRE needed
FROM eclipse-temurin:25-jre-noble AS runtime

# Install curl for the healthcheck. Deletes capt caches.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Create a non-root user for security (defense in depth).
RUN groupadd --system ziprace \
 && useradd  --system --gid ziprace --home /app --shell /usr/sbin/nologin ziprace

WORKDIR /app

# Copy only the built JAR from the build stage. Owner is zipracre:ziprace instead of root:root.
# Renamed to a stable filename so the ENTRYPOINT does not depend on the version string.
COPY --from=builder --chown=ziprace:ziprace /build/target/*.jar /app/app.jar

# The SQLite file lives here; this directory is the volume mount point. Create here to avoir root:root rights.
RUN mkdir -p /app/data && chown -R ziprace:ziprace /app/data

USER ziprace

EXPOSE 8080

# the flag allows sqlite-jdbc to load its native lib without warning (no fatal error on future java versions)
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]