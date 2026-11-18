package ec.edu.upse.distribuidas.persistencerouting;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Un nodo PostgreSQL concreto (el primario o una réplica) con nombre y rol.
 *
 * <p>Cada vez que se obtiene una conexión física de este nodo se registra la decisión de
 * enrutamiento, con este formato (evidencia de P05):
 *
 * <pre>DB route=REPLICA node=replica2 tx=read-only</pre>
 *
 * <p>{@code tx} es el tipo de operación: {@code read-only} (transacción de solo lectura),
 * {@code read-write} (transacción de escritura) o {@code none} (sin transacción). Las lecturas
 * críticas ({@link PrimaryRead}, {@link ReadFromPrimary}) añaden {@code read=critical}.
 *
 * <p>Las réplicas guardan además su disponibilidad: {@link ReplicaPoolDataSource} solo reparte
 * entre las disponibles y {@link ReplicaHealthMonitor} las marca como caídas o reincorporadas.
 */
public class DataSourceNode extends DelegatingDataSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DataSourceNode.class);

    private final String name;
    private final DataSourceRole role;

    private volatile boolean available = true;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public DataSourceNode(String name, DataSourceRole role, DataSource target) {
        super(target);
        this.name = name;
        this.role = role;
    }

    public String getName() {
        return name;
    }

    public DataSourceRole getRole() {
        return role;
    }

    /** Si el nodo está en la rotación de lecturas. Solo cambia en las réplicas. */
    public boolean isAvailable() {
        return available;
    }

    /** Cambia la disponibilidad. Devuelve {@code true} si el valor cambió. */
    boolean setAvailable(boolean available) {
        boolean changed = this.available != available;
        this.available = available;
        return changed;
    }

    /** Verificaciones fallidas seguidas desde la última correcta. */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    int recordFailure() {
        return consecutiveFailures.incrementAndGet();
    }

    void resetFailures() {
        consecutiveFailures.set(0);
    }

    /** Conexión directa al pool del nodo, sin registrar una decisión de enrutamiento. */
    Connection getConnectionWithoutRouting() throws SQLException {
        DataSource target = getTargetDataSource();
        if (target == null) {
            throw new SQLException("El nodo " + name + " no tiene DataSource");
        }
        return target.getConnection();
    }

    @Override
    public Connection getConnection() throws SQLException {
        logDecision();
        return super.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        logDecision();
        return super.getConnection(username, password);
    }

    private void logDecision() {
        log.info("DB route={} node={} tx={}{}", role, name, currentOperation(),
                PrimaryRead.isActive() ? " read=critical" : "");
    }

    static String currentOperation() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return "none";
        }
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "read-only" : "read-write";
    }

    /** Cierra el pool de conexiones del nodo, si lo tiene. */
    @Override
    public void close() throws Exception {
        if (getTargetDataSource() instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    @Override
    public String toString() {
        return name + "(" + role + ")";
    }
}
