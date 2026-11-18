# Construir desde la raíz del repositorio:
#   docker build -f discovery-server/Dockerfile -t discovery-server .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -q -pl discovery-server -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home appuser
COPY --from=build /workspace/discovery-server/target/discovery-server-*-SNAPSHOT.jar app.jar
USER appuser
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
