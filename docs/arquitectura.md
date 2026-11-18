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

La autopreservación (`enable-self-preservation`) está desactivada. Cuando está activa, Eureka suspende la expulsión de instancias si las renovaciones caen por debajo de un umbral (por ejemplo, si cae a la vez un grupo grande de instancias). Se desactiva solo en este laboratorio para que el registro no conserve instancias muertas durante las demos; en producción se mantendría activada. Con una sola instancia caída la expulsión ocurre igualmente a los ~24 s, tanto con la autopreservación activa como desactivada (comprobado en la revisión).

**Medición:** al terminar de forma abrupta una instancia de `servicio-productos` (sin darse de baja), Eureka la expulsó del registro a los ~24 s. Una parada ordenada (`docker compose stop`) sí se da de baja de inmediato.

**Por qué ~24 s y no 10 s:** la expiración configurada es de 10 s, pero Eureka la aplica con un margen adicional (en la práctica cerca del doble, ~20 s) y después espera al siguiente ciclo de evicción (hasta 5 s) y a la caché de respuesta (3 s). Ese margen es un comportamiento conocido de Eureka que no se verificó leyendo su código; el valor de ~24 s es el medido.

**Ventana total hasta que el Gateway deja de enviar a la instancia caída** (peor caso): ~24 s del servidor + hasta 5 s de consulta del registro por el cliente + hasta 5 s de la caché del LoadBalancer, es decir, **~35 s**. Durante esa ventana el Gateway puede seguir enviando solicitudes a la instancia caída; por eso se esperan errores transitorios, que se analizan en P03.

## Gateway: rutas y balanceo

`api-gateway` (puerto `8080`) es cliente de Eureka y el único punto de entrada. Las rutas usan nombres lógicos resueltos por Spring Cloud LoadBalancer (round-robin); no hay URLs fijas.

| Ruta externa | Destino | Se envía al servicio como |
|---|---|---|
| `/api/productos/**` | `lb://servicio-productos` | `/productos/**` |
| `/api/pedidos/**` | `lb://servicio-pedidos` | `/pedidos/**` |

- **Reintentos:** solo `GET` (idempotente), hasta 2 reintentos ante `IOException`, timeout o respuestas `502`, `503` y `504`, con espera creciente (50–500 ms). Un `500` de la aplicación no se reintenta, para no repetir un `GET` que falla por un error del servicio. Los `POST`, `PUT` y `PATCH` no se reintentan para no duplicar escrituras. Mientras Eureka no expulsa una instancia caída, el reintento hace que el `GET` pase a la otra instancia.
- **Errores transitorios esperados (P03):** durante la ventana de detección de una instancia caída (~35 s en el peor caso, ver la sección de Eureka), los `GET` se recuperan por reintento, pero las escrituras (`POST`, `PUT`, `PATCH`) que el LoadBalancer dirija a la instancia caída fallan (se observó `500` alternado) y el cliente debe reintentarlas. Si no queda ninguna instancia registrada, el Gateway responde `503`.
- **Un `Retry` por ruta se suma al global:** el filtro `Retry` está en `default-filters`. Si se agrega otro `Retry` dentro de una ruta, no reemplaza al global, se acumula.
- **Timeouts:** conexión 2 s, respuesta 10 s.
- **Log por solicitud** (`RequestLoggingFilter`): `GW method=GET path=/api/productos/ping route=servicio-productos target=host:8082 instanceId=productos-2 status=200 ms=12`. `target` es la instancia elegida por el LoadBalancer; si el servicio responde con el header `X-Instance-Id`, aparece en `instanceId`. Es la base de la evidencia de P02 y P03. En un reintento se registra la solicitud una sola vez, con el destino del último intento.
- **Actuator:** `/actuator/health` y, en solo lectura, `/actuator/gateway/routes`, `/actuator/gateway/globalfilters` y `/actuator/gateway/routefilters`. El endpoint `gateway` llega con acceso `NONE` por defecto en Spring Boot 3.4+; se habilita con `management.endpoint.gateway.access: read-only` (no `unrestricted`, que permitiría crear y borrar rutas sin autenticación).
- **Intentos fallidos:** el log registra solo la línea del último intento; los intentos fallidos de un reintento no aparecen en el log del Gateway.

**Verificación manual (procesos locales):** con dos instancias de `servicio-productos` (8081 y 8082), 10 solicitudes alternaron entre ambas. Al terminar de golpe la instancia 8081, las 10 solicitudes siguientes se atendieron por 8082 sin error de Gateway y, pasado el tiempo de expulsión, todas fueron a 8082.

## Base de datos: bases, roles y replicación

Un único clúster PostgreSQL 17: el primario (`primary`) y dos réplicas físicas (`replica1` y `replica2`) con streaming WAL asíncrono. Todo lo que se describe aquí lo crea [`database/primary-init.sh`](../database/primary-init.sh) al inicializar el primario y llega a las réplicas por WAL, sin pasos manuales.

### Bases y roles

| Base | Rol (dueño) | Usado por | Variable con la clave (`.env`) |
|---|---|---|---|
| `productos_db` | `productos_app` | `servicio-productos` | `PRODUCTOS_DB_PASSWORD` |
| `pedidos_db` | `pedidos_app` | `servicio-pedidos` | `PEDIDOS_DB_PASSWORD` |
| `laboratorio` | `admin` (superusuario, `POSTGRES_USER`) | Administración y pruebas manuales | `POSTGRES_PASSWORD` |
| — | `replicator` (solo `REPLICATION`) | Réplicas (`pg_basebackup` y streaming) | `REPLICATION_PASSWORD` |

- Cada rol de servicio es **dueño de su base**: puede crear y modificar sus tablas (por ejemplo con JPA o Flyway) sin permisos extra.
- **Aislamiento:** se revocó a `PUBLIC` el permiso `CONNECT` sobre `productos_db`, `pedidos_db`, `postgres`, `laboratorio` y `template1` (`template0` no admite conexiones). Así `productos_app` solo puede conectarse a `productos_db`, y `pedidos_app` solo a `pedidos_db`. Cualquier otro intento falla con `permission denied for database ... User does not have CONNECT privilege`, tanto en el primario como en las réplicas, porque los permisos también se replican.
- Los nombres de bases y roles son fijos. Solo las claves salen del `.env` (ver `.env.example`).

### Datos de conexión para los servicios

Los servicios de negocio están en la red `red_datos` y llegan a PostgreSQL por el nombre del servicio de Compose, siempre en el puerto interno `5432`:

| Nodo | Uso | URL JDBC (ejemplo con `productos_db`) |
|---|---|---|
| `primary` | Escrituras y lecturas críticas | `jdbc:postgresql://primary:5432/productos_db` |
| `replica1` | Lecturas ordinarias | `jdbc:postgresql://replica1:5432/productos_db` |
| `replica2` | Lecturas ordinarias | `jdbc:postgresql://replica2:5432/productos_db` |

Usuario: `productos_app` (o `pedidos_app` con `pedidos_db`). Clave: `${PRODUCTOS_DB_PASSWORD}` (o `${PEDIDOS_DB_PASSWORD}`). El módulo `persistence-routing` recibe estos datos con las propiedades `persistence.routing.*` (o sus variables de entorno `PERSISTENCE_ROUTING_*`), documentadas en su [README](../persistence-routing/README.md).

Desde el host se puede entrar a cada nodo por los puertos `5432`, `5433` y `5434` (ver [Puertos](#puertos)).

### Retención de WAL: slots de replicación

Cuando una réplica se detiene, el primario tiene que conservar el WAL que esa réplica no recibió. Si no lo hace, la réplica no puede ponerse al día al volver (P08). Se evaluaron dos opciones:

| | `wal_keep_size` (configuración anterior: 256 MB) | Slots de replicación (elegida) |
|---|---|---|
| Qué retiene | Un tamaño fijo de WAL, esté o no caída alguna réplica | Exactamente el WAL que cada réplica aún no confirmó |
| Réplica caída mucho tiempo | Si se generan más de 256 MB, el WAL se borra y la réplica **no puede recuperarse** (`requested WAL segment ... has already been removed`) | Se recupera sola mientras no se supere el tope |
| Riesgo | Pérdida de la réplica | **Acumulación de WAL** en el disco del primario mientras la réplica está caída |

**Decisión:** un slot físico por réplica (`replica1` y `replica2`), creado en `primary-init.sh` con `immediately_reserve = true` para que retenga WAL desde el primer momento. Cada réplica lo usa con `pg_basebackup -S`, y `-R` deja `primary_slot_name` en su configuración. El riesgo de acumulación se acota con **`max_slot_wal_keep_size = 1GB`**: si una réplica caída deja retenido más de 1 GB, PostgreSQL invalida su slot y libera el WAL, protegiendo el disco del primario a costa de esa réplica.

Estado de los slots (cuánto WAL retiene cada uno y si sigue siendo válido):

```bash
docker compose exec primary psql -U admin -d laboratorio \
  -c "SELECT slot_name, active, wal_status, safe_wal_size, restart_lsn FROM pg_replication_slots;"
```

`wal_status` es `reserved` en condiciones normales, `extended` o `unreserved` si se acerca al tope, y `lost` si el slot se invalidó. Una réplica con el slot en `lost` no puede ponerse al día y hay que reconstruirla desde una copia nueva. Se reconstruye así (borra solo el volumen de esa réplica; el slot sigue existiendo en el primario):

```bash
docker compose rm -sf replica1
docker volume rm microservicios-eureka-gateway-pg_replica1_data
docker compose exec primary psql -U admin -d laboratorio \
  -c "SELECT pg_drop_replication_slot('replica1'); SELECT pg_create_physical_replication_slot('replica1', true);"
docker compose up -d replica1
```

(El prefijo del volumen es el nombre del proyecto de Compose; `docker volume ls` lo muestra.)

Cada réplica se conecta con `application_name` igual a su slot, así que `pg_stat_replication` muestra `replica1` y `replica2` en lugar de `walreceiver`.

### Healthchecks

| Nodo | Se considera saludable cuando |
|---|---|
| `primary` | `pg_isready` responde |
| `replica1`, `replica2` | `pg_isready` responde **y** `pg_is_in_recovery()` devuelve `true`, es decir, sigue siendo standby y no fue promovida |

El healthcheck de las réplicas usa el socket local con el usuario `POSTGRES_USER`, que la imagen acepta sin clave dentro del contenedor. Por eso las réplicas reciben `POSTGRES_USER` y `POSTGRES_DB` en Compose; no inicializan nada con ellas.

### Inicialización y cambios

`primary-init.sh` solo se ejecuta cuando el volumen del primario está vacío. Si ya existía un laboratorio creado antes de este esquema, hay que recrearlo para obtener las bases, los roles y los slots:

```bash
docker compose down -v
docker compose up -d
```

## Puertos

| Servicio | Puerto en el host |
|---|---|
| Eureka | 8761 |
| Gateway | 8080 |
| servicio-productos (instancia 1) | 8081 |
| servicio-productos (instancia 2) | 8082 |
| servicio-pedidos | 8083 |
| PostgreSQL primario | 5432 (interno `primary:5432`) |
| PostgreSQL réplica 1 | 5433 (interno `replica1:5432`) |
| PostgreSQL réplica 2 | 5434 (interno `replica2:5432`) |

## Diagrama

_Pendiente: se agregará en esta carpeta (`docs/diagrama-arquitectura.*`) y se referenciará desde el README._

## Flujo de una operación de negocio

_Pendiente: se documentará con la secuencia completa de una creación de pedido (Gateway → pedidos → productos → PostgreSQL)._
