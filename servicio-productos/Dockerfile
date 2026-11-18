# Construir desde la raíz del repositorio:
#   docker build -f servicio-productos/Dockerfile -t servicio-productos .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -q -pl servicio-productos -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home appuser
COPY --from=build /workspace/servicio-productos/target/servicio-productos-*-SNAPSHOT.jar app.jar
USER appuser
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
