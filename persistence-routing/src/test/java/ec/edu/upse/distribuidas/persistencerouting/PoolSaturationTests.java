package ec.edu.upse.distribuidas.persistencerouting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Un pool de conexiones agotado no es una réplica caída.
 *
 * <p>Con HikariCP real (sobre un {@code DataSource} falso): cuando todas las conexiones del pool
 * de una réplica están en uso, la espera termina con {@link SQLTransientConnectionException} sin
 * causa. La réplica responde, así que no debe salir de la rotación; la lectura se atiende en otra
 * réplica. Si el nodo no responde, la excepción de HikariCP lleva como causa el error de conexión
 * y la réplica sí se excluye.
 */
@ExtendWith(OutputCaptureExtension.class)
class PoolSaturationTests {

    private static final Duration TIMEOUT = Duration.ofMillis(250);

    private final List<HikariDataSource> pools = new ArrayList<>();
    private final List<Connection> held = new ArrayList<>();

    private final FakeNodeDataSource replica1Db = new FakeNodeDataSource("replica1");
    private final FakeNodeDataSource replica2Db = new FakeNodeDataSource("replica2");
    private final FakeNodeDataSource primaryDb = new FakeNodeDataSource("primary");

    private final DataSourceNode replica1 = new DataSourceNode("replica1", DataSourceRole.REPLICA, hikari(replica1Db));
    private final DataSourceNode replica2 = new DataSourceNode("replica2", DataSourceRole.REPLICA, hikari(replica2Db));
    private final DataSourceNode primary = new DataSourceNode("primary", DataSourceRole.PRIMARY, hikari(primaryDb));

    private final ReplicaPoolDataSource pool = new ReplicaPoolDataSource(List.of(replica1, replica2),
            new RoundRobinReplicaSelectionPolicy(), primary);

    @AfterEach
    void close() throws SQLException {
        for (Connection connection : held) {
            connection.close();
        }
        pools.forEach(HikariDataSource::close);
    }

    @Test
    void hikariReportsASaturatedPoolWithoutCause() throws SQLException {
        saturate(replica1);

        assertThatThrownBy(replica1::getConnection).isInstanceOf(SQLTransientConnectionException.class)
                .hasNoCause();
    }

    @Test
    void hikariReportsAnUnreachableNodeWithItsConnectionFailureAsCause() throws SQLException {
        replica1.getConnection().close();   // pool iniciado con el nodo en marcha
        replica1Db.down = true;
        ((HikariDataSource) replica1.getTargetDataSource()).getHikariPoolMXBean().softEvictConnections();

        assertThatThrownBy(replica1::getConnection).isInstanceOf(SQLTransientConnectionException.class)
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void aSaturatedReplicaStaysInTheRotationAndTheReadGoesToTheOther(CapturedOutput output) throws SQLException {
        saturate(replica1);

        Connection connection = pool.getConnection();   // round-robin: le toca a replica1
        held.add(connection);

        assertThat(replica1Db.opened).isEqualTo(1);
        assertThat(replica2Db.opened).isEqualTo(1);
        assertThat(replica1.isAvailable()).isTrue();
        assertThat(pool.getAvailableReplicas()).containsExactly(replica1, replica2);
        assertThat(output.getOut()).contains("DB route=REPLICA node=replica1 pool agotado")
                .doesNotContain("estado=DOWN");
    }

    @Test
    void whenEveryReplicaIsSaturatedTheReadFailsWithoutExcludingThemNorLoadingThePrimary() throws SQLException {
        saturate(replica1);
        saturate(replica2);

        assertThatThrownBy(pool::getConnection).isInstanceOf(SQLTransientConnectionException.class)
                .hasMessageContaining("pools de las réplicas agotados");
        assertThat(pool.getAvailableReplicas()).containsExactly(replica1, replica2);
        assertThat(primaryDb.opened).isZero();
    }

    @Test
    void anUnreachableReplicaIsStillExcluded(CapturedOutput output) throws SQLException {
        replica1Db.down = true;

        Connection connection = pool.getConnection();
        held.add(connection);

        assertThat(replica1.isAvailable()).isFalse();
        assertThat(replica2Db.opened).isEqualTo(1);
        assertThat(output.getOut()).contains("DB health node=replica1 estado=DOWN");
    }

    @Test
    void theMonitorDoesNotCountASaturatedPoolAsAFailure(CapturedOutput output) throws SQLException {
        saturate(replica1);
        ReplicaHealthMonitor monitor = new ReplicaHealthMonitor(pool, new JdbcReplicaProbe(Duration.ofSeconds(1)),
                Duration.ofSeconds(5), Duration.ofSeconds(10), 1);

        monitor.check(replica1);

        assertThat(replica1.isAvailable()).isTrue();
        assertThat(replica1.getConsecutiveFailures()).isZero();
        assertThat(output.getOut()).doesNotContain("estado=DOWN");
    }

    @Test
    void recognizesAnExhaustedPoolAlsoWhenWrapped() {
        SQLException exhausted = new SQLTransientConnectionException("request timed out");
        SQLException unreachable = new SQLTransientConnectionException("request timed out", "08001",
                new SQLException("refused", "08001"));

        assertThat(ConnectionFailures.isPoolExhausted(exhausted)).isTrue();
        assertThat(ConnectionFailures.isPoolExhausted(unreachable)).isFalse();
        assertThat(ConnectionFailures.hasPoolExhaustion(new IllegalStateException(
                new SQLTransientConnectionException("pools agotados", "08001", exhausted)))).isTrue();
        assertThat(ConnectionFailures.hasPoolExhaustion(new IllegalStateException(unreachable))).isFalse();
    }

    // --- Utilidades ---

    /** Ocupa la única conexión del pool del nodo. */
    private void saturate(DataSourceNode node) throws SQLException {
        held.add(node.getConnection());
    }

    private HikariDataSource hikari(FakeNodeDataSource target) {
        HikariDataSource hikari = new HikariDataSource();
        hikari.setPoolName("test-" + target.name);
        hikari.setDataSource(target);
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(0);
        hikari.setConnectionTimeout(TIMEOUT.toMillis());
        // El pool se inicia aunque el nodo esté caído (como un servicio que arranca sin réplicas).
        hikari.setInitializationFailTimeout(-1);
        pools.add(hikari);
        return hikari;
    }

    /** Nodo falso: conexiones válidas mientras está en marcha; error de conexión (08001) si no. */
    static final class FakeNodeDataSource implements DataSource {

        final String name;
        volatile boolean down;
        volatile int opened;

        FakeNodeDataSource(String name) {
            this.name = name;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (down) {
                throw new SQLException("Connection to " + name + ":5432 refused", "08001");
            }
            opened++;
            return connection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        private Connection connection() {
            boolean[] closed = {false};
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "isValid" -> !down && !closed[0];
                        case "isClosed" -> closed[0];
                        case "close", "abort" -> {
                            closed[0] = true;
                            yield null;
                        }
                        case "getAutoCommit" -> true;
                        case "isReadOnly" -> false;
                        case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                        case "getNetworkTimeout" -> 0;
                        case "isWrapperFor" -> false;
                        case "toString" -> "fake-connection-" + name;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("No es un envoltorio");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
