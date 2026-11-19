# Informe de pruebas experimentales

Cada escenario debe ejecutarse y documentarse con evidencia funcional (salidas de comandos, registros, capturas de resultados). No se aceptan capturas de configuración como sustituto de una prueba funcional. Los fallos se provocan durante las pruebas y se explica el comportamiento observado, incluidos errores transitorios y tiempos de detección.

Responsable de cada escenario indicado entre paréntesis.

## P01 · Descubrimiento dinámico (Integrante 1)

- **Objetivo:** verificar que las instancias se registran en Eureka y pueden localizarse por nombre lógico.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:**
- **Evidencia:**
- **Análisis:**

## P02 · Balanceo HTTP (Integrante 1)

- **Objetivo:** ejecutar al menos 100 solicitudes contra un servicio con dos instancias y registrar qué instancia responde cada una.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** distribución observada, total de respuestas exitosas y errores.
- **Evidencia:**
- **Análisis:**

## P03 · Fallo de instancia (Integrante 1)

- **Objetivo:** detener una instancia y comprobar que el sistema continúa atendiendo solicitudes con la otra.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** solicitudes antes, durante y después del fallo; tiempo observado de recuperación.
- **Evidencia:**
- **Análisis:**

## P04 · Comunicación entre servicios (Integrante 3)

- **Objetivo:** ejecutar una operación de negocio que requiera una llamada real de un microservicio a otro mediante descubrimiento dinámico.
- **Procedimiento:**
- **Resultado esperado:**
- **Resultado obtenido:** trazas de solicitud y respuesta, resultado funcional y manejo de errores.
- **Evidencia:**
- **Análisis:**

## P05 · Enrutamiento de base de datos (Integrante 2)

- **Objetivo:** demostrar que las escrituras usan el primario y las consultas ordinarias se distribuyen entre las dos réplicas.
- **Procedimiento:** con el sistema levantado, ejecutar `scripts/p05-enrutamiento.sh`. El script:
  1. muestra el rol de cada nodo (`pg_is_in_recovery()`) y el estado de `pg_stat_replication`;
  2. crea un producto y 4 pedidos por el Gateway (`POST /api/pedidos`), que son escrituras;
  3. hace 10 lecturas ordinarias `GET /api/pedidos` (`@Transactional(readOnly = true)`);
  4. identifica el nodo de cada solicitud por su `X-Request-Id` en las líneas `DB route` del log de `servicio-pedidos`;
  5. consulta en cada nodo los pedidos creados y las conexiones abiertas del rol `pedidos_app`.
- **Resultado esperado:** las 4 escrituras en `PRIMARY node=primary tx=read-write`; las 10 lecturas en `REPLICA`, alternando `replica1` y `replica2` (5 y 5); los pedidos presentes en los tres nodos.
- **Resultado obtenido:** ejecución del 2026-11-18 a las 23:39:49 UTC.
  - Las 4 escrituras respondieron `201` y las atendió el primario (`DB route=PRIMARY node=primary tx=read-write`).
  - Las 10 lecturas respondieron `200` en 11-14 ms. Se alternaron en orden: `replica1`, `replica2`, `replica1`... (5 y 5).
  - Consultas SQL: los 4 pedidos están en `primary`, `replica1` y `replica2`. `pg_is_in_recovery()` es `f` en el primario y `t` en las réplicas. Cada nodo tiene abiertas 5 conexiones de `pedidos_app`: el módulo crea un pool de HikariCP por nodo, con `maximum-pool-size` = 5, y HikariCP las mantiene abiertas porque `minimum-idle` toma ese mismo valor.

  ```
  p05-escritura-1 -> DB route=PRIMARY node=primary tx=read-write
  p05-lectura-1 -> DB route=REPLICA node=replica1 tx=read-only
  p05-lectura-2 -> DB route=REPLICA node=replica2 tx=read-only
  p05-lectura-3 -> DB route=REPLICA node=replica1 tx=read-only
  ...
  Escrituras atendidas por el primario: 4 de 4
  Lecturas ordinarias: replica1=5 replica2=5 (total 10)
  ```
- **Evidencia:** [salida completa](evidencias/P05/salida.txt) y [log de enrutamiento de servicio-pedidos](evidencias/P05/logs-servicio-pedidos.txt).
- **Análisis:** el servicio no elige el nodo; solo marca sus métodos como `@Transactional` o `@Transactional(readOnly = true)`. La decisión la toma `persistence-routing`: `ReadWriteRoutingDataSource` (un `AbstractRoutingDataSource`) envía las escrituras al primario y las lecturas a `ReplicaPoolDataSource`, que aplica round-robin entre las réplicas disponibles. Cada transacción usa un solo nodo de principio a fin. El log también muestra líneas `DB route=PRIMARY ... tx=none`: son el health check de Actuator, que comprueba solo el primario.

## P06 · Fallo de réplica PostgreSQL (Integrante 2)

- **Objetivo:** detener una réplica y comprobar que las consultas continúan mediante la réplica restante.
- **Procedimiento:** ejecutar `scripts/p06-fallo-replica.sh`. El script:
  1. comprueba que las dos réplicas están en la rotación (4 lecturas previas);
  2. ejecuta `docker compose stop replica1` y muestra `docker compose ps -a`;
  3. hace 30 lecturas `GET /api/pedidos`, una por segundo, y registra el código, el tiempo y el nodo de cada una;
  4. extrae los registros `DB health` y consulta el primario y `replica2` (`pg_stat_replication`, `pg_replication_slots`).

  Deja `replica1` detenida para P08.
- **Resultado esperado:** ninguna lectura falla; tras la caída, todas las lecturas van a `replica2`; el módulo registra la exclusión de `replica1`.
- **Resultado obtenido:** ejecución del 2026-11-18 a las 23:40:11 UTC.
  - `replica1` quedó `Exited (0)` a las 23:40:14.
  - Las **30 lecturas respondieron `200`, sin errores**, y todas las atendió `replica2`.
  - La primera lectura a la que le tocaba `replica1` (`p06-durante-2`) tardó 3,0 s, el `connection-timeout` del pool. En ese momento el módulo excluyó la réplica y atendió la lectura en `replica2`. Las demás tardaron 10-39 ms.
  - El primario y `replica2` siguieron respondiendo consultas SQL. `pg_stat_replication` solo mostró `replica2` y el slot `replica1` quedó `active = f`.

  ```
  23:40:13 docker compose stop replica1
  23:40:18 DB health node=replica1 verificación fallida 1/2: sin conexión: PSQLException: This connection has been closed.
  23:40:19 DB health node=replica1 estado=DOWN motivo="fallo al obtener conexión: UnknownHostException: replica1" -> excluida de la rotación; rotación=[replica2]
  Lecturas con replica1 detenida: 30 correctas, 0 con error; atendidas por replica1: 0
  ```
- **Evidencia:** [salida completa](evidencias/P06/salida.txt) y [log de enrutamiento](evidencias/P06/logs-servicio-pedidos.txt).
- **Análisis:** la caída se detecta por dos vías:
  - **Verificación periódica:** `ReplicaHealthMonitor` comprueba cada 5 s que cada réplica responde, sigue en recuperación y tiene un retraso por debajo de 10 s. Marca la réplica como caída tras 2 fallos seguidos.
  - **Exclusión inmediata:** cuando una lectura no consigue conexión, la réplica sale de la rotación en el acto y la lectura se atiende en otra.

  Aquí actuó primero la segunda, 6 s después del `stop`. El único efecto visible fue una lectura de 3 s, sin error. Si la réplica cae a mitad de una lectura (`FATAL 57P01` en la parada rápida), la transacción de solo lectura se reintenta en otra réplica (`DB retry`). Sin ninguna réplica disponible, las lecturas se degradarían al primario.

## P07 · Consistencia de lectura (Integrante 2)

- **Objetivo:** crear un registro y consultarlo inmediatamente mediante una operación crítica dirigida al primario.
- **Procedimiento:** ejecutar `scripts/p07-consistencia.sh`.
  1. **Escritura y lectura crítica, 20 veces:** crear un pedido (`POST /api/pedidos`) y consultarlo en ese mismo instante con `GET /api/pedidos/{id}`. Esa consulta es la operación crítica de `servicio-pedidos` (`PedidoService.obtener`, con `@ReadFromPrimary`).
  2. **Contraste:** pausar el replay de WAL en las réplicas en marcha (`pg_wal_replay_pause()`) para provocar retraso. Crear un pedido y consultarlo con la lectura crítica y con la ordinaria (`GET /api/pedidos`, en réplica). Después, reanudar el replay (`pg_wal_replay_resume()`) y repetir la lectura ordinaria.

  Se ejecutó después de P06, con `replica1` detenida, así que el replay se pausó solo en `replica2`.
- **Resultado esperado:** la lectura crítica encuentra el pedido las 20 veces y la atiende el primario (`read=critical`). En el contraste, la lectura ordinaria no ve el pedido hasta que la réplica aplica el WAL; la crítica sí lo ve.
- **Resultado obtenido:** ejecución del 2026-11-18 a las 23:41:17 UTC.
  - **Lecturas críticas:** 20 de 20 respondieron `200` con el pedido recién creado. Las 20 las atendió `DB route=PRIMARY node=primary tx=read-only read=critical`.
  - **Contraste con `replica2` pausada:** el pedido 50 se creó a las 23:41:58.
    - La lectura crítica lo devolvió (`200`, en el primario).
    - La lectura ordinaria, atendida por `replica2`, **no lo incluía**. Por SQL, el primario tenía el pedido (`count = 1`) y `replica2` no (`count = 0`).
    - Al reanudar el replay, la misma lectura ordinaria ya lo incluía.

  ```
  p07-crear-1 -> DB route=PRIMARY node=primary tx=read-write | p07-leer-1 -> DB route=PRIMARY node=primary tx=read-only read=critical
  23:41:58 GET /api/pedidos/50 (crítica) [p07-contraste-critica] -> 200
  23:41:58 GET /api/pedidos (ordinaria) [p07-contraste-ordinaria] -> 200, incluye el pedido 50: no
  23:42:01 GET /api/pedidos (ordinaria) [p07-contraste-despues] -> 200, incluye el pedido 50: si
  ```
- **Evidencia:** [salida completa](evidencias/P07/salida.txt) y [log de enrutamiento](evidencias/P07/logs-servicio-pedidos.txt).
- **Análisis:** la replicación es asíncrona, así que una réplica puede no tener todavía un dato recién escrito; el contraste lo muestra. **Política aplicada:**
  - **Lecturas ordinarias:** aceptan consistencia eventual. Las réplicas con más de 10 s de retraso (`max-lag`) salen de la rotación, así que ese retraso queda acotado.
  - **Lecturas que deben ver una escritura anterior:** se declaran críticas y van al primario, que siempre tiene el dato. En el sistema, la única es consultar un pedido por id, que es lo que hace un cliente justo después de crearlo.

  La marca `@ReadFromPrimary` se aplica antes de que la transacción obtenga su conexión, y el log la distingue con `read=critical`.

## P08 · Recuperación de réplica (Integrante 2)

- **Objetivo:** reiniciar la réplica detenida, comprobar su recuperación y verificar que vuelve a participar en las consultas.
- **Procedimiento:** ejecutar `scripts/p08-recuperacion.sh`. Si `replica1` está en marcha, el script la detiene primero. Después:
  1. con `replica1` detenida, crea 5 pedidos y muestra el WAL que retiene su slot en el primario;
  2. ejecuta `docker compose start replica1` y hace lecturas hasta que `replica1` atiende una;
  3. consulta `pg_stat_replication`, `pg_replication_slots` y, en `replica1`, el estado del receptor de WAL y los 5 pedidos;
  4. hace 10 lecturas más y extrae los registros `DB health`.
- **Resultado esperado:** `replica1` recibe por streaming el WAL que se generó mientras estaba detenida (incluidos los 5 pedidos). El módulo la reincorpora solo cuando responde, está en recuperación y su retraso es aceptable. Después vuelve a recibir lecturas, alternándose con `replica2`.
- **Resultado obtenido:** ejecución del 2026-11-18 a las 23:42:07 UTC.
  - Con `replica1` detenida se crearon los pedidos 51-55. Su slot estaba `active = f`, `wal_status = reserved`, con 22 kB de WAL retenido.
  - `docker compose start replica1` a las 23:42:11.
  - A las 23:42:23, **12 s después**, el módulo registró `estado=UP recovery=true lag=0.0s -> reincorporada a la rotación`. Hasta entonces, las lecturas las atendió `replica2`.
  - `replica1` atendió de nuevo una lectura a los 16 s.
  - `pg_stat_replication` mostró las dos réplicas en `streaming`, con `replay_lsn` igual a `sent_lsn`. Los dos slots quedaron `active = t` sin WAL retenido.
  - En `replica1`: `pg_is_in_recovery() = t`, receptor de WAL en `streaming` y **los 5 pedidos presentes**.
  - Las 10 lecturas posteriores se repartieron 5 y 5.

  ```
  23:42:11 docker compose start replica1
  23:42:23 DB health node=replica1 estado=UP recovery=true lag=0.0s -> reincorporada a la rotación; rotación=[replica1, replica2]
   application_name |   state   | sent_lsn  | replay_lsn
   replica1         | streaming | 0/6E1A198 | 0/6E1A198
   replica2         | streaming | 0/6E1A198 | 0/6E1A198
  Pedidos creados con replica1 caída presentes en replica1: 5 de 5
  ```
- **Evidencia:** [salida completa](evidencias/P08/salida.txt) y [log de enrutamiento](evidencias/P08/logs-servicio-pedidos.txt).
- **Análisis:** la réplica se pone al día sola gracias a su slot de replicación. El primario conserva exactamente el WAL que la réplica no confirmó, y al volver lo recibe por streaming. La reincorporación es responsabilidad del módulo, no de Docker: `ReplicaHealthMonitor` solo devuelve la réplica a la rotación tras una verificación apta (conexión, `pg_is_in_recovery() = true` y retraso bajo `max-lag`). Por eso no recibe lecturas mientras arranca. Los 12 s se explican así: el arranque de PostgreSQL, el intervalo de verificación de 5 s y la caché de DNS de Docker. Al volver, el nombre `replica1` se resuelve de nuevo cuando el contenedor tiene red.

## Limitaciones observadas

### Persistencia (P05-P08)

- **Retención de WAL:**
  - Mientras una réplica está detenida, su slot obliga al primario a conservar el WAL que no recibió (22 kB en P08, con 5 pedidos).
  - Con mucha actividad o una caída larga, el WAL crece en el disco del primario. Por eso se limita con `max_slot_wal_keep_size = 1GB`.
  - Si una réplica supera ese tope, su slot pasa a `lost` y hay que reconstruirla (ver [arquitectura](arquitectura.md#retención-de-wal-slots-de-replicación)).
- **Primera lectura tras una caída:** la lectura a la que le toca la réplica caída puede esperar hasta el `connection-timeout` del pool (3 s en P06) antes de excluirla. No falla, pero tarda.
- **Ventana de detección:** entre la caída y la exclusión pasan unos segundos (6 s en P06). Una réplica que vuelve tarda unos 12 s en reincorporarse (P08).
- **Lecturas ordinarias con datos atrasados:** una lectura ordinaria puede no ver una escritura reciente (contraste de P07). El retraso aceptado está acotado por `max-lag` (10 s). Las lecturas que no lo toleran deben ser críticas.
- **Réplica desconectada del primario:** si una réplica sigue en pie pero pierde el streaming, su retraso medido no crece y seguiría en la rotación con datos cada vez más antiguos. En este despliegue solo ocurre si cae el primario.
- **Sin failover:** si cae el primario, las escrituras y las lecturas críticas fallan. No hay promoción automática de una réplica (el enunciado no la exige).
