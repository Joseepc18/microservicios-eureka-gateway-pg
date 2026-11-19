#!/usr/bin/env bash
# P08 · Recuperación de réplica.
# Con replica1 detenida (la deja así P06; si no, este script la detiene), crea pedidos, reinicia
# replica1 y comprueba que se pone al día por streaming, que el módulo la reincorpora y que vuelve
# a atender consultas. Evidencia en docs/evidencias/P08.
#
# Uso: scripts/p08-recuperacion.sh [pedidos_con_la_replica_caida]

. "$(dirname "$0")/lib/comun.sh"
PEDIDOS=${1:-5}
iniciar_evidencia P08 "Recuperación de réplica"

titulo "1. Precondición: replica1 detenida"
if [ "$(dc ps --format '{{.State}}' replica1)" = running ]; then
    echo "replica1 está en marcha; se detiene (docker compose stop replica1)"
    dc stop replica1
    sleep 12   # que el módulo la excluya (2 verificaciones fallidas)
fi
estado_contenedores
crear_producto p08

titulo "2. Cambios mientras replica1 está detenida: $PEDIDOS pedidos"
for i in $(seq 1 "$PEDIDOS"); do
    crear_pedido "p08-caida-$i"
    if [ "$i" = 1 ]; then PRIMER_ID=$PEDIDO_ID; fi
done
ULTIMO_ID=$PEDIDO_ID
echo
echo "El slot de replica1 retiene en el primario el WAL que replica1 aún no recibió:"
sql primary postgres "SELECT slot_name, active, wal_status, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS wal_retenido FROM pg_replication_slots ORDER BY slot_name"
sql primary pedidos_db "SELECT 'primary' AS nodo, count(*) AS pedidos_creados_con_replica1_caida FROM pedido WHERE id BETWEEN $PRIMER_ID AND $ULTIMO_ID"

titulo "3. Reinicio: docker compose start replica1"
T_START=$(date -u +%s)
echo "$(hora) docker compose start replica1"
dc start replica1
echo "Lecturas una por segundo hasta que replica1 atienda una (máximo 90 s):"
i=0; primera=""
while [ "$i" -lt 90 ]; do
    i=$((i + 1))
    r=$(http GET /api/pedidos "p08-reinicio-$i")
    sleep 0.3
    n=$(nodo_de "p08-reinicio-$i")
    echo "$(hora) GET /api/pedidos [p08-reinicio-$i] -> $r | $n"
    case "$n" in *node=replica1*) primera=$i; break ;; esac
    sleep 0.7
done
T_PRIMERA=$(date -u +%s)
if [ -n "$primera" ]; then echo "replica1 atendió de nuevo una lectura $((T_PRIMERA - T_START)) s después del start"; fi
esperar_saludable replica1
estado_contenedores

titulo "4. Estado de la replicación"
sql primary postgres "SELECT application_name, state, sync_state, sent_lsn, replay_lsn, replay_lag FROM pg_stat_replication ORDER BY application_name"
sql primary postgres "SELECT slot_name, active, wal_status, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS wal_retenido FROM pg_replication_slots ORDER BY slot_name"
sql replica1 postgres "SELECT pg_is_in_recovery() AS en_recuperacion, pg_last_wal_receive_lsn() AS recibido, pg_last_wal_replay_lsn() AS aplicado, (SELECT status FROM pg_stat_wal_receiver) AS wal_receiver"
echo "replica1 tiene los pedidos creados mientras estaba detenida:"
sql replica1 pedidos_db "SELECT 'replica1' AS nodo, count(*) AS pedidos_creados_con_replica1_caida FROM pedido WHERE id BETWEEN $PRIMER_ID AND $ULTIMO_ID"
recuperados=$(dc exec -T replica1 sh -c 'psql -U "$POSTGRES_USER" -d pedidos_db -X -At -c "SELECT count(*) FROM pedido WHERE id BETWEEN '"$PRIMER_ID"' AND '"$ULTIMO_ID"'"')

titulo "5. Reincorporación (DB health) y reparto posterior: 10 × GET /api/pedidos"
for j in $(seq 1 10); do
    r=$(http GET /api/pedidos "p08-despues-$j")
    echo "$(hora) GET /api/pedidos [p08-despues-$j] -> $r"
done
sleep 2
r1=0; r2=0
for j in $(seq 1 10); do
    n=$(nodo_de "p08-despues-$j")
    echo "p08-despues-$j -> $n"
    case "$n" in *node=replica1*) r1=$((r1 + 1)) ;; *node=replica2*) r2=$((r2 + 1)) ;; esac
done
guardar_logs
echo
grep -E 'DB (health|retry)' "$EVID/logs-servicio-pedidos.txt" || echo "(sin transiciones)"
linea_up=$(grep -m1 'node=replica1 estado=UP' "$EVID/logs-servicio-pedidos.txt" || true)
if [ -n "$linea_up" ]; then
    t_up=$(date -u -d "$(echo "$linea_up" | cut -c1-19)" +%s)
    echo
    echo "replica1 reincorporada a la rotación $((t_up - T_START)) s después de docker compose start"
fi

titulo "Resultado"
echo "Pedidos creados con replica1 caída presentes en replica1: $recuperados de $PEDIDOS"
echo "Reincorporación registrada: $([ -n "$linea_up" ] && echo si || echo no); lecturas posteriores: replica1=$r1 replica2=$r2"
if [ "$recuperados" = "$PEDIDOS" ] && [ -n "$linea_up" ] && [ "$r1" -gt 0 ] && [ "$r2" -gt 0 ]; then
    echo "P08 OK"
else
    echo "P08 FALLA"; exit 1
fi
