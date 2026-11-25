# Build the jar first: ./mvnw package (the GitLab pipeline does both).
FROM eclipse-temurin:21-jre

# A fixed numeric user, so Kubernetes can check runAsNonRoot against the image.
RUN groupadd --gid 10001 acquirer && useradd --uid 10001 --gid acquirer --no-create-home --home-dir /app \
    --shell /usr/sbin/nologin acquirer

WORKDIR /app
COPY target/acquirer-sim.jar /app/acquirer-sim.jar
USER 10001:10001

EXPOSE 8091
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/acquirer-sim.jar"]
