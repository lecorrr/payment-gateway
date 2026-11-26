# Build the jar first: ./mvnw package (the GitLab pipeline does both).
FROM eclipse-temurin:21-jre

# A fixed numeric user, so Kubernetes can check runAsNonRoot against the image.
RUN groupadd --gid 10001 risk && useradd --uid 10001 --gid risk --no-create-home --home-dir /app \
    --shell /usr/sbin/nologin risk

WORKDIR /app
COPY target/risk-service.jar /app/risk-service.jar
USER 10001:10001

EXPOSE 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/risk-service.jar"]
