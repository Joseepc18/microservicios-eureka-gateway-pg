# persistence-routing

Librería compartida que envía las **escrituras al primario** de PostgreSQL y reparte las **lecturas de solo lectura entre las réplicas sanas** (round-robin). Si una réplica cae, sale de la rotación y vuelve cuando está apta; las **lecturas críticas** van al primario. La usan `servicio-productos` y `servicio-pedidos`. Los servicios no contienen ninguna lógica de selección de nodo: solo agregan la dependencia, las propiedades y anotan sus transacciones como hacen normalmente.

## Uso en un servicio

**1. Dependencia** (`pom.xml` del servicio). La versión la gestiona el POM padre. Trae también el driver de PostgreSQL:

```xml
<dependency>
    <groupId>ec.edu.upse.distribuidas</groupId>
    <artifactId>persistence-routing</artifactId>
</dependency>
```

**2. Propiedades.** Con `persistence.routing.primary.url` definida, el módulo se activa y reemplaza al `DataSource` normal de Spring Boot. No hace falta `spring.datasource.*`.

```yaml
persistence:
  routing:
    primary:
      url: jdbc:postgresql://primary:5432/productos_db
      username: productos_app
      password: ${PRODUCTOS_DB_PASSWORD}
    replicas:
      - name: replica1
        url: jdbc:postgresql://replica1:5432/productos_db
      - name: replica2
        url: jdbc:postgresql://replica2:5432/productos_db
```

Las réplicas que no definen `username` ni `password` usan los del primario: es el mismo rol, replicado por WAL.

**3. Transacciones.** Las escrituras van en transacciones normales y las consultas ordinarias en transacciones de solo lectura:

```java
@Service
public class ProductoService {

    private final ProductoRepository repository;

    public ProductoService(ProductoRepository repository) {
        this.repository = repository;
    }

    @Transactional                    // escritura → primario
    public Producto crear(Producto producto) {
        return repository.save(producto);
    }

    @Transactional(readOnly = true)   // lectura ordinaria → réplica (round-robin)
    public List<Producto> listar() {
        return repository.findAll();
    }

    @ReadFromPrimary                  // lectura crítica → primario
    @Transactional(readOnly = true)
    public Producto buscarRecienCreado(Long id) {
        return repository.findById(id).orElseThrow();
    }
}
```

Funciona igual con JPA (Spring Data) y con `JdbcTemplate`.

## Propiedades

| Propiedad | Obligatoria | Por defecto | Descripción |
|---|---|---|---|
| `persistence.routing.primary.url` | **Sí** (activa el módulo) | — | URL JDBC del primario |
| `persistence.routing.primary.username` | Sí | — | Usuario de la base |
| `persistence.routing.primary.password` | Sí | — | Clave del usuario |
| `persistence.routing.primary.name` | No | `primary` | Nombre del nodo en los logs |
| `persistence.routing.replicas[n].url` | Sí, por cada réplica | — | URL JDBC de la réplica `n` (desde 0) |
| `persistence.routing.replicas[n].username` | No | El del primario | Usuario en la réplica |
| `persistence.routing.replicas[n].password` | No | La del primario | Clave en la réplica |
| `persistence.routing.replicas[n].name` | No | `replica1`, `replica2`... | Nombre del nodo en los logs |
| `persistence.routing.pool.maximum-pool-size` | No | `5` | Conexiones máximas por nodo (HikariCP) |
| `persistence.routing.pool.connection-timeout` | No | `3s` | Espera máxima para obtener una conexión de un nodo |
| `persistence.routing.health.enabled` | No | `true` | Verificación periódica de las réplicas |
| `persistence.routing.health.interval` | No | `5s` | Tiempo entre verificaciones |
| `persistence.routing.health.max-lag` | No | `10s` | Retraso de replicación máximo para que una réplica esté apta |
| `persistence.routing.health.failure-threshold` | No | `2` | Verificaciones no aptas seguidas para marcar una réplica como caída |
| `persistence.routing.health.query-timeout` | No | `2s` | Tiempo máximo de la consulta de verificación |
| `persistence.routing.read-retry.max-attempts` | No | `2` | Intentos de una lectura que falla por un error de conexión (`1` = sin reintento) |

Si falta la URL de una réplica declarada, el servicio no arranca y el error dice qué falta. Si no se declara ninguna réplica, el módulo funciona igual y envía también las lecturas al primario, con un aviso en el log.

### Desde variables de entorno (Docker Compose)

Spring Boot traduce cada propiedad a una variable de entorno: mayúsculas, `_` en lugar de `.` y el índice de la lista como un número más. Ejemplo para el bloque de un servicio en `docker-compose.yml`:

```yaml
environment:
  PERSISTENCE_ROUTING_PRIMARY_URL: jdbc:postgresql://primary:5432/productos_db
  PERSISTENCE_ROUTING_PRIMARY_USERNAME: productos_app
  PERSISTENCE_ROUTING_PRIMARY_PASSWORD: ${PRODUCTOS_DB_PASSWORD:?Defina PRODUCTOS_DB_PASSWORD en el archivo .env}
  PERSISTENCE_ROUTING_REPLICAS_0_URL: jdbc:postgresql://replica1:5432/productos_db
  PERSISTENCE_ROUTING_REPLICAS_1_URL: jdbc:postgresql://replica2:5432/productos_db
```

El servicio debe estar en la red `red_datos` y conviene que espere a la base con `depends_on: primary: condition: service_healthy`. Las bases, roles y claves están en [docs/arquitectura.md](../docs/arquitectura.md#base-de-datos-bases-roles-y-replicación).

## Política de enrutamiento

| Situación | Clave | Nodo |
|---|---|---|
| Transacción `@Transactional(readOnly = true)` | `REPLICA` | Una réplica **disponible**, elegida por round-robin |
| Lectura crítica (`@ReadFromPrimary` o `PrimaryRead`) | `PRIMARY` | Primario, aunque sea de solo lectura |
| Transacción de escritura (`@Transactional` sin `readOnly`) | `PRIMARY` | Primario |
| SQL fuera de una transacción | `PRIMARY` | Primario |
| Réplicas configuradas pero ninguna disponible | `REPLICA` | Primario (lectura degradada, con aviso en el log) |
| Sin réplicas configuradas | `PRIMARY` | Primario (también las lecturas) |

**Por qué el primario es el destino por defecto:** ante la duda, el primario siempre tiene el dato más reciente y acepta escrituras. Solo se envía a una réplica lo que se declaró explícitamente de solo lectura.

**Round-robin:** las lecturas se asignan por turnos a las réplicas, en el orden en que están configuradas: `replica1`, `replica2`, `replica1`... Con 10 lecturas seguidas y dos réplicas, cada una atiende 5, alternadas. El contador es atómico, así que el reparto se mantiene con peticiones concurrentes. Cada transacción de solo lectura usa una sola réplica de principio a fin: el turno se decide al obtener la conexión, no en cada consulta.

**`@Transactional(readOnly = true)` sola no reparte nada.** Solo marca la transacción. El reparto lo hace este módulo:

- `ReadWriteRoutingDataSource`, un `AbstractRoutingDataSource` con las claves `PRIMARY` y `REPLICA`, lee ese indicador y elige el destino.
- `ReplicaPoolDataSource` aplica la política entre las réplicas.

**Por qué hace falta `LazyConnectionDataSourceProxy`:** el gestor de transacciones pide la conexión al empezar la transacción, antes de publicar que es de solo lectura. Sin el proxy, la decisión se tomaría demasiado pronto y toda lectura acabaría en el primario (lo comprueba la prueba `withoutTheLazyProxyTheReadOnlyFlagArrivesTooLate`). El proxy entrega una conexión "perezosa" y solo obtiene la física con la primera sentencia SQL, cuando el indicador ya es correcto.

Además, el gestor de transacciones marca la conexión como `readOnly`, así que PostgreSQL rechaza cualquier escritura que se cuele en una transacción de solo lectura (`cannot execute INSERT in a read-only transaction`).

### Estructura

```
DataSource (único bean) = LazyRoutingDataSource   proxy perezoso (LazyConnectionDataSourceProxy)
  └─ ReadWriteRoutingDataSource           PRIMARY / REPLICA según la transacción
       ├─ PRIMARY → DataSourceNode "primary"   → pool HikariCP
       └─ REPLICA → ReplicaPoolDataSource      → ReplicaSelectionPolicy (round-robin)
                      ├─ DataSourceNode "replica1" → pool HikariCP
                      └─ DataSourceNode "replica2" → pool HikariCP
```

Cada nodo tiene su propio pool. Los pools se inician con la primera conexión, así que un nodo caído no impide que el servicio arranque.

El routing no se registra como bean ni se expone con `unwrap`: el único `DataSource` de la aplicación es el proxy. Si Spring Boot encontrara el routing, el health check de Actuator (`/actuator/health`, el que usa Docker Compose) comprobaría cada destino por separado, también `REPLICA` sin transacción, y cada comprobación consumiría turnos del round-robin y dejaría líneas `DB route=REPLICA ... tx=none` en el log. Así, el health check solo comprueba el primario.

## Resiliencia de réplicas

Una réplica está **apta** si responde, sigue en modo recuperación (`pg_is_in_recovery() = true`, es decir, no fue promovida) y su retraso de replicación no supera `max-lag`. El retraso es 0 si la réplica ya aplicó todo el WAL recibido; si no, es el tiempo desde la última transacción aplicada (`now() - pg_last_xact_replay_timestamp()`). Así, un primario sin escrituras no hace parecer retrasada a una réplica al día.

| Paso | Qué hace | Clase |
|---|---|---|
| **Detección** | Cada `interval` se verifica cada réplica con una conexión directa a su pool, sin pasar por el enrutamiento: no consume turnos del round-robin ni deja líneas `DB route`. Tras `failure-threshold` verificaciones no aptas seguidas, la réplica se marca como caída. | `ReplicaHealthMonitor`, `JdbcReplicaProbe` |
| **Exclusión inmediata** | Si una lectura no puede obtener conexión de la réplica que le tocó, esa réplica sale de la rotación en el acto y la lectura se hace en la siguiente disponible. Es el caso de `docker compose stop replica1`: la lectura no falla, aunque espera hasta `connection-timeout` (ver Limitaciones). | `ReplicaPoolDataSource` |
| **Reintento** | Si la réplica cae **a mitad** de una lectura, el método `@Transactional(readOnly = true)` se repite (`read-retry.max-attempts`) en una transacción nueva, que va a otra réplica. Solo se reintentan los errores de conexión (SQLState `08xxx`), no los de la consulta, y solo la transacción más externa. Repetir una lectura no tiene efectos secundarios. | `ReadOnlyRetryInterceptor` |
| **Sin réplicas sanas** | Las lecturas se **degradan al primario**, con un aviso en el log. | `ReplicaPoolDataSource` |
| **Reincorporación** | La primera verificación apta devuelve la réplica a la rotación. Como exige el retraso bajo `max-lag`, una réplica que vuelve tras una parada solo recibe lecturas cuando se puso al día. | `ReplicaHealthMonitor` |

**Por qué degradar al primario y no devolver un error:** el primario siempre tiene el dato más reciente y acepta lecturas, así que el servicio sigue respondiendo correctamente. La carga extra sobre el primario dura solo mientras no hay réplicas, y a la escala del proyecto no lo satura. Un error controlado dejaría sin servicio las consultas aunque la base siga disponible.

### Lecturas críticas y consistencia

La replicación es asíncrona: una lectura en réplica justo después de una escritura puede no ver todavía el dato. **Política:** las lecturas ordinarias aceptan ese pequeño retraso (consistencia eventual, acotada por `max-lag`); una lectura que **debe** ver una escritura anterior se declara crítica y va al primario, que siempre la tiene.

```java
// Con la anotación (en el método transaccional o en la clase)
@ReadFromPrimary
@Transactional(readOnly = true)
public Pedido buscarRecienCreado(Long id) { ... }

// O con el ejecutor, desde fuera de la transacción
Pedido pedido = PrimaryRead.call(() -> pedidoService.buscar(id));
```

La marca debe ponerse **antes** de que la transacción obtenga su conexión: en el método `@Transactional` o alrededor de él, no dentro de una transacción de solo lectura que ya consultó una réplica. Si se hace, la transacción sigue en su réplica y se registra un aviso. `@ReadFromPrimary` funciona, como `@Transactional`, cuando el método se llama a través del bean de Spring.

## Logs (evidencia de P05 a P08)

Cada vez que se obtiene una conexión física se registra una línea con el nodo elegido y el tipo de operación:

```
DB route=PRIMARY node=primary tx=read-write
DB route=REPLICA node=replica1 tx=read-only
DB route=REPLICA node=replica2 tx=read-only
DB route=PRIMARY node=primary tx=none
```

`tx` vale `read-write` (transacción de escritura), `read-only` (transacción de solo lectura) o `none` (sin transacción). Las lecturas críticas añaden `read=critical`: `DB route=PRIMARY node=primary tx=read-only read=critical`. El health check de Actuator también deja líneas `DB route=PRIMARY node=primary tx=none` en cada comprobación. El logger es `ec.edu.upse.distribuidas.persistencerouting.DataSourceNode`, en nivel `INFO`. Al arrancar se registra también la configuración:

```
Enrutamiento de persistencia: primario=primary réplicas=[replica1, replica2] política=round-robin
```

Los cambios de estado de las réplicas usan el prefijo `DB health` y se registran una vez por transición, no en cada verificación:

```
DB health verificando 2 réplicas cada 5 s (retraso máximo 10 s, caída tras 2 fallos)
DB health node=replica1 estado=DOWN motivo="fallo al obtener conexión: ..." -> excluida de la rotación; rotación=[replica2]
DB health node=replica1 verificación fallida 1/2: sin conexión: ...
DB health sin réplicas disponibles: las lecturas se degradan al primario
DB route=REPLICA sin réplicas disponibles: la lectura se degrada al primario
DB health node=replica1 estado=UP recovery=true lag=0.0s -> reincorporada a la rotación; rotación=[replica1, replica2]
DB health las lecturas vuelven a las réplicas
DB retry ProductoService.listar: fallo de conexión en una lectura (...); intento 2/2 en otra réplica
```

Para filtrar un servicio: `docker compose logs servicio-productos-1 | grep -E "DB (route|health|retry)"`.

## Cambiar la política

La política es un bean de tipo `ReplicaSelectionPolicy`. Si un servicio declara el suyo, se usa en lugar del round-robin:

```java
@Bean
ReplicaSelectionPolicy replicaSelectionPolicy() {
    return replicas -> replicas.get(ThreadLocalRandom.current().nextInt(replicas.size())); // aleatoria
}
```

## Limitaciones

- **Primario caído:** no hay promoción automática ni failover (el proyecto no lo exige). Las escrituras y las lecturas críticas fallan mientras el primario no responda.
- **Réplica desconectada del primario:** si una réplica sigue en pie pero pierde la conexión con el primario, deja de recibir WAL sin que su retraso medido crezca, porque ya aplicó todo lo que recibió. Sus lecturas quedarían desactualizadas hasta que se reconecte. En este proyecto solo ocurre si cae el primario.
- **Primera lectura tras una caída:** la lectura a la que le toca la réplica caída espera hasta `connection-timeout` (3 s por defecto) mientras el pool intenta reconectar; después la réplica se excluye y esa lectura se atiende en otra réplica, sin error. Las lecturas siguientes ya no esperan. Se observó así con `docker stop` sobre el clúster real: la lectura tardó unos 3 s. Bajar `connection-timeout` acorta esa espera, a cambio de excluir antes una réplica sana cuando el pool está saturado.
- **Pool agotado:** un tiempo de espera agotado al pedir una conexión también excluye la réplica; vuelve en la siguiente verificación apta.
- **Reintento:** solo cubre la transacción de solo lectura más externa. Una lectura que participa en una transacción ya abierta propaga el error a quien abrió la transacción.

## Pruebas

```bash
./mvnw -pl persistence-routing -am verify
```

Las pruebas no necesitan Docker ni PostgreSQL: usan transacciones reales de Spring con conexiones falsas que registran qué nodo se usó.

| Clase | Qué comprueba |
|---|---|
| `RoundRobinReplicaSelectionPolicyTests` | Alternancia y orden, 3 réplicas, una sola réplica, desbordamiento del contador, reparto 500/500 con 8 hilos |
| `ReadWriteRoutingDataSourceTests` | Escrituras siempre al primario, 10 lecturas alternadas, sin transacción al primario, decisión perezosa, por qué hace falta el proxy, health check solo al primario, routing oculto a `unwrap`, sin réplicas, formato del log |
| `ReplicaResilienceTests` | Réplica detenida excluida al instante y lecturas por la otra, degradación al primario, umbral de fallos, réplica promovida o retrasada fuera de la rotación, reincorporación solo cuando se puso al día, transiciones registradas una vez, lecturas críticas (`PrimaryRead`, `@ReadFromPrimary`, escritura seguida de lectura crítica), reintento en otra réplica y sus límites |
| `PersistenceRoutingAutoConfigurationTests` | Reemplaza el `DataSource` de Spring Boot (un único bean), propiedades y variables de entorno, herencia de credenciales, política propia, health check de Actuator sin réplicas, monitor de réplicas (arranque, sonda JDBC por defecto, desactivable), `@ReadFromPrimary` y reintento aplicados a los beans, inactivo sin URL, error si falta una URL, cierre de los pools |

> **Fuera de Docker:** si en el equipo hay un PostgreSQL instalado escuchando en el puerto `5432`, las conexiones a `127.0.0.1:5432` pueden llegar a ese servidor en lugar de al contenedor `primary`. Dentro de Docker Compose (`primary:5432`) no ocurre.
