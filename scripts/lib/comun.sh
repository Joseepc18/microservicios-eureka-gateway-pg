# Funciones comunes de los scripts de pruebas P05-P08 (persistencia).
# Se carga con: . "$(dirname "$0")/lib/comun.sh"
#
# Variables opcionales:
#   ENV_FILE  archivo de variables para docker compose (por defecto, el .env de la raíz)
#   GATEWAY   URL base del Gateway (por defecto http://127.0.0.1:${GATEWAY_PORT:-8080})

set -eu

# Git Bash en Windows convierte argumentos que parecen rutas (/pedidos); se desactiva.
export MSYS_NO_PATHCONV=1

RAIZ=$(cd "$(dirname "$0")/.." && pwd)
cd "$RAIZ"

GATEWAY="${GATEWAY:-http://127.0.0.1:${GATEWAY_PORT:-8080}}"
TMP_DIR=$(mktemp -d)
# Cuerpo de la última respuesta HTTP (http se ejecuta en un subshell; el archivo es compartido).
CUERPO="$TMP_DIR/cuerpo"
# En Git Bash, curl puede ser el curl.exe de Windows, que no entiende rutas /tmp.
if command -v cygpath >/dev/null 2>&1; then CUERPO="$(cygpath -m "$TMP_DIR")/cuerpo"; fi
trap 'rm -rf "$TMP_DIR"' EXIT

dc() {
    if [ -n "${ENV_FILE:-}" ]; then
        docker compose --env-file "$ENV_FILE" "$@"
    else
        docker compose "$@"
    fi
}

# Envía la salida del script a la pantalla y a docs/evidencias/<prueba>/salida.txt.
iniciar_evidencia() {
    EVID="docs/evidencias/$1"
    mkdir -p "$EVID"
    exec > >(tee "$EVID/salida.txt") 2>&1
    echo "# $1 · $2"
    echo "# Ejecutado: $(date -u '+%Y-%m-%d %H:%M:%S') UTC"
    T0=$(ahora_utc)
}

ahora_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
hora() { date -u +%H:%M:%S; }

titulo() { printf '\n## %s\n\n' "$*"; }

# Ejecuta SQL como administrador dentro del contenedor del nodo (primary, replica1, replica2),
# sin pedir la clave: el rol administrador usa la conexión local del contenedor.
sql() {
    local nodo=$1 base=$2 consulta=$3
    echo "\$ [$nodo/$base] $consulta"
    dc exec -T "$nodo" sh -c 'psql -U "$POSTGRES_USER" -d "$1" -X -P pager=off -c "$2"' sh "$base" "$consulta"
}

# Solicitud HTTP al Gateway con X-Request-Id. Imprime "código tiempo" y deja el cuerpo en el archivo $CUERPO.
# Uso: http GET /api/pedidos p05-lectura-1 [json]
http() {
    local metodo=$1 ruta=$2 rid=$3 datos=${4:-}
    # Sin respuesta, curl no escribe el archivo: se borra para no leer el cuerpo de la solicitud anterior.
    rm -f "$CUERPO"
    # Si falla la conexión, curl imprime igualmente "000 <tiempo>s".
    if [ -n "$datos" ]; then
        curl -s -o "$CUERPO" -w '%{http_code} %{time_total}s' -X "$metodo" "$GATEWAY$ruta" \
            -H "X-Request-Id: $rid" -H 'Content-Type: application/json' -d "$datos" || true
    else
        curl -s -o "$CUERPO" -w '%{http_code} %{time_total}s' -X "$metodo" "$GATEWAY$ruta" \
            -H "X-Request-Id: $rid" || true
    fi
}

# Primer valor de "id" del cuerpo JSON de la última respuesta.
id_respuesta() { grep -o '"id":[0-9]*' "$CUERPO" | head -1 | cut -d: -f2; }

# Nodo que atendió la solicitud, según el log "DB route" de servicio-pedidos.
nodo_de() {
    dc logs --no-log-prefix --since "$T0" servicio-pedidos 2>/dev/null \
        | grep "\[$1\]" | grep -o 'DB route=[A-Z]* node=[a-z0-9]* tx=[a-z-]*\( read=critical\)\?' | tail -1
}

# Guarda las líneas del enrutamiento (DB route/health/retry) de servicio-pedidos desde el inicio
# de la prueba en docs/evidencias/<prueba>/logs-servicio-pedidos.txt.
guardar_logs() {
    dc logs --no-log-prefix --since "$T0" servicio-pedidos 2>/dev/null \
        | grep -E 'DB (route|health|retry)|Pedido [0-9]+ creado|Enrutamiento de persistencia' \
        > "$EVID/logs-servicio-pedidos.txt" || true
    echo "Logs de enrutamiento guardados en $EVID/logs-servicio-pedidos.txt ($(wc -l < "$EVID/logs-servicio-pedidos.txt") líneas)"
}

estado_contenedores() {
    echo "\$ docker compose ps -a primary replica1 replica2 servicio-pedidos"
    dc ps -a --format 'table {{.Service}}\t{{.Status}}' primary replica1 replica2 servicio-pedidos
}

# Crea un producto con stock suficiente para las pruebas y deja su id en $PRODUCTO_ID.
crear_producto() {
    local r
    r=$(http POST /api/productos "$1-producto" \
        '{"nombre":"Producto de prueba '"$1"'","descripcion":"Creado por scripts/'"$1"'","precio":10.00,"stock":100000}')
    PRODUCTO_ID=$(id_respuesta)
    echo "POST /api/productos -> $r, productoId=$PRODUCTO_ID"
    [ "${r%% *}" = 201 ] || { echo "No se pudo crear el producto de prueba"; exit 1; }
}

# Crea un pedido y deja su id en $PEDIDO_ID. Uso: crear_pedido <request-id>
crear_pedido() {
    local r
    r=$(http POST /api/pedidos "$1" '{"productoId":'"$PRODUCTO_ID"',"cantidad":1}')
    PEDIDO_ID=$(id_respuesta)
    echo "$(hora) POST /api/pedidos [$1] -> $r, pedidoId=$PEDIDO_ID"
    [ "${r%% *}" = 201 ] && [ -n "$PEDIDO_ID" ] || { echo "No se pudo crear el pedido $1"; exit 1; }
}

esperar_saludable() {
    local servicio=$1 limite=${2:-120} i=0
    while [ "$(dc ps --format '{{.Health}}' "$servicio")" != healthy ]; do
        i=$((i + 1))
        [ "$i" -le "$limite" ] || { echo "$servicio no quedó healthy"; return 1; }
        sleep 1
    done
}
