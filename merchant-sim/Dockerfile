# Build the jar first: ./mvnw package (the GitLab pipeline does both).
FROM eclipse-temurin:21-jre

# A fixed numeric user, so Kubernetes can check runAsNonRoot against the image.
RUN groupadd --gid 10001 merchant && useradd --uid 10001 --gid merchant --no-create-home --home-dir /app \
    --shell /usr/sbin/nologin merchant

WORKDIR /app
COPY target/merchant-sim.jar /app/merchant-sim.jar
USER 10001:10001

EXPOSE 8092
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/merchant-sim.jar"]
