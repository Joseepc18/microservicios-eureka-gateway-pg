# Microservicios con descubrimiento dinámico, balanceo de carga y persistencia replicada

Proyecto de la asignatura **Aplicaciones Distribuidas** (Universidad Estatal Península de Santa Elena).
Arquitectura distribuida basada en Spring Boot, Eureka, Spring Cloud Gateway y PostgreSQL con un nodo primario y dos réplicas de lectura, desplegada con Docker Compose.

> **Estado:** en construcción. Las secciones marcadas con *(pendiente)* se completan al cerrar las issues correspondientes. El plan de trabajo está en [docs/plan-de-trabajo.md](docs/plan-de-trabajo.md).

## Equipo

| Integrante | Responsabilidad principal |
|---|---|
| Joseepc18 | Descubrimiento, Gateway e integración con Docker Compose |
| Integrante 2 | Persistencia replicada y enrutamiento de lecturas/escrituras |
| Integrante 3 | Microservicios de negocio y comunicación entre servicios |

## Arquitectura

```
Cliente HTTP
     ↓
Spring Cloud Gateway (:8080)
     ↓  descubrimiento mediante Eureka (:8761)
servicio-pedidos  ⇄  servicio-productos (2 instancias)
     ↓
Enrutamiento de persistencia (AbstractRoutingDataSource)
     ↓
PostgreSQL Primary (WRITE)
     ↘ replicación WAL
Réplica 1 / Réplica 2 (READ)
```

Detalles, decisiones y tabla de puertos en [docs/arquitectura.md](docs/arquitectura.md). *(diagrama final pendiente)*

## Estructura del repositorio

```
.
├── database/            Scripts de inicialización del primario y las réplicas
├── docs/                Arquitectura, plan de trabajo, informe de pruebas y material de referencia
├── requests/            Colección de solicitudes HTTP (pendiente)
├── discovery-server/    Eureka Server (pendiente)
├── api-gateway/         Spring Cloud Gateway (pendiente)
├── servicio-productos/  Microservicio de catálogo e inventario (pendiente)
├── servicio-pedidos/    Microservicio de pedidos (pendiente)
└── docker-compose.yml   Despliegue reproducible
```

## Requisitos

- Docker y Docker Compose.
- Java 21 (solo para compilar fuera de Docker). No hace falta instalar Maven: el repositorio incluye Maven Wrapper (`mvnw`).

## Inicio rápido: capa de base de datos

```bash
cp .env.example .env        # ajustar las contraseñas
docker compose up -d
docker compose ps
```

Debe verse `primary`, `replica1` y `replica2` en estado *running*. Las pruebas manuales de replicación están en [docs/referencia/pg-pruebas-replicacion.md](docs/referencia/pg-pruebas-replicacion.md).

Detener sin borrar datos:

```bash
docker compose down
```

Reiniciar el laboratorio desde cero (elimina los volúmenes):

```bash
docker compose down -v
```

## Compilación

Desde la raíz del repositorio (Linux, macOS o Git Bash):

```bash
./mvnw clean verify
```

En PowerShell o CMD: `.\mvnw.cmd clean verify`. Compila los cinco módulos y ejecuta sus pruebas. La primera vez descarga Maven automáticamente.

Compilar o ejecutar un solo módulo:

```bash
./mvnw -pl discovery-server -am package
java -jar discovery-server/target/discovery-server-0.1.0-SNAPSHOT.jar
```

Construir la imagen de un módulo (el contexto de build es la raíz):

```bash
docker build -f discovery-server/Dockerfile -t discovery-server .
```

## Ejecución del sistema completo *(pendiente)*

Se documentará cuando se integren todos los servicios en `docker-compose.yml`: cómo iniciar, detener, verificar y recuperar cada componente.

## Pruebas experimentales P01–P08 *(pendiente)*

Resultados y evidencias en [docs/informe-pruebas.md](docs/informe-pruebas.md).

## Colección de solicitudes *(pendiente)*

Se ubicará en `requests/`.

## Video demostrativo *(pendiente)*

Enlace público: _por agregar_.

## Material de referencia

- [Pruebas de replicación PostgreSQL](docs/referencia/pg-pruebas-replicacion.md)
- [Replicación y sincronización en PostgreSQL](docs/referencia/replicacion-y-sincronizacion.md)

## Colaboración

Ver [CONTRIBUTING.md](CONTRIBUTING.md).
