#!/usr/bin/env bash
# P07 · Consistencia de lectura.
# Crea pedidos y los consulta de inmediato con GET /api/pedidos/{id}, la operación crítica de
# servicio-pedidos (@ReadFromPrimary): se atiende en el primario aunque sea de solo lectura.
# Para mostrar por qué hace falta, pausa un momento el replay de WAL de las réplicas: una lectura
# ordinaria (GET /api/pedidos, en réplica) no ve el pedido recién creado y la crítica sí.
# Evidencia en docs/evidencias/P07.
#
# Uso: scripts/p07-consistencia.sh [repeticiones]

. "$(dirname "$0")/lib/comun.sh"
REPETICIONES=${1:-20}
iniciar_evidencia P07 "Consistencia de lectura"

# Réplicas en marcha (si P06 dejó replica1 detenida, solo replica2).
REPLICAS=""
for nodo in replica1 replica2; do
    if [ "$(dc ps --format "{{.State}}" "$nodo")" = running ]; then REPLICAS="$REPLICAS $nodo"; fi
done

reanudar_replay() {
    for nodo in $REPLICAS; do
        dc exec -T "$nodo" sh -c 'psql -U "$POSTGRES_USER" -d postgres -X -At -c "SELECT pg_wal_replay_resume()"' >/dev/null 2>&1 || true
    done
}
# Pase lo que pase, el replay no queda pausado.
trap 'reanudar_replay; rm -rf "$TMP_DIR"' EXIT

titulo "1. Estado inicial"
estado_contenedores
crear_producto p07

titulo "2. Escritura seguida de lectura crítica inmediata ($REPETICIONES veces)"
encontrados=0; en_primario=0
for i in $(seq 1 "$REPETICIONES"); do
    crear_pedido "p07-crear-$i"
    r=$(http GET "/api/pedidos/$PEDIDO_ID" "p07-leer-$i")
    echo "$(hora) GET /api/pedidos/$PEDIDO_ID [p07-leer-$i] -> $r $(tr -d '\n' < "$CUERPO" | cut -c1-120)"
    if [ "${r%% *}" = 200 ]; then encontrados=$((encontrados + 1)); fi
done
sleep 2
for i in $(seq 1 "$REPETICIONES"); do
    n=$(nodo_de "p07-leer-$i")
    echo "p07-crear-$i -> $(nodo_de "p07-crear-$i") | p07-leer-$i -> $n"
    case "$n" in *"route=PRIMARY node=primary tx=read-only read=critical"*) en_primario=$((en_primario + 1)) ;; esac
done

titulo "3. Contraste: réplicas con retraso (replay pausado en:$REPLICAS)"
for nodo in $REPLICAS; do
    sql "$nodo" postgres "SELECT '$nodo' AS nodo, pg_wal_replay_pause()"
done
crear_pedido p07-contraste-crear
ID_CONTRASTE=$PEDIDO_ID
r=$(http GET "/api/pedidos/$ID_CONTRASTE" p07-contraste-critica)
echo "$(hora) GET /api/pedidos/$ID_CONTRASTE (crítica) [p07-contraste-critica] -> $r"
critica_ok=$([ "${r%% *}" = 200 ] && echo si || echo no)
r=$(http GET /api/pedidos p07-contraste-ordinaria)
if grep -q "\"id\":$ID_CONTRASTE[,}]" "$CUERPO"; then ordinaria_ve=si; else ordinaria_ve=no; fi
echo "$(hora) GET /api/pedidos (ordinaria) [p07-contraste-ordinaria] -> $r, incluye el pedido $ID_CONTRASTE: $ordinaria_ve"
for nodo in $REPLICAS; do
    sql "$nodo" pedidos_db "SELECT '$nodo' AS nodo, count(*) AS tiene_el_pedido FROM pedido WHERE id = $ID_CONTRASTE"
done
sql primary pedidos_db "SELECT 'primary' AS nodo, count(*) AS tiene_el_pedido FROM pedido WHERE id = $ID_CONTRASTE"
echo "Se reanuda el replay:"
reanudar_replay
sleep 1
r=$(http GET /api/pedidos p07-contraste-despues)
if grep -q "\"id\":$ID_CONTRASTE[,}]" "$CUERPO"; then despues_ve=si; else despues_ve=no; fi
echo "$(hora) GET /api/pedidos (ordinaria) [p07-contraste-despues] -> $r, incluye el pedido $ID_CONTRASTE: $despues_ve"
sleep 2
for rid in p07-contraste-crear p07-contraste-critica p07-contraste-ordinaria p07-contraste-despues; do
    echo "$rid -> $(nodo_de "$rid")"
done

guardar_logs

titulo "Resultado"
echo "Lectura crítica inmediata: encontrada $encontrados de $REPETICIONES; atendida por el primario $en_primario de $REPETICIONES"
echo "Contraste con réplicas retrasadas: crítica encuentra el pedido=$critica_ok; ordinaria lo ve=$ordinaria_ve; ordinaria tras reanudar=$despues_ve"
if [ "$encontrados" = "$REPETICIONES" ] && [ "$en_primario" = "$REPETICIONES" ] && [ "$critica_ok" = si ]; then
    echo "P07 OK"
else
    echo "P07 FALLA"; exit 1
fi
