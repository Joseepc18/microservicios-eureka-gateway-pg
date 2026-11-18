# persistence-routing

Librería compartida que envía las **escrituras al primario** de PostgreSQL y reparte las **lecturas de solo lectura entre las réplicas** (round-robin). La usan `servicio-productos` y `servicio-pedidos`. Los servicios no contienen ninguna lógica de selección de nodo: solo agregan la dependencia, las propiedades y anotan sus transacciones como hacen normalmente.

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
| Transacción `@Transactional(readOnly = true)` | `REPLICA` | Una réplica, elegida por round-robin |
| Transacción de escritura (`@Transactional` sin `readOnly`) | `PRIMARY` | Primario |
| SQL fuera de una transacción | `PRIMARY` | Primario |
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
DataSource (bean principal) = LazyConnectionDataSourceProxy
  └─ ReadWriteRoutingDataSource           PRIMARY / REPLICA según la transacción
       ├─ PRIMARY → DataSourceNode "primary"   → pool HikariCP
       └─ REPLICA → ReplicaPoolDataSource      → ReplicaSelectionPolicy (round-robin)
                      ├─ DataSourceNode "replica1" → pool HikariCP
                      └─ DataSourceNode "replica2" → pool HikariCP
```

Cada nodo tiene su propio pool. Los pools se inician con la primera conexión, así que un nodo caído no impide que el servicio arranque.

## Logs (evidencia de P05)

Cada vez que se obtiene una conexión física se registra una línea con el nodo elegido y el tipo de operación:

```
DB route=PRIMARY node=primary tx=read-write
DB route=REPLICA node=replica1 tx=read-only
DB route=REPLICA node=replica2 tx=read-only
DB route=PRIMARY node=primary tx=none
```

`tx` vale `read-write` (transacción de escritura), `read-only` (transacción de solo lectura) o `none` (sin transacción). El logger es `ec.edu.upse.distribuidas.persistencerouting.DataSourceNode`, en nivel `INFO`. Al arrancar se registra también la configuración:

```
Enrutamiento de persistencia: primario=primary réplicas=[replica1, replica2] política=round-robin
```

Para filtrar las decisiones de un servicio: `docker compose logs servicio-productos-1 | grep "DB route"`.

## Cambiar la política

La política es un bean de tipo `ReplicaSelectionPolicy`. Si un servicio declara el suyo, se usa en lugar del round-robin:

```java
@Bean
ReplicaSelectionPolicy replicaSelectionPolicy() {
    return replicas -> replicas.get(ThreadLocalRandom.current().nextInt(replicas.size())); // aleatoria
}
```

## Limitaciones actuales

- **Réplica caída:** este módulo todavía no comprueba la salud de las réplicas, así que una lectura asignada a una réplica detenida falla. Con `docker compose stop replica1` falla de inmediato, porque su nombre deja de resolverse (`UnknownHostException: replica1`). Si el nodo existe pero no responde, la espera está acotada por `connection-timeout`. La detección, exclusión y reincorporación de réplicas llega en la issue #9.
- **Retraso de replicación:** la replicación es asíncrona, así que una lectura en réplica justo después de una escritura puede no ver el dato todavía. Las lecturas críticas dirigidas al primario llegan en la issue #9. Mientras tanto, si una lectura necesita el dato recién escrito, debe hacerse dentro de una transacción de escritura, que va al primario.

## Pruebas

```bash
./mvnw -pl persistence-routing -am verify
```

Las pruebas no necesitan Docker ni PostgreSQL: usan transacciones reales de Spring con conexiones falsas que registran qué nodo se usó.

| Clase | Qué comprueba |
|---|---|
| `RoundRobinReplicaSelectionPolicyTests` | Alternancia y orden, 3 réplicas, una sola réplica, desbordamiento del contador, reparto 500/500 con 8 hilos |
| `ReadWriteRoutingDataSourceTests` | Escrituras siempre al primario, 10 lecturas alternadas, sin transacción al primario, decisión perezosa, por qué hace falta el proxy, sin réplicas, formato del log |
| `PersistenceRoutingAutoConfigurationTests` | Reemplaza el `DataSource` de Spring Boot, propiedades y variables de entorno, herencia de credenciales, política propia, inactivo sin URL, error si falta una URL, cierre de los pools |

> **Fuera de Docker:** si en el equipo hay un PostgreSQL instalado escuchando en el puerto `5432`, las conexiones a `127.0.0.1:5432` pueden llegar a ese servidor en lugar de al contenedor `primary`. Dentro de Docker Compose (`primary:5432`) no ocurre.
