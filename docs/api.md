# API REST

Cada operación de este documento tiene su solicitud de ejemplo en la [colección de solicitudes](../requests/README.md) (`requests/`), incluidos los casos de error.

## servicio-productos

Catálogo e inventario. Cada instancia escucha en su puerto (8081 y 8082 en el host); el acceso normal es por el Gateway:

| Desde | Base |
|---|---|
| Gateway (recomendado) | `http://127.0.0.1:8080/api/productos` |
| Instancia 1 (directo) | `http://127.0.0.1:8081/productos` |
| Instancia 2 (directo) | `http://127.0.0.1:8082/productos` |

El Gateway quita el prefijo `/api`: `/api/productos/1` llega al servicio como `/productos/1`.

### Header `X-Instance-Id`

**Todas** las respuestas (éxitos y errores) incluyen `X-Instance-Id` con el valor de `INSTANCE_ID` de la instancia que respondió (`productos-1` o `productos-2` en Compose). Sirve para comprobar el balanceo (P02) y el fallo de una instancia (P03).

```bash
curl -i http://127.0.0.1:8080/api/productos/1    # ver la línea X-Instance-Id
```

### Modelo

```json
{
  "id": 1,
  "nombre": "Laptop 14 pulgadas",
  "descripcion": "Laptop de 14 pulgadas, 16 GB de RAM",
  "precio": 749.90,
  "stock": 25
}
```

| Campo | Regla de validación (en `POST` y `PUT`) |
|---|---|
| `nombre` | obligatorio, no vacío, máximo 120 caracteres |
| `descripcion` | opcional, máximo 500 caracteres |
| `precio` | obligatorio, mayor o igual a 0, hasta 2 decimales |
| `stock` | obligatorio, entero mayor o igual a 0 |

Al iniciar, Flyway crea la tabla `producto` y carga 5 productos de ejemplo.

### Operaciones

| Método y ruta | Descripción | Éxito | Errores |
|---|---|---|---|
| `GET /productos` | Lista todos los productos | `200` | |
| `GET /productos/{id}` | Obtiene un producto | `200` | `404`, `400` (id no numérico) |
| `POST /productos` | Crea un producto | `201` + `Location` | `400` |
| `PUT /productos/{id}` | Reemplaza los datos de un producto | `200` | `400`, `404` |
| `DELETE /productos/{id}` | Elimina un producto | `204` | `404` |
| `PATCH /productos/{id}/stock` | Descuenta stock | `200` (producto actualizado) | `400`, `404`, `409` |
| `PATCH /productos/{id}/stock/reponer` | Repone stock (al cancelar un pedido) | `200` (producto actualizado) | `400`, `404` |

#### `PATCH /productos/{id}/stock`

Operación usada por `servicio-pedidos`. Cuerpo:

```json
{ "cantidad": 3 }
```

`cantidad` es obligatoria y debe ser un entero mayor o igual a 1. El descuento es una sola sentencia atómica (`UPDATE ... WHERE stock >= cantidad`), por lo que dos instancias que descuenten a la vez nunca dejan el stock en negativo. Si el stock es insuficiente responde `409` y no modifica nada.

#### `PATCH /productos/{id}/stock/reponer`

Operación usada por `servicio-pedidos` al cancelar un pedido. Mismo cuerpo que el descuento (`{ "cantidad": 3 }`, entero mayor o igual a 1). Suma la cantidad sobre el valor actual en una sola sentencia atómica (`UPDATE ... SET stock = stock + cantidad`), así que no pisa los descuentos que hagan otras instancias a la vez. Responde `404` si el producto no existe.

### Errores

Todos los errores usan el mismo cuerpo JSON:

```json
{
  "timestamp": "2026-11-18T21:30:00.123Z",
  "status": 409,
  "error": "Conflict",
  "message": "Stock insuficiente para el producto 1 (solicitado: 500)",
  "path": "/productos/1/stock"
}
```

| Código | Cuándo |
|---|---|
| `400` | Validación fallida, JSON malformado o id no numérico. En validación, `message` lista los campos inválidos |
| `404` | El producto o la ruta no existen |
| `405` | Método HTTP no permitido en esa ruta |
| `415` | `Content-Type` no soportado (el cuerpo debe ser `application/json`) |
| `409` | Stock insuficiente |
| `500` | Error inesperado (el detalle solo queda en el log del servicio) |

### Ejemplos

```bash
# Listar
curl http://127.0.0.1:8080/api/productos

# Crear
curl -X POST http://127.0.0.1:8080/api/productos \
  -H "Content-Type: application/json" \
  -d '{"nombre":"Cable HDMI","descripcion":"2 metros","precio":8.50,"stock":30}'

# Actualizar
curl -X PUT http://127.0.0.1:8080/api/productos/1 \
  -H "Content-Type: application/json" \
  -d '{"nombre":"Laptop 14 pulgadas","descripcion":"16 GB de RAM","precio":699.90,"stock":25}'

# Descontar stock
curl -X PATCH http://127.0.0.1:8080/api/productos/1/stock \
  -H "Content-Type: application/json" \
  -d '{"cantidad":3}'

# Eliminar
curl -X DELETE http://127.0.0.1:8080/api/productos/6
```

### Persistencia

Cada instancia usa su propia base, `productos_db`, con el rol `productos_app`, a través del módulo [`persistence-routing`](../persistence-routing/README.md): las escrituras (`POST`, `PUT`, `DELETE` y `PATCH` de stock) van al primario y las lecturas (`GET`, con `@Transactional(readOnly = true)`) se reparten por turnos entre `replica1` y `replica2`. La conexión se configura con `PERSISTENCE_ROUTING_*` en `docker-compose.yml`. Cada decisión queda en el log (`docker compose logs servicio-productos-1 | grep "DB route"`).

Como las réplicas se actualizan con un pequeño retraso, un `GET` inmediatamente después de un `POST` o `PUT` puede devolver todavía el dato anterior (o `404`, si es un producto recién creado). Es el comportamiento esperado de la replicación asíncrona; el `PATCH` de stock no se ve afectado porque descuenta en el primario.

## servicio-pedidos

Registro de pedidos. Para crear uno **llama a `servicio-productos` por su nombre lógico de Eureka** (`http://servicio-productos`, resuelto por Spring Cloud LoadBalancer); no hay URLs fijas entre servicios.

| Desde | Base |
|---|---|
| Gateway (recomendado) | `http://127.0.0.1:8080/api/pedidos` |
| Directo | `http://127.0.0.1:8083/pedidos` |

Igual que productos, todas las respuestas incluyen `X-Instance-Id` y el cuerpo de error es el mismo JSON uniforme (ver arriba).

### Modelo

```json
{
  "id": 1,
  "productoId": 1,
  "cantidad": 2,
  "total": 1499.80,
  "estado": "CREADO",
  "fecha": "2026-11-18T22:30:00.123Z"
}
```

`estado` es `CREADO` o `CANCELADO`. `productoId` es un valor simple: no hay clave foránea ni acceso a las tablas de `servicio-productos`. `total` es `precio × cantidad`, con el precio que devolvió `servicio-productos` al crear el pedido.

### Operaciones

| Método y ruta | Descripción | Éxito | Errores |
|---|---|---|---|
| `POST /pedidos` | Crea un pedido | `201` + `Location` | `400`, `404`, `409`, `503` |
| `GET /pedidos/{id}` | Obtiene un pedido. `?consistencia=fuerte` lo lee del primario (ver Persistencia) | `200` | `404`, `400` |
| `GET /pedidos` | Lista los pedidos | `200` | |
| `PATCH /pedidos/{id}/cancelar` | Cancela un pedido `CREADO` y repone su stock | `200` | `404`, `409`, `503` |

#### `POST /pedidos`

```json
{ "productoId": 1, "cantidad": 2 }
```

`productoId` debe ser positivo y `cantidad` un entero mayor o igual a 1. Pasos:

1. `GET /productos/{id}` en `servicio-productos`: `404` si no existe.
2. Valida el stock: `409` si es menor que la cantidad pedida.
3. `PATCH /productos/{id}/stock`: descuenta el stock. Es la verificación definitiva, porque otra solicitud pudo consumirlo en el intervalo (`409` si ocurre).
4. Guarda el pedido con el total calculado.

| Código | Cuándo |
|---|---|
| `400` | Validación fallida o JSON malformado |
| `404` | El producto no existe (o, en las demás rutas, el pedido) |
| `409` | Stock insuficiente (o intentar cancelar un pedido que no está `CREADO`) |
| `503` | `servicio-productos` no tiene instancias, no responde o supera el timeout. Mensaje: `El servicio de productos no está disponible: <causa>` |

#### `PATCH /pedidos/{id}/cancelar`

Cambia el estado a `CANCELADO` y **repone el stock** del producto en `servicio-productos` (`PATCH /productos/{id}/stock/reponer`). Pasos:

1. Lee el pedido en el primario (lectura crítica: puede haberse creado hace un instante). `404` si no existe.
2. Lo pasa de `CREADO` a `CANCELADO` con una sentencia condicional (`UPDATE ... WHERE estado = 'CREADO'`). Si ya estaba cancelado, o si otra solicitud lo canceló al mismo tiempo, responde `409`: así el stock se repone **una sola vez** aunque lleguen dos cancelaciones a la vez.
3. Repone el stock. Si `servicio-productos` no responde, el pedido **vuelve a `CREADO`** y se responde `503`, para que el cliente reintente la cancelación. Si el producto ya no existe, el pedido queda cancelado igualmente (no hay stock que reponer) y se registra un aviso.

El `PATCH` de reposición no se reintenta, por la misma razón que el descuento.

### Timeouts y reintentos

Las llamadas a `servicio-productos` tienen timeout de conexión de 2 s y de lectura de 5 s (`productos.client.*` en `application.yml`).

- **`GET /productos/{id}`:** si falla por conexión o timeout, el LoadBalancer lo repite **una vez en otra instancia** (`spring.cloud.loadbalancer.retry.*`, con `spring-retry`). Así, mientras Eureka no expulsa una instancia caída (ver [arquitectura](arquitectura.md)), la consulta previa de un pedido pasa a la instancia viva en lugar de responder `503`; solo tarda unos 2 s más (el timeout de conexión). Repetir una consulta no tiene efectos secundarios.
- **`PATCH /productos/{id}/stock`:** **no se reintenta** (`retry-on-all-operations: false`): si el primer intento llegó a descontar y se perdió la respuesta, repetirlo descontaría dos veces. Si cae en la instancia caída, `servicio-pedidos` responde `503` sin crear el pedido y el cliente debe reintentarlo.
- **Sin instancias disponibles** (las dos detenidas): el `GET` prueba las dos y responde `503`.

### Trazabilidad con `X-Request-Id`

Cada solicitud tiene un identificador de correlación. Si el cliente envía `X-Request-Id` (solo letras, números, `.`, `_` y `-`, hasta 64 caracteres) se conserva; si no, se genera un UUID. Se devuelve en la respuesta, aparece entre corchetes en cada línea de log (`INFO [3f2a...] ...`) y `servicio-pedidos` lo reenvía a `servicio-productos`, que lo registra igual. Así una solicitud (por ejemplo de P04) se sigue en ambos logs:

```bash
curl -i -X POST http://127.0.0.1:8080/api/pedidos -H "X-Request-Id: prueba-p04-1"   -H "Content-Type: application/json" -d '{"productoId":1,"cantidad":2}'
docker compose logs servicio-pedidos servicio-productos-1 servicio-productos-2 | grep prueba-p04-1
```

### Limitación: sin transacciones distribuidas

No hay transacciones distribuidas ni Sagas. El stock se descuenta en `servicio-productos` **antes** de guardar el pedido en `pedidos_db`; son dos bases distintas y no se pueden confirmar juntas. Comportamiento esperado:

- **Falla antes del descuento** (producto inexistente, stock insuficiente, servicio caído): no se modifica nada. Es el caso normal.
- **Falla el guardado después de descontar:** el stock queda descontado y no existe el pedido. El servicio registra un `ERROR` con el `productoId`, la cantidad y el `X-Request-Id`, y responde `500`. La corrección es manual (con `PATCH /productos/{id}/stock/reponer`, que suma sin pisar otros cambios).
- **Timeout en el descuento:** el resultado es incierto, porque `servicio-productos` pudo descontar aunque la respuesta no llegara. Se responde `503` y no se crea el pedido; el stock podría haber quedado descontado.
- **Timeout al reponer en una cancelación:** el resultado también es incierto: `servicio-productos` pudo reponer aunque la respuesta no llegara. El pedido vuelve a `CREADO` y se responde `503`; si el cliente reintenta la cancelación, el stock podría reponerse dos veces.

### Persistencia

`servicio-pedidos` usa su propia base, `pedidos_db`, con el rol `pedidos_app`, a través del módulo [`persistence-routing`](../persistence-routing/README.md): las escrituras (`@Transactional`) van al primario y las lecturas ordinarias (`@Transactional(readOnly = true)`: `GET /pedidos` y `GET /pedidos/{id}`) se reparten por turnos entre `replica1` y `replica2`. La conexión se configura con `PERSISTENCE_ROUTING_*` en `docker-compose.yml`. Cada decisión queda en el log (`docker compose logs servicio-pedidos | grep "DB route"`).

**Lectura ordinaria y lectura crítica de un pedido.** `GET /pedidos/{id}` tiene dos modos, que elige el cliente con el parámetro `consistencia`:

| Solicitud | Tipo | Nodo | Cuándo usarla |
|---|---|---|---|
| `GET /pedidos/{id}` (o `?consistencia=eventual`) | Ordinaria | Una réplica, por turnos | Consultas normales. Acepta consistencia eventual: un pedido creado o cancelado hace un instante podría no verse todavía (`404` o estado anterior) |
| `GET /pedidos/{id}?consistencia=fuerte` | Crítica (`@ReadFromPrimary`) | El primario | Leer lo que el mismo cliente acaba de escribir, por ejemplo justo después de `POST /pedidos` |

Cualquier otro valor de `consistencia` responde `400`. La replicación es asíncrona y las réplicas se actualizan con un pequeño retraso (acotado por `max-lag`, 10 s), así que la lectura crítica es la que garantiza ver la escritura anterior. El log distingue las dos: `DB route=REPLICA node=replica1 tx=read-only` y `DB route=PRIMARY node=primary tx=read-only read=critical`.
