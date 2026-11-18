# Construir desde la raíz del repositorio:
#   docker build -f servicio-pedidos/Dockerfile -t servicio-pedidos .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -q -pl servicio-pedidos -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home appuser
COPY --from=build /workspace/servicio-pedidos/target/servicio-pedidos-*-SNAPSHOT.jar app.jar
USER appuser
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
