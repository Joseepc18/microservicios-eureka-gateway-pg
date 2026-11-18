#!/usr/bin/env bash
# Inicialización del nodo primario. La imagen oficial de PostgreSQL ejecuta este script una
# sola vez, cuando el volumen de datos está vacío (docker-entrypoint-initdb.d). Lo creado aquí
# llega a las réplicas por WAL, porque estas parten de una copia base del primario.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v repl_password="$REPLICATION_PASSWORD" \
  -v productos_password="$PRODUCTOS_DB_PASSWORD" \
  -v pedidos_password="$PEDIDOS_DB_PASSWORD" \
  -v admin_db="$POSTGRES_DB" <<'SQL'
-- ------------------------------------------------------------------ Replicación
SELECT format('CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD %L', :'repl_password') \gexec

-- Un slot físico por réplica: el primario conserva el WAL que cada réplica aún no recibió,
-- con el tope max_slot_wal_keep_size (ver docker-compose.yml y docs/arquitectura.md).
-- immediately_reserve = true: el slot retiene WAL desde ya, no desde la primera conexión. Sin
-- esto, el primer pg_basebackup puede fallar con "requested WAL segment ... has already been removed".
SELECT pg_create_physical_replication_slot('replica1', true);
SELECT pg_create_physical_replication_slot('replica2', true);

-- ------------------------------------------------------------------ Base de datos por servicio
-- Cada servicio tiene su propio rol y su propia base, de la que es dueño (puede crear tablas).
SELECT format('CREATE ROLE productos_app WITH LOGIN PASSWORD %L', :'productos_password') \gexec
SELECT format('CREATE ROLE pedidos_app WITH LOGIN PASSWORD %L', :'pedidos_password') \gexec

CREATE DATABASE productos_db OWNER productos_app;
CREATE DATABASE pedidos_db OWNER pedidos_app;

-- PUBLIC recibe CONNECT y TEMP en toda base nueva. Se retira para que un servicio no pueda
-- conectarse a la base del otro ni a las bases administrativas; el dueño conserva sus permisos.
REVOKE ALL ON DATABASE productos_db FROM PUBLIC;
REVOKE ALL ON DATABASE pedidos_db FROM PUBLIC;
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'admin_db') \gexec
SQL

# Permite las conexiones de replicación del rol replicator desde la red de Docker. La línea
# `host all all all` de la imagen no cubre `replication`, por eso hace falta esta. Se agrega
# solo si no existe, para no duplicarla.
HBA_LINE='host replication replicator all scram-sha-256'
grep -qxF "$HBA_LINE" "$PGDATA/pg_hba.conf" || echo "$HBA_LINE" >> "$PGDATA/pg_hba.conf"
