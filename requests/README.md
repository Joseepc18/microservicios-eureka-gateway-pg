# Colección de solicitudes

Ejemplos reproducibles de todas las operaciones de `servicio-productos` y `servicio-pedidos`, siempre **a través del Gateway** (`/api/...`), más solicitudes de verificación y de error. Las operaciones, cuerpos y códigos de respuesta están descritos en [docs/api.md](../docs/api.md).

| Archivo | Contenido |
|---|---|
| [verificacion.http](verificacion.http) | Salud del Gateway y de los servicios, rutas `lb://`, registro de Eureka y la solicitud para comprobar el balanceo |
| [productos.http](productos.http) | CRUD de productos y descuento de stock |
| [pedidos.http](pedidos.http) | Crear, consultar, listar y cancelar pedidos; `X-Request-Id` |
| [errores.http](errores.http) | `404`, `409`, `400`, `405` y `415` de ambos servicios |
| [servicio-caido.http](servicio-caido.http) | `503` con `servicio-productos` detenido (requiere detener contenedores) |

## Cómo usarla

1. Levantar el sistema (ver el [README principal](../README.md)):

   ```bash
   cp .env.example .env
   docker compose up -d --build --wait
   ```

2. Abrir los archivos `.http` con **VS Code** y la extensión [REST Client](https://marketplace.visualstudio.com/items?itemName=humao.rest-client) (se envía cada solicitud con *Send Request*). También se pueden abrir con el cliente HTTP de IntelliJ IDEA.

3. Ejecutar cada archivo **de arriba hacia abajo**: algunas solicitudes usan el resultado de una anterior del mismo archivo (por ejemplo, `productos.http` elimina el producto que creó antes).

Las variables (`@gateway`, `@eureka`, etc.) están al inicio de cada archivo. Si cambiaste un puerto en `.env`, edítalo ahí. Se usa `127.0.0.1` y no `localhost` porque en Windows con Docker Desktop `localhost` puede resolver primero a IPv6 y la solicitud se queda colgada.

Cada solicitud indica en un comentario el código que debe devolver (`# Esperado: 201`).

## Notas

- **Compatibilidad:** las referencias a respuestas anteriores (`{{crearProducto.response.body.$.id}}`) usan la sintaxis de REST Client. IntelliJ no las entiende: allí hay que reemplazar el identificador a mano por el que devolvió la solicitud anterior.
- **`X-Instance-Id`:** la respuesta indica qué instancia atendió la solicitud (`productos-1`, `productos-2` o `pedidos-1`). Repetir `GET /api/productos/1` en `verificacion.http` muestra el balanceo.
- **El stock cambia:** cada ejecución de `pedidos.http` y `errores.http` descuenta unas pocas unidades del producto 2 (150 al iniciar). Para volver a los datos iniciales: `docker compose down -v` y levantar de nuevo.
- **Servicio caído:** seguir los pasos de [servicio-caido.http](servicio-caido.http): detener las dos instancias de productos, esperar unos 15 s, enviar las solicitudes y volver a iniciarlas con `docker compose start servicio-productos-1 servicio-productos-2`.
- **Sin secretos:** la colección no contiene claves ni tokens. Las contraseñas de la base de datos solo existen en el archivo `.env`, que no se versiona.
