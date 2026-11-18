#!/usr/bin/env bash
# Arranque de una réplica. Si el volumen está vacío, copia el primario con pg_basebackup;
# -R deja configurado el modo standby (primary_conninfo y primary_slot_name). Después
# arranca PostgreSQL como servidor de solo lectura.
#
# Variables: PGDATA, PGPASSWORD (clave de replicator) y REPLICATION_SLOT (slot creado en el
# primario por primary-init.sh; también se usa como application_name en pg_stat_replication).
set -euo pipefail
: "${REPLICATION_SLOT:?Defina REPLICATION_SLOT (replica1 o replica2)}"

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  mkdir -p "$PGDATA"
  chmod 700 "$PGDATA"
  echo "Esperando al primario y creando copia base (slot ${REPLICATION_SLOT})..."
  until pg_basebackup \
      -d "host=primary port=5432 user=replicator application_name=${REPLICATION_SLOT}" \
      -D "$PGDATA" -Fp -Xs -R -P -S "$REPLICATION_SLOT"; do
    echo 'Copia fallida; reintentando en 3 segundos...'
    rm -rf "${PGDATA:?}"/*
    sleep 3
  done
fi
exec postgres -D "$PGDATA" -c hot_standby=on
