# Contexto de construcción: la raíz del repositorio (ver README).
#   docker build -f discovery-server/Dockerfile -t discovery-server .

# --- Compilación ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -q -B -pl discovery-server -am -DskipTests package

# --- Ejecución ---
FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
USER app
WORKDIR /app
COPY --from=build /workspace/discovery-server/target/discovery-server-*.jar app.jar
EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
