# Arquitectura

## Componentes

| Componente | Cantidad | Responsabilidad |
|---|---|---|
| `discovery-server` (Eureka) | 1 | Registro y descubrimiento dinámico de servicios |
| `api-gateway` (Spring Cloud Gateway) | 1 | Punto de entrada HTTP y enrutamiento `lb://` |
| `servicio-productos` | 2 instancias | Catálogo e inventario de productos |
| `servicio-pedidos` | 1 instancia | Registro de pedidos; consulta y descuenta stock llamando a `servicio-productos` |
| PostgreSQL primario | 1 | Recibe todas las escrituras |
| PostgreSQL réplicas | 2 | Atienden las lecturas ordinarias |

Eureka administra el descubrimiento de microservicios. No administra las réplicas PostgreSQL: el balanceo HTTP y la selección de conexiones a la base de datos son responsabilidades diferentes.

## Decisiones de diseño

1. **Dominio:** pedidos y productos. `servicio-pedidos` necesita validar y descontar stock de `servicio-productos`, lo que genera una comunicación real entre servicios por nombre lógico.
2. **Base de datos por servicio:** un único clúster PostgreSQL (1 primario + 2 réplicas) con dos bases lógicas, `productos_db` y `pedidos_db`. Ningún servicio consulta las tablas del otro.
3. **Enrutamiento de persistencia:** módulo común con `AbstractRoutingDataSource`. Escrituras al primario; lecturas ordinarias repartidas entre réplicas saludables; lecturas críticas posteriores a una escritura dirigidas al primario.
4. **Sin failover automático** del primario ni transacciones distribuidas (fuera del alcance de la actividad).
5. **Stack:** Java 21, Spring Boot 3.x, Spring Cloud, Maven multi-módulo.

## Módulos, nombres lógicos y versiones

Proyecto Maven multi-módulo (`pom.xml` en la raíz). Los nombres lógicos de Eureka coinciden con `spring.application.name` y no deben cambiarse, porque las rutas `lb://` dependen de ellos.

| Módulo Maven | `spring.application.name` | Tipo |
|---|---|---|
| `discovery-server` | `discovery-server` | Aplicación (Eureka Server) |
| `api-gateway` | `api-gateway` | Aplicación (Gateway) |
| `servicio-productos` | `servicio-productos` | Aplicación |
| `servicio-pedidos` | `servicio-pedidos` | Aplicación |
| `persistence-routing` | — | Librería compartida (sin `main`) |

Versiones: Java 21, Spring Boot 3.5.16 y Spring Cloud 2025.0.3 (`2025.0.x` es el release train compatible con Spring Boot 3.5.x; la serie `2025.1.x` corresponde a Spring Boot 4). Paquete base: `ec.edu.upse.distribuidas`.

## Eureka: configuración y tiempos de detección

`discovery-server` corre en el puerto `8761` como servidor único (`register-with-eureka=false`, `fetch-registry=false`). Dashboard: `http://localhost:8761`. Registro en JSON: `http://localhost:8761/eureka/apps` (con el header `Accept: application/json`). Salud: `/actuator/health`.

Tiempos ajustados para que una caída sea visible durante la demo (los valores por defecto de Eureka son mucho mayores):

| Parámetro | Defecto | Valor |
|---|---|---|
| Latido del cliente (`lease-renewal-interval-in-seconds`) | 30 s | 5 s |
| Expiración de la instancia (`lease-expiration-duration-in-seconds`) | 90 s | 10 s |
| Evicción en el servidor (`eviction-interval-timer-in-ms`) | 60 s | 5 s |
| Caché de respuesta del servidor (`response-cache-update-interval-ms`) | 30 s | 3 s |
| Consulta del registro por el cliente (`registry-fetch-interval-seconds`) | 30 s | 5 s |
| Caché de instancias del LoadBalancer (`spring.cloud.loadbalancer.cache.ttl`) | 35 s | 5 s (gateway y pedidos) |

La autopreservación (`enable-self-preservation`) está desactivada: con pocas instancias, Eureka dejaría de expulsar las caídas. Es una decisión de laboratorio; en producción se mantendría activada.

**Medición:** al terminar de forma abrupta una instancia de `servicio-productos` (sin darse de baja), Eureka la expulsó del registro a los ~24 s. Durante esa ventana, el Gateway puede seguir enviando solicitudes a la instancia caída; por eso se esperan errores transitorios (se tratan en P03). Una parada ordenada (`docker compose stop`) sí se da de baja de inmediato.

## Gateway: rutas y balanceo

`api-gateway` (puerto `8080`) es cliente de Eureka y el único punto de entrada. Las rutas usan nombres lógicos resueltos por Spring Cloud LoadBalancer (round-robin); no hay URLs fijas.

| Ruta externa | Destino | Se envía al servicio como |
|---|---|---|
| `/api/productos/**` | `lb://servicio-productos` | `/productos/**` |
| `/api/pedidos/**` | `lb://servicio-pedidos` | `/pedidos/**` |

- **Reintentos:** solo `GET` (idempotente), hasta 2 reintentos ante `IOException`, timeout o respuestas 5xx, con espera creciente (50–500 ms). Los `POST`, `PUT` y `PATCH` no se reintentan para no duplicar escrituras. Mientras Eureka no expulsa una instancia caída, el reintento hace que el `GET` pase a la otra instancia.
- **Timeouts:** conexión 2 s, respuesta 10 s.
- **Log por solicitud** (`RequestLoggingFilter`): `GW method=GET path=/api/productos/ping route=servicio-productos target=host:8082 instanceId=productos-2 status=200 ms=12`. `target` es la instancia elegida por el LoadBalancer; si el servicio responde con el header `X-Instance-Id`, aparece en `instanceId`. Es la base de la evidencia de P02 y P03. En un reintento se registra la solicitud una sola vez, con el destino del último intento.
- **Actuator:** `/actuator/health` y `/actuator/gateway/routes`.

**Verificación manual (procesos locales):** con dos instancias de `servicio-productos` (8081 y 8082), 10 solicitudes alternaron entre ambas. Al terminar de golpe la instancia 8081, las 10 solicitudes siguientes se atendieron por 8082 sin error de Gateway y, pasado el tiempo de expulsión, todas fueron a 8082.

## Puertos

| Servicio | Puerto en el host |
|---|---|
| Eureka | 8761 |
| Gateway | 8080 |
| servicio-productos (instancia 1) | 8081 |
| servicio-productos (instancia 2) | 8082 |
| servicio-pedidos | 8083 |
| PostgreSQL primario | 5432 |
| PostgreSQL réplica 1 | 5433 |
| PostgreSQL réplica 2 | 5434 |

## Diagrama

_Pendiente: se agregará en esta carpeta (`docs/diagrama-arquitectura.*`) y se referenciará desde el README._

## Flujo de una operación de negocio

_Pendiente: se documentará con la secuencia completa de una creación de pedido (Gateway → pedidos → productos → PostgreSQL)._
