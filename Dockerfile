# Simple single-stage build for Cloud Build
FROM openjdk:17-jdk-slim

# Install required packages
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Make gradlew executable
RUN chmod +x gradlew

# Copy source code
COPY src src

# Build the application (only bootJar)
RUN ./gradlew bootJar -x test --no-daemon

# Create app user for security
RUN useradd -r -s /bin/false app

# Create data directory
RUN mkdir -p /app/data && chown -R app:app /app

# Switch to app user
USER app

# Expose port
EXPOSE 8080

# Environment variables optimized for Cloud Run
ENV JAVA_OPTS="-Xmx768m -Xms256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom -Djava.awt.headless=true -Dfile.encoding=UTF-8" \
    SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "echo 'Starting application...' && ls -la build/libs/ && java $JAVA_OPTS -jar build/libs/exch_sim-0.0.1-SNAPSHOT.jar"]