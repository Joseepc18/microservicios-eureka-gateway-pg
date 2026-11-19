#!/usr/bin/env bash
# P04 · Comunicación entre servicios.
# Crea pedidos por el Gateway: servicio-pedidos llama a servicio-productos (descubierto por Eureka)
# para consultar el producto y descontar stock. Comprueba el caso correcto y los tres casos de error
# (producto inexistente, stock insuficiente, servicio-productos detenido) y sigue cada solicitud por su
# X-Request-Id en los logs de ambos servicios. Evidencia en docs/evidencias/P04.
#
# Detiene las dos instancias de servicio-productos para el caso 503 y las vuelve a iniciar al terminar
# (también si el script falla a mitad).
# Variables opcionales: ENV_FILE, GATEWAY (ver scripts/lib/comun.sh), EUREKA (por defecto
# http://127.0.0.1:${EUREKA_PORT:-8761}).
# Uso: scripts/p04-comunicacion.sh

. "$(dirname "$0")/lib/comun.sh"
EUREKA="${EUREKA:-http://127.0.0.1:${EUREKA_PORT:-8761}}"
iniciar_evidencia P04 "Comunicación entre servicios"

FALLOS=0
comprobar() {   # <descripción> <esperado> <obtenido>
    if [ "$2" = "$3" ]; then
        echo "  [OK]    $1: $3"
    else
        echo "  [FALLA] $1: esperado $2, obtenido $3"
        FALLOS=$((FALLOS + 1))
    fi
}

# Garantiza que las instancias de productos queden en marcha, pase lo que pase.
restaurar() { dc start servicio-productos-1 servicio-productos-2 >/dev/null 2>&1 || true; }
trap 'restaurar; rm -rf "$TMP_DIR"' EXIT

instancias_eureka() {
    curl -s -H 'Accept: application/json' "$EUREKA/eureka/apps" | grep -o '"instanceId":"[^"]*"' | cut -d'"' -f4 | sort
}
# Número de instancias de servicio-productos que Eureka tiene registradas.
productos_en_eureka() { instancias_eureka | grep -c '^servicio-productos:' || true; }

contar_pedidos() {
    dc exec -T primary sh -c 'psql -U "$POSTGRES_USER" -d pedidos_db -tAc "SELECT count(*) FROM pedido"' | tr -d '[:space:]'
}
cuerpo() { cat "$CUERPO" 2>/dev/null || true; }
campo() { grep -o "\"$1\":[0-9.]*" "$CUERPO" | head -1 | cut -d: -f2; }

# Crea un producto con el precio y el stock indicados y deja su id en $PRODUCTO_ID.
nuevo_producto() {   # <request-id> <precio> <stock>
    local r
    r=$(http POST /api/productos "$1" '{"nombre":"Producto de prueba P04","descripcion":"Creado por scripts/p04-comunicacion.sh","precio":'"$2"',"stock":'"$3"'}')
    PRODUCTO_ID=$(id_respuesta)
    echo "$(hora) POST /api/productos [$1] -> $r, productoId=$PRODUCTO_ID (precio $2, stock $3)"
    [ "${r%% *}" = 201 ] && [ -n "$PRODUCTO_ID" ] || { echo "No se pudo crear el producto de prueba"; exit 1; }
}
stock_de() {   # <producto-id> <request-id>
    http GET "/api/productos/$1" "$2" >/dev/null
    campo stock
}

titulo "1. Estado inicial"
echo "\$ docker compose ps"
dc ps --format 'table {{.Service}}\t{{.Status}}' api-gateway discovery-server servicio-productos-1 servicio-productos-2 servicio-pedidos
for s in servicio-productos-1 servicio-productos-2 servicio-pedidos api-gateway; do
    [ "$(dc ps --format '{{.Health}}' "$s")" = healthy ] || { echo "$s no está healthy: levantar el sistema con docker compose up -d --build --wait"; exit 1; }
done
echo
echo "Instancias registradas en Eureka ($EUREKA/eureka/apps):"
instancias_eureka | sed 's/^/  /'
comprobar "instancias de servicio-productos en Eureka" 2 "$(productos_en_eureka)"

titulo "2. Caso correcto: POST /api/pedidos descuenta stock en servicio-productos"
nuevo_producto p04-producto-ok 20.00 10
PRODUCTO_OK=$PRODUCTO_ID
sleep 2   # las lecturas se atienden en réplicas: se deja replicar el producto recién creado
echo "Stock antes: $(stock_de "$PRODUCTO_OK" p04-stock-antes)"
r=$(http POST /api/pedidos p04-ok-1 '{"productoId":'"$PRODUCTO_OK"',"cantidad":3}')
PEDIDO_OK=$(id_respuesta)
echo "$(hora) POST /api/pedidos [p04-ok-1] -> $r"
echo "Respuesta: $(cuerpo)"
comprobar "estado HTTP de la creación" 201 "${r%% *}"
comprobar "total del pedido (3 x 20.00)" 60.00 "$(campo total)"
sleep 2
STOCK_DESPUES=$(stock_de "$PRODUCTO_OK" p04-stock-despues)
echo "Stock después: $STOCK_DESPUES"
comprobar "stock descontado en servicio-productos (10 - 3)" 7 "$STOCK_DESPUES"
r=$(http GET "/api/pedidos/$PEDIDO_OK" p04-ok-consulta)
echo "$(hora) GET /api/pedidos/$PEDIDO_OK [p04-ok-consulta] -> $r"
comprobar "consulta del pedido creado" 200 "${r%% *}"

titulo "3. Trazas del caso correcto: el X-Request-Id p04-ok-1 en ambos servicios"
sleep 1
dc logs --since "$T0" servicio-pedidos servicio-productos-1 servicio-productos-2 2>/dev/null \
    | grep '\[p04-ok-1\]' | sort -t'|' -k2 | sed 's/^/  /'
LLAMADAS=$(dc logs --no-log-prefix --since "$T0" servicio-productos-1 servicio-productos-2 2>/dev/null | grep -c '\[p04-ok-1\] .*REQ method=' || true)
comprobar "solicitudes de servicio-productos con el mismo X-Request-Id (GET + PATCH)" 2 "$LLAMADAS"

titulo "4. Error: producto inexistente"
ANTES=$(contar_pedidos)
r=$(http POST /api/pedidos p04-404 '{"productoId":999999,"cantidad":1}')
echo "$(hora) POST /api/pedidos [p04-404] -> $r"
echo "Respuesta: $(cuerpo)"
comprobar "estado HTTP" 404 "${r%% *}"
comprobar "pedidos guardados (no debe crearse ninguno)" "$ANTES" "$(contar_pedidos)"

titulo "5. Error: stock insuficiente"
nuevo_producto p04-producto-escaso 5.00 2
PRODUCTO_ESCASO=$PRODUCTO_ID
sleep 2
ANTES=$(contar_pedidos)
r=$(http POST /api/pedidos p04-409 '{"productoId":'"$PRODUCTO_ESCASO"',"cantidad":5}')
echo "$(hora) POST /api/pedidos [p04-409] (5 unidades de un producto con stock 2) -> $r"
echo "Respuesta: $(cuerpo)"
comprobar "estado HTTP" 409 "${r%% *}"
comprobar "pedidos guardados (no debe crearse ninguno)" "$ANTES" "$(contar_pedidos)"
sleep 2
comprobar "stock del producto sin cambios" 2 "$(stock_de "$PRODUCTO_ESCASO" p04-stock-escaso)"

titulo "6. Error: servicio-productos detenido"
ANTES=$(contar_pedidos)
T_STOP=$(date -u +%s)
echo "$(hora) docker compose stop servicio-productos-1 servicio-productos-2"
dc stop servicio-productos-1 servicio-productos-2
echo "$(hora) instancias detenidas"
echo
echo "6a. Inmediatamente después de detenerlas (la caché del LoadBalancer puede conservar las instancias):"
r=$(http POST /api/pedidos p04-503-inmediato '{"productoId":'"$PRODUCTO_OK"',"cantidad":1}')
echo "$(hora) POST /api/pedidos [p04-503-inmediato] -> $r"
echo "Respuesta: $(cuerpo)"
comprobar "estado HTTP" 503 "${r%% *}"
echo
echo "6b. Con Eureka ya sin instancias (espera de 15 s):"
sleep 15
echo "Instancias de servicio-productos en Eureka: $(productos_en_eureka)"
r=$(http POST /api/pedidos p04-503 '{"productoId":'"$PRODUCTO_OK"',"cantidad":1}')
echo "$(hora) POST /api/pedidos [p04-503] -> $r"
echo "Respuesta: $(cuerpo)"
comprobar "estado HTTP" 503 "${r%% *}"
comprobar "pedidos guardados (no debe crearse ninguno)" "$ANTES" "$(contar_pedidos)"
r=$(http GET /api/productos p04-503-gateway)
echo "$(hora) GET /api/productos [p04-503-gateway] (directo por el Gateway) -> $r"
comprobar "el Gateway también responde 503" 503 "${r%% *}"
echo "servicio-pedidos sigue disponible: $(dc ps --format '{{.Service}} {{.Health}}' servicio-pedidos)"

titulo "7. Recuperación: se vuelven a iniciar las instancias"
echo "$(hora) docker compose start servicio-productos-1 servicio-productos-2"
dc start servicio-productos-1 servicio-productos-2
esperar_saludable servicio-productos-1
esperar_saludable servicio-productos-2
echo "$(hora) instancias healthy; esperando su registro en Eureka"
i=0
while [ "$(productos_en_eureka)" != 2 ] && [ "$i" -lt 60 ]; do i=$((i + 1)); sleep 1; done
echo "$(hora) instancias de servicio-productos en Eureka: $(productos_en_eureka)"
# La caché del LoadBalancer (5 s) puede tardar en ver las instancias: se reintenta hasta 30 s.
i=0; estado=000
while [ "$estado" != 201 ] && [ "$i" -lt 30 ]; do
    i=$((i + 1))
    r=$(http POST /api/pedidos p04-recuperado '{"productoId":'"$PRODUCTO_OK"',"cantidad":1}')
    estado=${r%% *}
    [ "$estado" = 201 ] || sleep 1
done
echo "$(hora) POST /api/pedidos [p04-recuperado] -> $r (intento $i)"
echo "Respuesta: $(cuerpo)"
comprobar "estado HTTP tras restaurar" 201 "$estado"

titulo "8. Trazas de los casos de error (X-Request-Id p04-404, p04-409 y p04-503*)"
sleep 1
dc logs --since "$T0" servicio-pedidos servicio-productos-1 servicio-productos-2 2>/dev/null \
    | grep -E '\[p04-(404|409|503)' | sort -t'|' -k2 | sed 's/^/  /' | cut -c1-260

dc logs --since "$T0" servicio-pedidos servicio-productos-1 servicio-productos-2 2>/dev/null \
    | grep '\[p04-' | sort -t'|' -k2 > "$EVID/trazas.txt" || true
echo
echo "Trazas completas guardadas en $EVID/trazas.txt ($(wc -l < "$EVID/trazas.txt") líneas)"

titulo "Resultado"
echo "Comprobaciones fallidas: $FALLOS"
dc ps --format 'table {{.Service}}\t{{.Status}}' servicio-productos-1 servicio-productos-2 servicio-pedidos
if [ "$FALLOS" = 0 ]; then echo "P04 OK"; else echo "P04 FALLA"; exit 1; fi
