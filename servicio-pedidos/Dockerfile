# Contexto de construcción: la raíz del repositorio (ver README).
#   docker build -f servicio-pedidos/Dockerfile -t servicio-pedidos .

# --- Compilación ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -B -pl servicio-pedidos -am -DskipTests package

# --- Ejecución ---
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
USER app
WORKDIR /app
COPY --from=build /workspace/servicio-pedidos/target/servicio-pedidos-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
