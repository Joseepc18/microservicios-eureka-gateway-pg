# Contexto de construcción: la raíz del repositorio (ver README).
#   docker build -f api-gateway/Dockerfile -t api-gateway .

# --- Compilación ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -B -pl api-gateway -am -DskipTests package

# --- Ejecución ---
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
USER app
WORKDIR /app
COPY --from=build /workspace/api-gateway/target/api-gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
