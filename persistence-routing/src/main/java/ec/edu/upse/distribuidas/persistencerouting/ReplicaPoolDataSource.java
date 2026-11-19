package ec.edu.upse.distribuidas.persistencerouting;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Destino de la clave {@link DataSourceRole#REPLICA}: reparte cada conexión entre las réplicas
 * <b>disponibles</b> según la {@link ReplicaSelectionPolicy} configurada.
 *
 * <ul>
 *   <li><b>Exclusión:</b> una réplica marcada como caída sale de la rotación. Si no se puede
 *       obtener una conexión de la réplica elegida porque el nodo no responde, se marca como
 *       caída en el acto y se prueba con la siguiente disponible.</li>
 *   <li><b>Pool agotado:</b> si la réplica responde pero todas las conexiones de su pool están
 *       en uso, no se excluye: se prueba con otra réplica. Si todas las disponibles están
 *       agotadas, la lectura falla en lugar de cargar al primario.</li>
 *   <li><b>Sin réplicas disponibles:</b> la lectura se degrada al primario ({@code fallback}),
 *       con un aviso en el log. El primario siempre tiene el dato más reciente y acepta lecturas,
 *       así que el servicio sigue respondiendo; la carga extra es temporal y a esta escala no lo
 *       satura.</li>
 *   <li><b>Reincorporación:</b> la hace {@link ReplicaHealthMonitor} con
 *       {@link #markUp(DataSourceNode, String)} cuando la réplica vuelve a estar apta.</li>
 * </ul>
 *
 * <p>Los cambios de estado se registran con el prefijo {@code DB health} (evidencia de P06 y
 * P08).
 */
public class ReplicaPoolDataSource extends AbstractDataSource {

    private static final Logger log = LoggerFactory.getLogger(ReplicaPoolDataSource.class);

    private final List<DataSourceNode> replicas;
    private final ReplicaSelectionPolicy policy;
    private final DataSource fallback;
    private volatile boolean excludeOnConnectionFailure = true;

    /** Réplicas sin degradación: si no queda ninguna disponible, la conexión falla. */
    public ReplicaPoolDataSource(List<DataSourceNode> replicas, ReplicaSelectionPolicy policy) {
        this(replicas, policy, null);
    }

    /**
     * @param fallback destino cuando no queda ninguna réplica disponible (el primario), o
     *                 {@code null} para fallar con una {@link SQLException}
     */
    public ReplicaPoolDataSource(List<DataSourceNode> replicas, ReplicaSelectionPolicy policy, DataSource fallback) {
        if (replicas.isEmpty()) {
            throw new IllegalArgumentException("El conjunto de réplicas no puede estar vacío");
        }
        this.replicas = List.copyOf(replicas);
        this.policy = policy;
        this.fallback = fallback;
    }

    public List<DataSourceNode> getReplicas() {
        return replicas;
    }

    /** Réplicas que están en la rotación, en el orden configurado. */
    public List<DataSourceNode> getAvailableReplicas() {
        return replicas.stream().filter(DataSourceNode::isAvailable).toList();
    }

    public ReplicaSelectionPolicy getPolicy() {
        return policy;
    }

    /**
     * Si un fallo al obtener una conexión excluye la réplica en el acto. Se desactiva cuando no
     * hay monitor de salud, porque nadie la reincorporaría.
     */
    public void setExcludeOnConnectionFailure(boolean excludeOnConnectionFailure) {
        this.excludeOnConnectionFailure = excludeOnConnectionFailure;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return acquire(null, null);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return acquire(username, password);
    }

    private Connection acquire(String username, String password) throws SQLException {
        SQLException lastFailure = null;
        SQLException exhausted = null;
        // Como mucho un intento por réplica en cada solicitud.
        Set<DataSourceNode> tried = new HashSet<>();
        while (true) {
            List<DataSourceNode> candidates = getAvailableReplicas().stream()
                    .filter(replica -> !tried.contains(replica))
                    .toList();
            if (candidates.isEmpty()) {
                break;
            }
            DataSourceNode replica = policy.select(candidates);
            tried.add(replica);
            try {
                return username == null ? replica.getConnection() : replica.getConnection(username, password);
            } catch (SQLException | RuntimeException ex) {
                // HikariCP lanza PoolInitializationException (RuntimeException) si el pool no
                // llegó a iniciarse porque el nodo no responde.
                lastFailure = ex instanceof SQLException sql ? sql : new SQLException(ex.getMessage(), "08001", ex);
                if (ConnectionFailures.isPoolExhausted(ex)) {
                    // La réplica responde pero tiene todas sus conexiones en uso: no está caída.
                    // Se prueba con otra réplica sin sacarla de la rotación.
                    exhausted = lastFailure;
                    log.warn("DB route=REPLICA node={} pool agotado ({}): se prueba otra réplica sin excluirla",
                            replica.getName(), ConnectionFailures.describe(ex));
                    continue;
                }
                if (!excludeOnConnectionFailure) {
                    throw lastFailure;
                }
                markDown(replica, "fallo al obtener conexión: " + ConnectionFailures.describe(ex));
            }
        }
        if (exhausted != null && !getAvailableReplicas().isEmpty()) {
            // Las réplicas disponibles están ocupadas, no caídas. Degradar al primario le pasaría
            // toda la carga de lectura justo cuando hay más demanda: la lectura falla y no se
            // reintenta (ReadOnlyRetryInterceptor reconoce el pool agotado en la causa).
            throw new SQLTransientConnectionException("No se obtuvo conexión: pools de las réplicas agotados "
                    + names(getAvailableReplicas()), "08001", exhausted);
        }
        if (fallback == null) {
            SQLException ex = new SQLException("No hay réplicas disponibles", "08001");
            if (lastFailure != null) {
                ex.addSuppressed(lastFailure);
            }
            throw ex;
        }
        log.warn("DB route=REPLICA sin réplicas disponibles: la lectura se degrada al primario");
        return username == null ? fallback.getConnection() : fallback.getConnection(username, password);
    }

    /**
     * Saca la réplica de la rotación. Registra la transición solo si estaba disponible.
     *
     * @return {@code true} si la réplica estaba disponible
     */
    public synchronized boolean markDown(DataSourceNode replica, String reason) {
        if (!replica.setAvailable(false)) {
            return false;
        }
        List<DataSourceNode> available = getAvailableReplicas();
        log.warn("DB health node={} estado=DOWN motivo=\"{}\" -> excluida de la rotación; rotación={}",
                replica.getName(), reason, names(available));
        if (available.isEmpty()) {
            log.warn("DB health sin réplicas disponibles: las lecturas se degradan al primario");
        }
        return true;
    }

    /**
     * Devuelve la réplica a la rotación. Registra la transición solo si estaba caída.
     *
     * @return {@code true} si la réplica estaba caída
     */
    public synchronized boolean markUp(DataSourceNode replica, String detail) {
        boolean noneBefore = getAvailableReplicas().isEmpty();
        if (!replica.setAvailable(true)) {
            return false;
        }
        log.info("DB health node={} estado=UP {} -> reincorporada a la rotación; rotación={}",
                replica.getName(), detail, names(getAvailableReplicas()));
        if (noneBefore) {
            log.info("DB health las lecturas vuelven a las réplicas");
        }
        return true;
    }

    private static List<String> names(List<DataSourceNode> nodes) {
        return nodes.stream().map(DataSourceNode::getName).toList();
    }
}
