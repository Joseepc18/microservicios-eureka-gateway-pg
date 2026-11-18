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
├── discovery-server/    Eureka Server
├── api-gateway/         Spring Cloud Gateway
├── servicio-productos/  Microservicio de catálogo e inventario
├── servicio-pedidos/    Microservicio de pedidos
├── persistence-routing/ Librería compartida de enrutamiento de datos (sin clase main)
├── pom.xml              POM padre (Java 21, Spring Boot, Spring Cloud)
├── mvnw, mvnw.cmd       Maven Wrapper (no hace falta instalar Maven)
└── docker-compose.yml   Despliegue reproducible
```

## Requisitos

- Docker y Docker Compose.
- Java 21 (solo para compilar los servicios fuera de Docker). Maven no se instala: se usa el wrapper `mvnw`.

## Compilación

Desde la raíz del repositorio:

```bash
./mvnw -q clean verify      # compila todos los módulos y ejecuta sus pruebas
```

En Windows (PowerShell o cmd) se usa `mvnw.cmd` en lugar de `./mvnw`.

Ejecutar un módulo de forma aislada (necesita Java 21):

```bash
./mvnw -pl discovery-server -am spring-boot:run
```

Los módulos ejecutables son `discovery-server`, `api-gateway`, `servicio-productos` y `servicio-pedidos`; `persistence-routing` es solo una librería.

Construir la imagen Docker de un módulo (el contexto es siempre la raíz del repositorio):

```bash
docker build -f discovery-server/Dockerfile -t discovery-server .
```

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
