#!/usr/bin/env bash
# P05 · Enrutamiento de base de datos.
# Las escrituras de servicio-pedidos van al primario y las lecturas ordinarias (GET /pedidos)
# se reparten entre replica1 y replica2. Evidencia en docs/evidencias/P05.
#
# Requiere el sistema levantado (docker compose up -d --build --wait).
# Uso: scripts/p05-enrutamiento.sh [escrituras] [lecturas]

. "$(dirname "$0")/lib/comun.sh"
ESCRITURAS=${1:-4}
LECTURAS=${2:-10}
iniciar_evidencia P05 "Enrutamiento de base de datos"

titulo "1. Estado inicial"
estado_contenedores
echo
echo "Rol de cada nodo (el primario no está en recuperación; las réplicas sí):"
for nodo in primary replica1 replica2; do
    sql "$nodo" pedidos_db "SELECT '$nodo' AS nodo, pg_is_in_recovery() AS en_recuperacion, pg_postmaster_start_time() AS iniciado"
done
sql primary postgres "SELECT application_name, state, sync_state FROM pg_stat_replication ORDER BY application_name"


titulo "2. Escrituras: $ESCRITURAS pedidos (POST /api/pedidos por el Gateway)"
crear_producto p05
for i in $(seq 1 "$ESCRITURAS"); do
    crear_pedido "p05-escritura-$i"
done

sleep 1
titulo "3. Lecturas ordinarias: $LECTURAS × GET /api/pedidos"
for i in $(seq 1 "$LECTURAS"); do
    r=$(http GET /api/pedidos "p05-lectura-$i")
    echo "$(hora) GET /api/pedidos [p05-lectura-$i] -> $r, pedidos=$(grep -o '"id":' "$CUERPO" | wc -l)"
done

sleep 2
titulo "4. Nodo que atendió cada solicitud (log DB route de servicio-pedidos, por X-Request-Id)"
escr_primario=0
for i in $(seq 1 "$ESCRITURAS"); do
    n=$(nodo_de "p05-escritura-$i")
    echo "p05-escritura-$i -> $n"
    case "$n" in *"route=PRIMARY node=primary tx=read-write"*) escr_primario=$((escr_primario + 1)) ;; esac
done
r1=0; r2=0
for i in $(seq 1 "$LECTURAS"); do
    n=$(nodo_de "p05-lectura-$i")
    echo "p05-lectura-$i -> $n"
    case "$n" in
        *"node=replica1 tx=read-only"*) r1=$((r1 + 1)) ;;
        *"node=replica2 tx=read-only"*) r2=$((r2 + 1)) ;;
    esac
done

titulo "5. Consultas SQL en cada nodo"
echo "Los $ESCRITURAS pedidos nuevos están en los tres nodos (replicados desde el primario):"
for nodo in primary replica1 replica2; do
    sql "$nodo" pedidos_db "SELECT '$nodo' AS nodo, count(*) AS pedidos_de_p05 FROM pedido WHERE producto_id = $PRODUCTO_ID"
done
echo "Conexiones del rol pedidos_app abiertas en cada nodo (pools de HikariCP del módulo):"
for nodo in primary replica1 replica2; do
    sql "$nodo" postgres "SELECT '$nodo' AS nodo, count(*) AS conexiones FROM pg_stat_activity WHERE usename = 'pedidos_app'"
done

guardar_logs

titulo "Resultado"
echo "Escrituras atendidas por el primario: $escr_primario de $ESCRITURAS"
echo "Lecturas ordinarias: replica1=$r1 replica2=$r2 (total $LECTURAS)"
if [ "$escr_primario" = "$ESCRITURAS" ] && [ "$r1" -gt 0 ] && [ "$r2" -gt 0 ] && [ $((r1 + r2)) = "$LECTURAS" ]; then
    echo "P05 OK"
else
    echo "P05 FALLA"; exit 1
fi
