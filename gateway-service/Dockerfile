# Build the jar first: ./mvnw package (the GitLab pipeline does both).
FROM eclipse-temurin:21-jre

# A fixed numeric user, so Kubernetes can check runAsNonRoot against the image.
RUN groupadd --gid 10001 gateway && useradd --uid 10001 --gid gateway --no-create-home --home-dir /app \
    --shell /usr/sbin/nologin gateway

WORKDIR /app
COPY target/gateway-service-*.jar /app/gateway-service.jar
USER 10001:10001

EXPOSE 8090
# Heap from the container's memory limit; 60 % leaves room for metaspace and threads.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/gateway-service.jar"]
