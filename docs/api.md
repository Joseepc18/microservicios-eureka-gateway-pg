# API REST

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

#### `PATCH /productos/{id}/stock`

Operación usada por `servicio-pedidos`. Cuerpo:

```json
{ "cantidad": 3 }
```

`cantidad` es obligatoria y debe ser un entero mayor o igual a 1. El descuento es una sola sentencia atómica (`UPDATE ... WHERE stock >= cantidad`), por lo que dos instancias que descuenten a la vez nunca dejan el stock en negativo. Si el stock es insuficiente responde `409` y no modifica nada.

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

Cada instancia usa su propia base `productos_db` con el rol `productos_app` (ver [arquitectura](arquitectura.md)). Hoy se conecta solo al primario con un `DataSource` simple, configurado por `DB_URL`, `DB_USER` y `DB_PASSWORD`. Las lecturas están marcadas con `@Transactional(readOnly = true)` y las escrituras con una transacción normal, de modo que al integrar `persistence-routing` (#8) las lecturas se repartirán entre las réplicas sin cambiar el código del servicio.

## servicio-pedidos

_Pendiente (issue #12)._
