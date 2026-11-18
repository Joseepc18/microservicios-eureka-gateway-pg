# Contexto de construcción: la raíz del repositorio (ver README).
#   docker build -f servicio-productos/Dockerfile -t servicio-productos .

# --- Compilación ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -B -pl servicio-productos -am -DskipTests package

# --- Ejecución ---
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
USER app
WORKDIR /app
COPY --from=build /workspace/servicio-productos/target/servicio-productos-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
