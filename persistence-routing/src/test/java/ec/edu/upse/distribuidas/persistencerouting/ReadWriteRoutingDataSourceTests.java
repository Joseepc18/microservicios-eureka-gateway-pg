package ec.edu.upse.distribuidas.persistencerouting;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enrutamiento completo, tal como lo arma la autoconfiguración: proxy perezoso → routing →
 * primario o réplicas. Las transacciones son reales (DataSourceTransactionManager); solo las
 * conexiones son falsas.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReadWriteRoutingDataSourceTests {

    private final List<String> used = Collections.synchronizedList(new ArrayList<>());

    private DataSource dataSource;
    private TransactionTemplate writeTx;
    private TransactionTemplate readOnlyTx;

    private static DataSourceNode node(String name, DataSourceRole role, List<String> used) {
        return new DataSourceNode(name, role, new RecordingDataSource(name, used));
    }

    private static DataSource lazy(DataSource target) {
        LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy();
        proxy.setTargetDataSource(target);
        proxy.setDefaultAutoCommit(true);
        proxy.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        proxy.afterPropertiesSet();
        return proxy;
    }

    @BeforeEach
    void setUp() {
        ReplicaPoolDataSource replicas = new ReplicaPoolDataSource(
                List.of(node("replica1", DataSourceRole.REPLICA, used), node("replica2", DataSourceRole.REPLICA, used)),
                new RoundRobinReplicaSelectionPolicy());
        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource(
                node("primary", DataSourceRole.PRIMARY, used), replicas);
        dataSource = lazy(routing);
        configureTransactions(dataSource);
        used.clear();
    }

    private void configureTransactions(DataSource ds) {
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(ds);
        writeTx = new TransactionTemplate(txManager);
        readOnlyTx = new TransactionTemplate(txManager);
        readOnlyTx.setReadOnly(true);
    }

    /** Ejecuta una sentencia dentro de la transacción en curso, como haría un repositorio. */
    private void executeStatement() {
        try {
            RecordingDataSource.touch(DataSourceUtils.getConnection(dataSource));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aWriteTransactionAlwaysUsesThePrimary() {
        for (int i = 0; i < 10; i++) {
            writeTx.executeWithoutResult(status -> executeStatement());
        }

        assertThat(used).hasSize(10).containsOnly("primary");
    }

    @Test
    void tenConsecutiveReadOnlyTransactionsAlternateBetweenTheTwoReplicas() {
        for (int i = 0; i < 10; i++) {
            readOnlyTx.executeWithoutResult(status -> executeStatement());
        }

        assertThat(used).containsExactly("replica1", "replica2", "replica1", "replica2", "replica1",
                "replica2", "replica1", "replica2", "replica1", "replica2");
    }

    @Test
    void writesAndReadsInterleavedKeepTheirOwnDestination() {
        writeTx.executeWithoutResult(status -> executeStatement());
        readOnlyTx.executeWithoutResult(status -> executeStatement());
        writeTx.executeWithoutResult(status -> executeStatement());
        readOnlyTx.executeWithoutResult(status -> executeStatement());

        assertThat(used).containsExactly("primary", "replica1", "primary", "replica2");
    }

    @Test
    void sqlOutsideATransactionGoesToThePrimary() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            RecordingDataSource.touch(connection);
        }

        assertThat(used).containsExactly("primary");
    }

    @Test
    void theDecisionWaitsForTheFirstStatement() {
        // Sin sentencias no se toma ninguna conexión física: es lo que permite decidir con el
        // indicador readOnly ya publicado por el gestor de transacciones.
        readOnlyTx.executeWithoutResult(status -> DataSourceUtils.getConnection(dataSource));

        assertThat(used).isEmpty();
    }

    @Test
    void withoutTheLazyProxyTheReadOnlyFlagArrivesTooLate() {
        // Justifica el LazyConnectionDataSourceProxy: sin él, el gestor obtiene la conexión al
        // empezar la transacción, antes de marcarla como de solo lectura, y la lectura acaba en
        // el primario.
        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource(node("primary", DataSourceRole.PRIMARY, used),
                new ReplicaPoolDataSource(List.of(node("replica1", DataSourceRole.REPLICA, used)),
                        new RoundRobinReplicaSelectionPolicy()));
        dataSource = routing;
        configureTransactions(routing);

        readOnlyTx.executeWithoutResult(status -> executeStatement());

        assertThat(used).containsExactly("primary");
    }

    @Test
    void withoutReplicasReadsAlsoGoToThePrimary() {
        dataSource = lazy(new ReadWriteRoutingDataSource(node("primary", DataSourceRole.PRIMARY, used), null));
        configureTransactions(dataSource);

        readOnlyTx.executeWithoutResult(status -> executeStatement());

        assertThat(used).containsExactly("primary");
    }

    @Test
    void logsEachDecisionWithNodeAndOperationType(CapturedOutput output) throws Exception {
        writeTx.executeWithoutResult(status -> executeStatement());
        readOnlyTx.executeWithoutResult(status -> executeStatement());
        readOnlyTx.executeWithoutResult(status -> executeStatement());
        try (Connection connection = dataSource.getConnection()) {
            RecordingDataSource.touch(connection);
        }

        assertThat(output.getOut())
                .contains("DB route=PRIMARY node=primary tx=read-write")
                .contains("DB route=REPLICA node=replica1 tx=read-only")
                .contains("DB route=REPLICA node=replica2 tx=read-only")
                .contains("DB route=PRIMARY node=primary tx=none");
    }
}
