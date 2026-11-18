package ec.edu.upse.distribuidas.persistencerouting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Enrutamiento de lecturas y escrituras.
 *
 * <ul>
 *   <li>Transacción de solo lectura ({@code @Transactional(readOnly = true)}) → clave
 *       {@link DataSourceRole#REPLICA}, que reparte entre las réplicas con la política
 *       configurada.</li>
 *   <li>Lectura crítica ({@link PrimaryRead}, {@link ReadFromPrimary}) → clave
 *       {@link DataSourceRole#PRIMARY}, aunque la transacción sea de solo lectura.</li>
 *   <li>Cualquier otro caso (transacción de escritura o sin transacción) → clave
 *       {@link DataSourceRole#PRIMARY}.</li>
 *   <li>Si no hay réplicas configuradas, todo va al primario. Si las hay pero ninguna está
 *       disponible, {@link ReplicaPoolDataSource} degrada la lectura al primario.</li>
 * </ul>
 *
 * <p>Debe usarse detrás de un {@code LazyConnectionDataSourceProxy}: el gestor de transacciones
 * pide la conexión al empezar la transacción, antes de publicar que es de solo lectura. El proxy
 * retrasa la obtención de la conexión física hasta la primera sentencia SQL, cuando
 * {@link TransactionSynchronizationManager#isCurrentTransactionReadOnly()} ya tiene el valor
 * correcto.
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ReadWriteRoutingDataSource.class);

    private final DataSourceNode primary;
    private final ReplicaPoolDataSource replicaPool;

    /**
     * @param primary     nodo primario
     * @param replicaPool réplicas con su política, o {@code null} si no hay réplicas
     */
    public ReadWriteRoutingDataSource(DataSourceNode primary, ReplicaPoolDataSource replicaPool) {
        this.primary = primary;
        this.replicaPool = replicaPool;

        Map<Object, Object> targets = new HashMap<>();
        targets.put(DataSourceRole.PRIMARY, primary);
        if (replicaPool != null) {
            targets.put(DataSourceRole.REPLICA, replicaPool);
        }
        setTargetDataSources(targets);
        setDefaultTargetDataSource(primary);
        // Una clave desconocida es un error de programación: no se cae al primario en silencio.
        setLenientFallback(false);
        afterPropertiesSet();

        if (replicaPool == null) {
            log.warn("Enrutamiento de persistencia: no hay réplicas configuradas; "
                    + "las lecturas también irán al primario {}", primary.getName());
        } else {
            log.info("Enrutamiento de persistencia: primario={} réplicas={} política={}",
                    primary.getName(), getReplicas().stream().map(DataSourceNode::getName).toList(),
                    replicaPool.getPolicy().name());
        }
    }

    @Override
    protected Object determineCurrentLookupKey() {
        boolean readOnly = TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return readOnly && replicaPool != null && !PrimaryRead.isActive() ? DataSourceRole.REPLICA
                : DataSourceRole.PRIMARY;
    }

    public DataSourceNode getPrimary() {
        return primary;
    }

    /** Réplicas con su política y su estado, o {@code null} si no hay réplicas. */
    public ReplicaPoolDataSource getReplicaPool() {
        return replicaPool;
    }

    public List<DataSourceNode> getReplicas() {
        return replicaPool == null ? List.of() : replicaPool.getReplicas();
    }

    public ReplicaSelectionPolicy getPolicy() {
        return replicaPool == null ? null : replicaPool.getPolicy();
    }

    /** Cierra los pools de conexiones de todos los nodos al apagar la aplicación. */
    @Override
    public void destroy() throws Exception {
        List<DataSourceNode> nodes = new ArrayList<>();
        nodes.add(primary);
        nodes.addAll(getReplicas());
        for (DataSourceNode node : nodes) {
            node.close();
        }
    }
}
