#!/usr/bin/env bash
# P02 · Balanceo HTTP.
#
# Envía N solicitudes GET al Gateway sobre un endpoint de servicio-productos y cuenta, por el
# header X-Instance-Id, cuántas respondió cada instancia. También cuenta éxitos (2xx) y errores
# (cualquier otro código, incluido 000 si no hubo respuesta).
#
# Uso:
#   scripts/p02-balanceo.sh [CANTIDAD] [URL_BASE] [RUTA]
#
# También se puede parametrizar con variables de entorno (los argumentos tienen prioridad):
#   CANTIDAD     solicitudes a enviar (por defecto 100)
#   URL_BASE     URL del Gateway (por defecto http://127.0.0.1:8080)
#   RUTA         endpoint a consultar (por defecto /api/productos)
#   PAUSA        segundos entre solicitudes (por defecto 0: lo más rápido posible)
#   DETALLE=1    imprime una línea por solicitud (por defecto 1; usar 0 para ver solo el resumen)
#
# Código de salida: 0 si todas las solicitudes fueron 2xx; 1 si hubo errores; 2 si el Gateway
# no responde antes de empezar.
#
# Se usa 127.0.0.1 y no localhost: en Windows con Docker Desktop localhost puede resolver
# primero a IPv6 y la solicitud se queda colgada.

set -u

CANTIDAD="${1:-${CANTIDAD:-100}}"
URL_BASE="${2:-${URL_BASE:-http://127.0.0.1:8080}}"
RUTA="${3:-${RUTA:-/api/productos}}"
PAUSA="${PAUSA:-0}"
DETALLE="${DETALLE:-1}"
URL="${URL_BASE%/}${RUTA}"

if ! [[ "$CANTIDAD" =~ ^[0-9]+$ ]] || [ "$CANTIDAD" -lt 1 ]; then
  echo "CANTIDAD debe ser un entero positivo (recibido: '$CANTIDAD')" >&2
  exit 2
fi

# Una solicitud: imprime "codigo instancia milisegundos".
# -D - vuelca las cabeceras en la salida estándar; el cuerpo se descarta.
solicitar() {
  local salida codigo ms instancia
  salida=$(curl -s -o /dev/null -D - --max-time 15 \
    -w 'CODIGO:%{http_code} TIEMPO:%{time_total}' "$URL" 2>/dev/null)
  codigo=${salida##*CODIGO:}
  codigo=${codigo%% *}
  ms=${salida##*TIEMPO:}
  ms=$(awk -v s="$ms" 'BEGIN { printf "%d", s * 1000 }')
  instancia=$(printf '%s' "$salida" | tr -d '\r' | grep -i '^x-instance-id:' | head -1 \
    | cut -d: -f2- | tr -d ' ')
  echo "${codigo:-000} ${instancia:--} ${ms:-0}"
}

echo "== P02 · Balanceo HTTP =="
echo "Inicio:    $(date '+%Y-%m-%d %H:%M:%S')"
echo "URL:       $URL"
echo "Solicitudes: $CANTIDAD (pausa ${PAUSA}s)"

codigo_previo=$(curl -s -o /dev/null --max-time 10 -w '%{http_code}' "$URL_BASE/actuator/health" 2>/dev/null)
if [ "$codigo_previo" != "200" ]; then
  echo "El Gateway no responde en $URL_BASE (HTTP ${codigo_previo:-000}). ¿Está levantado el sistema?" >&2
  exit 2
fi
echo

resultados=$(mktemp)
trap 'rm -f "$resultados"' EXIT

[ "$DETALLE" = "1" ] && printf '%-6s %-7s %-14s %s\n' "Nº" "Código" "Instancia" "ms"
for ((i = 1; i <= CANTIDAD; i++)); do
  linea=$(solicitar)
  echo "$linea" >> "$resultados"
  if [ "$DETALLE" = "1" ]; then
    read -r c inst ms <<< "$linea"
    printf '%-6s %-7s %-14s %s\n' "$i" "$c" "$inst" "$ms"
  fi
  [ "$PAUSA" != "0" ] && sleep "$PAUSA"
done

total=$(wc -l < "$resultados")
exitosas=$(awk '$1 ~ /^2[0-9][0-9]$/ { n++ } END { print n + 0 }' "$resultados")
errores=$((total - exitosas))

echo
echo "== Resumen =="
echo "Solicitudes enviadas: $total"
echo "Exitosas (2xx):       $exitosas"
echo "Errores:              $errores"
echo
echo "Distribución por instancia (header X-Instance-Id):"
sort -k2 "$resultados" | awk '{ n[$2]++ } END { for (k in n) printf "%s %d\n", k, n[k] }' | sort \
  | awk -v t="$total" '{ printf "  %-14s %4d  (%.1f %%)\n", $1, $2, 100 * $2 / t }'
echo
echo "Distribución por código HTTP:"
awk '{ n[$1]++ } END { for (k in n) printf "%s %d\n", k, n[k] }' "$resultados" | sort \
  | awk '{ printf "  %-6s %4d\n", $1, $2 }'
echo
echo "Fin:       $(date '+%Y-%m-%d %H:%M:%S')"

[ "$errores" -eq 0 ]
