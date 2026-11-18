#!/usr/bin/env bash
# P06 · Fallo de réplica PostgreSQL.
# Detiene replica1 con docker compose stop mientras servicio-pedidos atiende lecturas y comprueba
# que las consultas continúan por replica2. Evidencia en docs/evidencias/P06.
#
# Deja replica1 detenida: P08 (scripts/p08-recuperacion.sh) la reinicia.
# Uso: scripts/p06-fallo-replica.sh [lecturas_con_la_replica_caida]

. "$(dirname "$0")/lib/comun.sh"
LECTURAS=${1:-30}
iniciar_evidencia P06 "Fallo de réplica PostgreSQL"

titulo "1. Estado inicial"
if [ "$(dc ps --format '{{.Health}}' replica1)" != healthy ]; then
    echo "replica1 no está healthy; se inicia antes de la prueba"
    dc start replica1 >/dev/null
    esperar_saludable replica1
    sleep 6   # una verificación de salud del módulo para que vuelva a la rotación
fi
estado_contenedores
sql primary postgres "SELECT application_name, state FROM pg_stat_replication ORDER BY application_name"
echo "Lecturas antes del fallo:"
for i in 1 2 3 4; do
    r=$(http GET /api/pedidos "p06-antes-$i")
    echo "$(hora) GET /api/pedidos [p06-antes-$i] -> $r"
done

titulo "2. Fallo: docker compose stop replica1"
T_STOP=$(date -u +%s)
echo "$(hora) docker compose stop replica1"
dc stop replica1
echo "$(hora) replica1 detenida"
estado_contenedores

titulo "3. Lecturas con la réplica detenida: $LECTURAS × GET /api/pedidos, una por segundo"
ok=0; errores=0
for i in $(seq 1 "$LECTURAS"); do
    r=$(http GET /api/pedidos "p06-durante-$i")
    echo "$(hora) GET /api/pedidos [p06-durante-$i] -> $r"
    if [ "${r%% *}" = 200 ]; then ok=$((ok + 1)); else errores=$((errores + 1)); fi
    sleep 1
done

sleep 2
titulo "4. Nodo que atendió cada lectura (log DB route)"
for i in 1 2 3 4; do echo "p06-antes-$i -> $(nodo_de "p06-antes-$i")"; done
en_replica1=0
for i in $(seq 1 "$LECTURAS"); do
    n=$(nodo_de "p06-durante-$i")
    echo "p06-durante-$i -> $n"
    case "$n" in *node=replica1*) en_replica1=$((en_replica1 + 1)) ;; esac
done

titulo "5. Registros del mecanismo de selección (DB health)"
guardar_logs
grep -E 'DB (health|retry)' "$EVID/logs-servicio-pedidos.txt" || echo "(sin transiciones)"
linea_down=$(grep -m1 'node=replica1 estado=DOWN' "$EVID/logs-servicio-pedidos.txt" || true)
if [ -n "$linea_down" ]; then
    t_down=$(date -u -d "$(echo "$linea_down" | cut -c1-19)" +%s)
    echo
    echo "replica1 excluida de la rotación $((t_down - T_STOP)) s después de docker compose stop"
fi

titulo "6. Consultas SQL: el primario y replica2 siguen atendiendo"
for nodo in primary replica2; do
    sql "$nodo" pedidos_db "SELECT '$nodo' AS nodo, pg_is_in_recovery() AS en_recuperacion, count(*) AS pedidos FROM pedido"
done
echo "pg_stat_replication en el primario (replica1 ya no aparece):"
sql primary postgres "SELECT application_name, state FROM pg_stat_replication ORDER BY application_name"
sql primary postgres "SELECT slot_name, active, wal_status FROM pg_replication_slots ORDER BY slot_name"

titulo "Resultado"
echo "Lecturas con replica1 detenida: $ok correctas, $errores con error; atendidas por replica1: $en_replica1"
estado_contenedores
if [ "$errores" = 0 ] && [ "$en_replica1" = 0 ]; then echo "P06 OK"; else echo "P06 FALLA"; exit 1; fi
