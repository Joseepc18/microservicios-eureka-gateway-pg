#!/usr/bin/env bash
# P03 · Fallo de instancia.
#
# Genera carga continua de GET contra el Gateway (una solicitud cada INTERVALO_MS, sin esperar la
# respuesta anterior), termina una instancia de servicio-productos a mitad de la prueba y mide:
#   - solicitudes, códigos de estado, instancia que respondió y errores antes, durante y después;
#   - tiempo hasta el último error y hasta que Eureka expulsa la instancia;
# Después vuelve a levantar la instancia y comprueba que se registra de nuevo en Eureka y que
# vuelve a recibir tráfico por el Gateway.
#
# Requiere el sistema levantado (docker compose up -d --build --wait) y ejecutarse en el host
# donde corre Docker Compose. Se puede lanzar desde cualquier carpeta: el script entra a la
# raíz del repositorio.
#
# Uso:
#   scripts/p03-fallo-instancia.sh
#   MODO=stop scripts/p03-fallo-instancia.sh     # parada ordenada en lugar de terminación abrupta
#
# Variables de entorno (valores por defecto entre paréntesis):
#   MODO            kill (abrupta, no se da de baja en Eureka) o stop (ordenada, sí se da de baja) (kill)
#   SERVICIO        servicio de Compose que se termina (servicio-productos-2)
#   INSTANCIA       instanceId que anuncia en Eureka y en X-Instance-Id (productos-2)
#   APP             nombre lógico en Eureka (servicio-productos)
#   URL_BASE        URL del Gateway (http://127.0.0.1:8080)
#   RUTA            endpoint al que se envía la carga (/api/productos)
#   EUREKA_URL      URL de Eureka (http://127.0.0.1:8761)
#   DURACION        segundos de carga (90)
#   FALLO_EN        segundo de la carga en el que se termina la instancia (20)
#   INTERVALO_MS    milisegundos entre solicitudes (200)
#   VENTANA_DURANTE segundos, desde el fallo, que se consideran fase "durante" (40; el peor caso
#                   documentado para que el Gateway deje de enviar a la instancia es ~35 s)
#   SALIDA          carpeta para los archivos de detalle (por defecto, una carpeta temporal)
#
# Se usa 127.0.0.1 y no localhost: en Windows con Docker Desktop localhost puede resolver
# primero a IPv6 y la solicitud se queda colgada.

set -u
cd "$(dirname "$0")/.." || exit 2

MODO="${MODO:-kill}"
SERVICIO="${SERVICIO:-servicio-productos-2}"
INSTANCIA="${INSTANCIA:-productos-2}"
APP="${APP:-servicio-productos}"
URL_BASE="${URL_BASE:-http://127.0.0.1:8080}"
RUTA="${RUTA:-/api/productos}"
EUREKA_URL="${EUREKA_URL:-http://127.0.0.1:8761}"
DURACION="${DURACION:-90}"
FALLO_EN="${FALLO_EN:-20}"
INTERVALO_MS="${INTERVALO_MS:-200}"
VENTANA_DURANTE="${VENTANA_DURANTE:-40}"
SALIDA="${SALIDA:-$(mktemp -d)}"
URL="${URL_BASE%/}${RUTA}"
APP_EUREKA=$(printf '%s' "$APP" | tr '[:lower:]' '[:upper:]')

case "$MODO" in kill | stop) ;; *) echo "MODO debe ser kill o stop (recibido: '$MODO')" >&2; exit 2 ;; esac
mkdir -p "$SALIDA"
SOLICITUDES="$SALIDA/solicitudes.txt"   # t_ms codigo instancia latencia_ms
EUREKA_LOG="$SALIDA/eureka.txt"         # t_ms estado de la instancia en el registro de Eureka
: > "$SOLICITUDES"
: > "$EUREKA_LOG"

ahora_ms() { echo $(($(date +%s%N) / 1000000)); }

dormir_ms() { sleep "$(printf '%d.%03d' $(($1 / 1000)) $(($1 % 1000)))"; }

# Una solicitud GET al Gateway; imprime "codigo instancia milisegundos".
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

# Estado de la instancia en el REGISTRO de Eureka: UP, DOWN, AUSENTE o SIN_RESPUESTA.
estado_eureka() {
  local json estado
  json=$(curl -s --max-time 3 -H 'Accept: application/json' \
    "$EUREKA_URL/eureka/apps/$APP_EUREKA" 2>/dev/null)
  if [ -z "$json" ]; then echo "SIN_RESPUESTA"; return; fi
  estado=$(printf '%s' "$json" | tr '{' '\n' | grep "\"instanceId\":\"${APP}:${INSTANCIA}\"" \
    | grep -o '"status":"[A-Z_]*"' | head -1 | cut -d'"' -f4)
  echo "${estado:-AUSENTE}"
}

vigilar_eureka() {
  while :; do
    echo "$(($(ahora_ms) - T0)) $(estado_eureka)" >> "$EUREKA_LOG"
    sleep 1
  done
}

# ---------------------------------------------------------------- comprobaciones previas
echo "== P03 · Fallo de instancia =="
echo "Inicio:      $(date '+%Y-%m-%d %H:%M:%S')"
echo "Modo:        $MODO (docker compose $MODO $SERVICIO)"
echo "Carga:       GET $URL cada ${INTERVALO_MS} ms durante ${DURACION} s"
echo "Fallo en:    t=${FALLO_EN} s   Fase 'durante': ${VENTANA_DURANTE} s desde el fallo"
echo "Instancia:   $APP:$INSTANCIA (servicio de Compose: $SERVICIO)"
echo "Detalle en:  $SALIDA"

if [ "$(curl -s -o /dev/null --max-time 10 -w '%{http_code}' "$URL_BASE/actuator/health")" != "200" ]; then
  echo "El Gateway no responde en $URL_BASE. ¿Está levantado el sistema?" >&2
  exit 2
fi
if [ "$(estado_eureka)" != "UP" ]; then
  echo "La instancia $APP:$INSTANCIA no figura como UP en Eureka antes de empezar." >&2
  exit 2
fi
if [ "$FALLO_EN" -ge "$DURACION" ]; then
  echo "FALLO_EN debe ser menor que DURACION." >&2
  exit 2
fi
echo "Estado inicial en Eureka de $INSTANCIA: UP"
echo

INICIO_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
T0=$(ahora_ms)
vigilar_eureka &
PID_EUREKA=$!
trap 'kill "$PID_EUREKA" 2>/dev/null' EXIT

# ---------------------------------------------------------------- carga con fallo a mitad
pids=()
fallo_lanzado=0
T_FALLO=0
i=0
while :; do
  objetivo=$((i * INTERVALO_MS))
  actual=$(($(ahora_ms) - T0))
  [ "$actual" -ge $((DURACION * 1000)) ] && break
  [ "$objetivo" -gt "$actual" ] && dormir_ms $((objetivo - actual))
  actual=$(($(ahora_ms) - T0))

  if [ "$fallo_lanzado" -eq 0 ] && [ "$actual" -ge $((FALLO_EN * 1000)) ]; then
    fallo_lanzado=1
    T_FALLO=$actual
    ( docker compose "$MODO" "$SERVICIO" > "$SALIDA/docker-fallo.txt" 2>&1
      echo "$(($(ahora_ms) - T0))" > "$SALIDA/fallo-completado-ms.txt" ) &
    echo "[t=$(awk -v m="$T_FALLO" 'BEGIN { printf "%.1f", m / 1000 }') s] docker compose $MODO $SERVICIO"
  fi

  # Cada solicitud se lanza en segundo plano para mantener el ritmo aunque una tarde varios segundos.
  ( read -r codigo instancia ms <<< "$(solicitar)"
    echo "$actual $codigo $instancia $ms" >> "$SOLICITUDES" ) &
  pids+=($!)
  i=$((i + 1))
done
echo "[t=$(awk -v m="$(($(ahora_ms) - T0))" 'BEGIN { printf "%.1f", m / 1000 }') s] fin de la carga; esperando solicitudes en curso"
wait "${pids[@]}"
sort -n -o "$SOLICITUDES" "$SOLICITUDES"
FALLO_COMPLETADO=$(cat "$SALIDA/fallo-completado-ms.txt" 2>/dev/null || echo "?")
echo "Comando de fallo terminado en t=${FALLO_COMPLETADO} ms (lanzado en t=${T_FALLO} ms)"

# ---------------------------------------------------------------- análisis por fases
VENTANA_MS=$((VENTANA_DURANTE * 1000))
awk -v f="$T_FALLO" -v v="$VENTANA_MS" -v d="$SALIDA" '{
  fase = ($1 < f) ? "antes" : (($1 < f + v) ? "durante" : "despues")
  print > (d "/fase-" fase ".tmp")
}' "$SOLICITUDES"

resumen_fase() { # nombre, descripción
  local archivo="$SALIDA/fase-$1.tmp"
  echo "--- Fase '$1' ($2) ---"
  if [ ! -s "$archivo" ]; then echo "  (sin solicitudes)"; return; fi
  local total ok
  total=$(wc -l < "$archivo")
  ok=$(awk '$2 ~ /^2[0-9][0-9]$/ { n++ } END { print n + 0 }' "$archivo")
  echo "  Solicitudes: $total   Exitosas (2xx): $ok   Errores: $((total - ok))"
  # Una exitosa tardía (>= 1500 ms) indica que el primer intento cayó en la instancia caída
  # (timeout de conexión de 2 s) y el reintento del Gateway la rescató.
  awk '$2 ~ /^2[0-9][0-9]$/ { if ($4 >= 1500) l++; if ($4 > m) m = $4 }
    END { printf "  Exitosas lentas (>= 1500 ms, rescatadas por reintento): %d   latencia máxima exitosa: %d ms\n", l + 0, m + 0 }' "$archivo"
  awk '$2 !~ /^2[0-9][0-9]$/ { if ($4 > m) m = $4; n++ }
    END { if (n) printf "  Latencia máxima de una solicitud con error: %d ms\n", m }' "$archivo"
  echo "  Códigos HTTP:"
  awk '{ print $2 }' "$archivo" | sort | uniq -c | awk '{ printf "    %-6s %5d\n", $2, $1 }'
  echo "  Instancia que respondió (X-Instance-Id; '-' = sin header, p. ej. error del Gateway):"
  awk '{ print $3 }' "$archivo" | sort | uniq -c | awk '{ printf "    %-12s %5d\n", $2, $1 }'
}

echo
echo "== Resultados por fases (tiempos medidos desde el inicio de la carga) =="
resumen_fase antes "t < $(awk -v m="$T_FALLO" 'BEGIN { printf "%.1f", m / 1000 }') s"
resumen_fase durante "desde el fallo, ${VENTANA_DURANTE} s"
resumen_fase despues "después de la ventana"
rm -f "$SALIDA"/fase-*.tmp

echo
echo "== Errores en el tiempo (solicitudes en ventanas de 5 s desde el fallo) =="
printf '%-18s %12s %8s\n' "Ventana (s)" "Solicitudes" "Errores"
awk -v f="$T_FALLO" '{
  b = int(($1 - f) / 5000); if ($1 < f) b = -1
  n[b]++; if ($2 !~ /^2[0-9][0-9]$/) e[b]++
} END { for (b in n) printf "%d %d %d\n", b, n[b], e[b] + 0 }' "$SOLICITUDES" | sort -n \
  | awk '{ if ($1 < 0) r = "antes del fallo"; else r = sprintf("+%d a +%d", $1 * 5, $1 * 5 + 5);
           printf "%-18s %12d %8d\n", r, $2, $3 }'

echo
echo "== Tiempos (segundos desde el comando de fallo) =="
awk -v f="$T_FALLO" '$1 >= f && $2 !~ /^2[0-9][0-9]$/ { if (!p) p = $1; u = $1; n++ }
  END {
    if (n == 0) print "Errores tras el fallo:            ninguno";
    else {
      printf "Errores tras el fallo:            %d\n", n;
      printf "Primera solicitud con error:      +%.1f s\n", (p - f) / 1000;
      printf "Última solicitud con error:       +%.1f s  (desde ahí no hay más errores)\n", (u - f) / 1000
    }
  }' "$SOLICITUDES"
awk -v f="$T_FALLO" '$1 >= f && $2 ~ /^2[0-9][0-9]$/ && $4 >= 1500 { if (!p) p = $1; u = $1; n++ }
  END {
    if (n == 0) print "Solicitudes rescatadas por reintento (>= 1500 ms): ninguna";
    else {
      printf "Solicitudes rescatadas por reintento (>= 1500 ms): %d\n", n;
      printf "Primera / última rescatada:       +%.1f s / +%.1f s  (el Gateway aún elegía la instancia caída)\n", (p - f) / 1000, (u - f) / 1000
    }
  }' "$SOLICITUDES"
awk -v f="$T_FALLO" '$1 >= f && $2 == "DOWN" && !p { p = $1 }
  $1 >= f && $2 == "AUSENTE" && !a { a = $1 }
  END {
    if (p) printf "Eureka la marca DOWN:             +%.1f s\n", (p - f) / 1000;
    if (a) printf "Eureka la retira del registro:    +%.1f s  (muestreo cada ~1 s)\n", (a - f) / 1000;
    else print "Eureka la retira del registro:    no observado en la prueba"
  }' "$EUREKA_LOG"
echo "Referencia documentada (docs/arquitectura.md): expulsión ~24 s; Gateway deja de enviarle tráfico ~35 s (peor caso)."

echo
echo "== Estado de la instancia en el registro de Eureka (solo cambios) =="
awk -v f="$T_FALLO" '$2 != prev { printf "  t=%6.1f s (%+.1f s respecto al fallo): %s\n", $1 / 1000, ($1 - f) / 1000, $2; prev = $2 }' "$EUREKA_LOG"

# ---------------------------------------------------------------- registros del Gateway
docker compose logs --no-log-prefix --since "$INICIO_UTC" api-gateway 2>/dev/null \
  | grep 'GW method=' > "$SALIDA/gateway.txt"
echo
echo "== Log del Gateway durante la prueba: destino y estado (target / status, recuento) =="
sed -n 's/.*target=\([^ ]*\) .*status=\([0-9]*\).*/\1 \2/p' "$SALIDA/gateway.txt" | sort | uniq -c \
  | awk '{ printf "  %-28s %-6s %5d\n", $2, $3, $1 }'
echo "  (el Gateway registra una línea por solicitud con el destino del último intento)"
echo "Líneas de error del Gateway (status 5xx), hasta 15:"
grep -E 'status=5[0-9][0-9]' "$SALIDA/gateway.txt" | head -15 | sed 's/^/  /'

# ---------------------------------------------------------------- recuperación
echo
echo "== Recuperación: docker compose start $SERVICIO =="
T_START=$(ahora_ms)
docker compose start "$SERVICIO" 2>&1 | sed 's/^/  /'
echo "[arranque ordenado a las $(date '+%H:%M:%S')]"

T_REGISTRO=""
while [ $(($(ahora_ms) - T_START)) -lt 180000 ]; do
  if [ "$(estado_eureka)" = "UP" ]; then T_REGISTRO=$(($(ahora_ms) - T_START)); break; fi
  sleep 1
done
if [ -n "$T_REGISTRO" ]; then
  echo "Eureka: $APP:$INSTANCIA vuelve a figurar UP a los $(awk -v m="$T_REGISTRO" 'BEGIN { printf "%.1f", m / 1000 }') s del comando start."
else
  echo "Eureka: la instancia NO volvió a UP en 180 s."
fi

T_TRAFICO=""
while [ $(($(ahora_ms) - T_START)) -lt 240000 ]; do
  read -r codigo instancia ms <<< "$(solicitar)"
  if [ "$instancia" = "$INSTANCIA" ] && [[ "$codigo" =~ ^2 ]]; then
    T_TRAFICO=$(($(ahora_ms) - T_START)); break
  fi
  dormir_ms 500
done
if [ -n "$T_TRAFICO" ]; then
  echo "Gateway: $INSTANCIA vuelve a responder solicitudes a los $(awk -v m="$T_TRAFICO" 'BEGIN { printf "%.1f", m / 1000 }') s del comando start."
else
  echo "Gateway: $INSTANCIA NO volvió a recibir tráfico en 240 s."
fi

echo
echo "Comprobación posterior: 30 solicitudes seguidas"
for _ in $(seq 1 30); do solicitar; done > "$SALIDA/posterior.txt"
awk '{ print $2 }' "$SALIDA/posterior.txt" | sort | uniq -c | awk '{ printf "  %-12s %3d\n", $2, $1 }'
echo "  Códigos:"
awk '{ print $1 }' "$SALIDA/posterior.txt" | sort | uniq -c | awk '{ printf "    %-6s %3d\n", $2, $1 }'

echo
echo "Fin: $(date '+%Y-%m-%d %H:%M:%S')"

# El script termina bien solo si la instancia volvió a UP y a recibir tráfico.
[ -n "$T_REGISTRO" ] && [ -n "$T_TRAFICO" ]
