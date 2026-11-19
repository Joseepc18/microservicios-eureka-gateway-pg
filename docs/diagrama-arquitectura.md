# Diagrama de arquitectura

Los diagramas están en [Mermaid](https://mermaid.js.org/), que GitHub dibuja directamente. Describen lo que levanta `docker-compose.yml`. El texto de cada decisión está en [arquitectura.md](arquitectura.md).

## Componentes, puertos y redes

```mermaid
flowchart LR
    cliente["Cliente HTTP<br/>curl, navegador o requests/"]

    subgraph red_app["red_app"]
        gw["api-gateway<br/>Spring Cloud Gateway<br/>host 8080"]
        eureka["discovery-server<br/>Eureka<br/>host 8761"]
    end

    subgraph negocio["Servicios de negocio<br/>(red_app y red_datos)"]
        prod1["servicio-productos<br/>instancia 1: productos-1<br/>host 8081"]
        prod2["servicio-productos<br/>instancia 2: productos-2<br/>host 8082"]
        ped["servicio-pedidos<br/>pedidos-1<br/>host 8083"]
    end

    subgraph red_datos["red_datos: PostgreSQL 17"]
        primary[("primary<br/>escrituras<br/>host 5432")]
        replica1[("replica1<br/>solo lectura<br/>host 5433")]
        replica2[("replica2<br/>solo lectura<br/>host 5434")]
    end

    cliente -->|"HTTP /api/**"| gw
    gw -->|"lb://servicio-productos"| prod1
    gw -->|"lb://servicio-productos"| prod2
    gw -->|"lb://servicio-pedidos"| ped
    ped -->|"por nombre lógico"| prod1
    ped -->|"por nombre lógico"| prod2

    gw -.->|"registro y consulta"| eureka
    negocio -.->|"registro, latido y consulta"| eureka

    negocio ==>|"escrituras y lecturas críticas"| primary
    negocio -->|"lecturas ordinarias, por turnos"| replica1
    negocio -->|"lecturas ordinarias, por turnos"| replica2
    primary -->|"replicación WAL asíncrona"| replica1
    primary -->|"replicación WAL asíncrona"| replica2
```

Cómo leerlo:

- **Líneas punteadas:** descubrimiento. Cada aplicación se registra en Eureka y envía un latido cada 5 s. El Gateway y `servicio-pedidos` copian el registro cada 5 s y eligen la instancia con su copia local, por lo que Eureka no está en el camino de cada solicitud.
- **Líneas gruesas:** escrituras y lecturas críticas, que van al primario.
- **Líneas finas hacia las réplicas:** lecturas ordinarias (`@Transactional(readOnly = true)`), repartidas por turnos entre `replica1` y `replica2`. Si ninguna réplica está apta, el módulo las degrada al primario.
- **Redes:** el Gateway y Eureka solo están en `red_app`; PostgreSQL solo en `red_datos`; los tres servicios de negocio están en las dos. Por eso el Gateway no puede llegar a la base de datos.
- **Puertos:** los números "host" son los publicados en la máquina del usuario (los de las aplicaciones se cambian en `.env`; los de PostgreSQL están fijos en `docker-compose.yml`). Dentro de Compose, cada contenedor usa su puerto interno (el mismo número en las aplicaciones y `5432` en los tres nodos de PostgreSQL).

| Componente | Nombre en Compose | Puerto en el host | Puerto interno |
|---|---|---|---|
| Eureka | `discovery-server` | 8761 (`EUREKA_PORT`) | 8761 |
| Gateway | `api-gateway` | 8080 (`GATEWAY_PORT`) | 8080 |
| servicio-productos, instancia 1 | `servicio-productos-1` | 8081 (`PRODUCTOS_1_PORT`) | 8081 |
| servicio-productos, instancia 2 | `servicio-productos-2` | 8082 (`PRODUCTOS_2_PORT`) | 8082 |
| servicio-pedidos | `servicio-pedidos` | 8083 (`PEDIDOS_PORT`) | 8083 |
| PostgreSQL primario | `primary` | 5432 | 5432 |
| PostgreSQL réplica 1 | `replica1` | 5433 | 5432 |
| PostgreSQL réplica 2 | `replica2` | 5434 | 5432 |

## Enrutamiento de persistencia

Lo decide el módulo [`persistence-routing`](../persistence-routing/README.md) cuando la transacción pide su conexión. Los servicios solo anotan sus métodos.

```mermaid
flowchart LR
    tx["Método de servicio"] --> tipo{"Tipo de transacción"}
    tipo -->|"@Transactional<br/>escritura"| primario["primary"]
    tipo -->|"sin transacción"| primario
    tipo -->|"@Transactional(readOnly = true)<br/>con @ReadFromPrimary"| primario
    tipo -->|"@Transactional(readOnly = true)<br/>lectura ordinaria"| aptas{"¿Hay réplicas aptas?"}
    aptas -->|"sí: round-robin"| replicas["replica1 o replica2"]
    aptas -->|"no: lectura degradada"| primario
```

Una réplica está apta si responde, sigue en modo recuperación (no fue promovida) y su retraso no supera 10 s. El único método con `@ReadFromPrimary` es `GET /pedidos/{id}` de `servicio-pedidos`, porque el cliente lo consulta justo después de crear el pedido.

## Secuencia: crear un pedido

`POST /api/pedidos` es la operación que cruza todos los componentes. Al final, el cliente consulta el pedido recién creado.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant GW as api-gateway
    participant PE as servicio-pedidos
    participant PR as servicio-productos (instancia 1 o 2)
    participant DBR as replica1 o replica2
    participant DBP as primary

    Note over GW,PE: El Gateway y servicio-pedidos eligen la instancia destino con su copia del registro de Eureka. Eureka no interviene en cada solicitud.

    C->>GW: POST /api/pedidos con productoId y cantidad
    GW->>PE: POST /pedidos (lb://servicio-pedidos, sin el prefijo /api)
    PE->>PR: GET /productos/{id} (por nombre lógico servicio-productos)
    PR->>DBR: SELECT en productos_db (lectura ordinaria)
    DBR-->>PR: producto
    PR-->>PE: 200 con precio y stock

    alt el producto no existe o el stock no alcanza
        PE-->>GW: 404 o 409, sin cambios en ninguna base
        GW-->>C: 404 o 409
    else hay stock
        PE->>PR: PATCH /productos/{id}/stock con la cantidad
        PR->>DBP: UPDATE ... WHERE stock >= cantidad (escritura)
        PR-->>PE: 200 (409 si otra solicitud consumió el stock)
        PE->>DBP: INSERT en pedidos_db (escritura)
        DBP-->>PE: pedido con id
        PE-->>GW: 201 con Location
        GW-->>C: 201 con el pedido en estado CREADO
    end

    Note over C,DBP: Consulta inmediata del pedido recién creado
    C->>GW: GET /api/pedidos/{id}
    GW->>PE: GET /pedidos/{id}
    PE->>DBP: SELECT en pedidos_db (lectura crítica, @ReadFromPrimary)
    DBP-->>PE: pedido
    PE-->>GW: 200
    GW-->>C: 200 con el pedido
```

Puntos que conviene recordar:

- `servicio-pedidos` no abre una transacción durante las llamadas HTTP: la base de datos solo interviene al guardar el pedido.
- No hay transacciones distribuidas. El stock se descuenta antes de guardar el pedido; si el guardado falla, queda una inconsistencia que se corrige a mano (ver [api.md](api.md#limitación-sin-transacciones-distribuidas)).
- Las llamadas de `servicio-pedidos` a `servicio-productos` no se reintentan (un `PATCH` repetido podría descontar dos veces). Sin instancias disponibles, responde `503`.
- La consulta final va al primario porque una réplica con retraso podría no tener todavía el pedido (comprobado en P07, ver [informe-pruebas.md](informe-pruebas.md)).
