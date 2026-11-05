FROM eclipse-temurin:25-jre

WORKDIR /app
COPY target/backend-*.jar app.jar

# Port inside the container stays 8080 (stack, Caddy), locally server.port=18180 applies
ENV SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
