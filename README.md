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
├── persistence-routing/ Librería compartida de enrutamiento de lecturas y escrituras
├── discovery-server/    Eureka Server
├── api-gateway/         Spring Cloud Gateway
├── servicio-productos/  Microservicio de catálogo e inventario
├── servicio-pedidos/    Microservicio de pedidos
├── pom.xml              POM padre (Maven multi-módulo); mvnw / mvnw.cmd: Maven Wrapper
└── docker-compose.yml   Despliegue reproducible
```

## Requisitos

- Docker y Docker Compose.
- Java 21 (solo para compilar fuera de Docker). No hace falta instalar Maven: el repositorio incluye Maven Wrapper (`mvnw`).

## Inicio rápido: capa de base de datos

```bash
cp .env.example .env        # ajustar las contraseñas
docker compose up -d primary replica1 replica2   # solo la base de datos
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
java -jar discovery-server/target/discovery-server-*.jar
```

Construir la imagen de un módulo (el contexto de build es la raíz):

```bash
docker build -f discovery-server/Dockerfile -t discovery-server .
```

## Ejecución del sistema completo

`docker-compose.yml` levanta todo el sistema: Eureka, Gateway, dos instancias de `servicio-productos`, `servicio-pedidos` y la capa PostgreSQL. Hay dos redes: `red_app` (HTTP entre Gateway, Eureka y servicios) y `red_datos` (PostgreSQL). El Gateway solo está en `red_app`; los servicios de negocio están en ambas; la base de datos solo en `red_datos`.

> **Windows con Docker Desktop:** en los ejemplos se usa `127.0.0.1` y no `localhost`. En algunos equipos `localhost` resuelve primero a IPv6 (`::1`) y la solicitud se queda colgada.

### Iniciar

```bash
cp .env.example .env              # primera vez; ajustar contraseñas y puertos si hace falta
docker compose up -d --build --wait
```

`--wait` devuelve el control cuando todos los servicios están saludables (la primera construcción de imágenes tarda varios minutos). El orden de arranque lo controlan los healthchecks: Eureka arranca primero; el Gateway y los servicios de negocio esperan a que esté saludable. Los puertos del host y los identificadores de instancia se configuran en `.env`.

| Servicio | URL en el host |
|---|---|
| Eureka (dashboard) | http://127.0.0.1:8761 |
| Gateway | http://127.0.0.1:8080 |
| servicio-productos (instancia 1) | http://127.0.0.1:8081 |
| servicio-productos (instancia 2) | http://127.0.0.1:8082 |
| servicio-pedidos | http://127.0.0.1:8083 |

Hasta que se implementen los servicios de negocio, `servicio-productos` y `servicio-pedidos` solo arrancan y se registran en Eureka (no exponen endpoints propios).

### Verificar

```bash
docker compose ps                                    # todos deben figurar como (healthy)
curl http://127.0.0.1:8080/actuator/health           # salud del Gateway: {"status":"UP",...}
curl http://127.0.0.1:8080/actuator/gateway/routes   # rutas lb:// cargadas
curl -H "Accept: application/json" http://127.0.0.1:8761/eureka/apps   # registro en JSON
```

En el dashboard de Eureka (http://127.0.0.1:8761) deben aparecer `API-GATEWAY`, `SERVICIO-PEDIDOS` y dos instancias de `SERVICIO-PRODUCTOS`, con instanceId `servicio-productos:productos-1` y `servicio-productos:productos-2`. Cada solicitud que pasa por el Gateway queda registrada con la instancia destino:

```bash
docker compose logs -f api-gateway    # líneas "GW method=GET ... target=servicio-productos-1:8081 ..."
```

### Detener y recuperar componentes

```bash
docker compose stop servicio-productos-2     # parada ordenada: Eureka la marca DOWN y la retira en ~10 s
docker compose start servicio-productos-2    # vuelve a registrarse sola en Eureka (~20-30 s)
docker compose logs servicio-productos-2     # diagnóstico de una instancia
docker compose restart servicio-pedidos      # reiniciar un servicio
```

Mientras una instancia está detenida, el Gateway reparte las solicitudes entre las instancias restantes.

### Detener todo

```bash
docker compose down        # detiene y elimina contenedores; conserva los datos de PostgreSQL
docker compose down -v     # además elimina los volúmenes (reinicio desde cero)
```

Si un puerto del host ya está en uso (`ports are not available`), cambiar el valor correspondiente en `.env` (por ejemplo `GATEWAY_PORT=18080`); el puerto interno de cada contenedor no cambia.

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
