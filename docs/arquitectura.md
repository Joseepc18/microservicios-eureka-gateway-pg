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
