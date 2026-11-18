package ec.edu.upse.distribuidas.persistencerouting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resiliencia de réplicas (issue #9): caída, exclusión, degradación al primario,
 * reincorporación, lecturas críticas y reintento. Transacciones reales de Spring con conexiones
 * falsas; la caída de un nodo se simula con {@link RecordingDataSource#setDown(boolean)} y su
 * estado de replicación con una sonda falsa.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReplicaResilienceTests {

    private final List<String> used = Collections.synchronizedList(new ArrayList<>());

    private final RecordingDataSource primaryDb = new RecordingDataSource("primary", used);
    private final RecordingDataSource replica1Db = new RecordingDataSource("replica1", used);
    private final RecordingDataSource replica2Db = new RecordingDataSource("replica2", used);

    private final DataSourceNode primary = new DataSourceNode("primary", DataSourceRole.PRIMARY, primaryDb);
    private final DataSourceNode replica1 = new DataSourceNode("replica1", DataSourceRole.REPLICA, replica1Db);
    private final DataSourceNode replica2 = new DataSourceNode("replica2", DataSourceRole.REPLICA, replica2Db);

    /** Estado de replicación que devuelve la sonda falsa para cada réplica. */
    private final Map<String, ReplicaProbe.Status> replicationStatus = new ConcurrentHashMap<>();

    private ReplicaPoolDataSource pool;
    private DataSource dataSource;
    private DataSourceTransactionManager txManager;
    private TransactionTemplate readOnlyTx;
    private TransactionTemplate writeTx;
    private ReplicaHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        pool = new ReplicaPoolDataSource(List.of(replica1, replica2), new RoundRobinReplicaSelectionPolicy(), primary);
        dataSource = new LazyRoutingDataSource(new ReadWriteRoutingDataSource(primary, pool));
        txManager = new DataSourceTransactionManager(dataSource);
        readOnlyTx = new TransactionTemplate(txManager);
        readOnlyTx.setReadOnly(true);
        writeTx = new TransactionTemplate(txManager);

        ReplicaProbe probe = replica -> {
            RecordingDataSource db = replica == replica1 ? replica1Db : replica2Db;
            if (db.isDown()) {
                throw new SQLException("Connection to " + replica.getName() + ":5432 refused", "08001");
            }
            return replicationStatus.getOrDefault(replica.getName(), new ReplicaProbe.Status(true, 0));
        };
        monitor = new ReplicaHealthMonitor(pool, probe, Duration.ofSeconds(5), Duration.ofSeconds(10), 2);
    }

    @AfterEach
    void cleanThreadState() {
        // Ninguna prueba debe dejar marcada una lectura crítica en el hilo.
        assertThat(PrimaryRead.isActive()).isFalse();
    }

    private void read() {
        readOnlyTx.executeWithoutResult(status -> statement());
    }

    private void write() {
        writeTx.executeWithoutResult(status -> statement());
    }

    private void statement() {
        try {
            RecordingDataSource.touch(DataSourceUtils.getConnection(dataSource));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void reads(int times) {
        for (int i = 0; i < times; i++) {
            read();
        }
    }

    // --- Caída y exclusión ---

    @Test
    void aStoppedReplicaIsExcludedAtOnceAndReadsContinueOnTheOther(CapturedOutput output) {
        replica1Db.setDown(true);

        reads(6);

        assertThat(used).hasSize(6).containsOnly("replica2");
        assertThat(replica1.isAvailable()).isFalse();
        assertThat(pool.getAvailableReplicas()).containsExactly(replica2);
        assertThat(output.getOut()).contains("DB health node=replica1 estado=DOWN")
                .contains("excluida de la rotación; rotación=[replica2]");
    }

    @Test
    void withoutAvailableReplicasReadsDegradeToThePrimary(CapturedOutput output) {
        replica1Db.setDown(true);
        replica2Db.setDown(true);

        reads(3);
        write();

        assertThat(used).containsExactly("primary", "primary", "primary", "primary");
        assertThat(output.getOut()).contains("DB health sin réplicas disponibles")
                .contains("la lectura se degrada al primario")
                .contains("DB route=PRIMARY node=primary tx=read-only");
    }

    @Test
    void withoutAFallbackAndNoReplicasTheConnectionFails() {
        ReplicaPoolDataSource noFallback = new ReplicaPoolDataSource(List.of(replica1),
                new RoundRobinReplicaSelectionPolicy());
        replica1Db.setDown(true);

        assertThatThrownBy(noFallback::getConnection).isInstanceOf(SQLException.class)
                .hasMessageContaining("No hay réplicas disponibles");
    }

    @Test
    void withoutExclusionAFailedReplicaStaysInTheRotation() {
        pool.setExcludeOnConnectionFailure(false);
        replica1Db.setDown(true);

        assertThatThrownBy(pool::getConnection).isInstanceOf(SQLException.class);
        assertThat(replica1.isAvailable()).isTrue();
    }

    // --- Detección periódica ---

    @Test
    void theMonitorMarksAReplicaDownAfterTheFailureThreshold(CapturedOutput output) {
        replica1Db.setDown(true);

        monitor.checkAll();
        assertThat(replica1.isAvailable()).isTrue();
        assertThat(output.getOut()).contains("DB health node=replica1 verificación fallida 1/2");

        monitor.checkAll();
        assertThat(replica1.isAvailable()).isFalse();
        assertThat(replica2.isAvailable()).isTrue();
        assertThat(output.getOut()).contains("DB health node=replica1 estado=DOWN motivo=\"sin conexión: SQLException");

        reads(4);
        assertThat(used).containsOnly("replica2");
    }

    @Test
    void aSuccessfulCheckResetsTheFailureCount() {
        replica1Db.setDown(true);
        monitor.checkAll();
        replica1Db.setDown(false);
        monitor.checkAll();
        replica1Db.setDown(true);
        monitor.checkAll();

        assertThat(replica1.isAvailable()).isTrue();
        assertThat(replica1.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void aPromotedReplicaLeavesTheRotation(CapturedOutput output) {
        replicationStatus.put("replica2", new ReplicaProbe.Status(false, 0));

        monitor.checkAll();
        monitor.checkAll();

        assertThat(replica2.isAvailable()).isFalse();
        assertThat(output.getOut()).contains("no está en modo recuperación");
    }

    @Test
    void aLaggingReplicaLeavesTheRotation(CapturedOutput output) {
        replicationStatus.put("replica1", new ReplicaProbe.Status(true, 25.0));

        monitor.checkAll();
        monitor.checkAll();

        assertThat(replica1.isAvailable()).isFalse();
        assertThat(output.getOut()).contains("retraso de replicación 25.0 s > máximo 10 s");
    }

    // --- Reincorporación ---

    @Test
    void aRecoveredReplicaRejoinsOnlyWhenItHasCaughtUp(CapturedOutput output) {
        replica1Db.setDown(true);
        monitor.checkAll();
        monitor.checkAll();
        assertThat(replica1.isAvailable()).isFalse();

        // Vuelve a responder, pero todavía está aplicando el WAL acumulado: no está apta.
        replica1Db.setDown(false);
        replicationStatus.put("replica1", new ReplicaProbe.Status(true, 40.0));
        monitor.checkAll();
        assertThat(replica1.isAvailable()).isFalse();
        reads(2);
        assertThat(used).containsOnly("replica2");

        // Ya se puso al día: se reincorpora y vuelve a recibir lecturas.
        replicationStatus.put("replica1", new ReplicaProbe.Status(true, 0.0));
        monitor.checkAll();
        assertThat(replica1.isAvailable()).isTrue();
        assertThat(output.getOut()).contains("DB health node=replica1 estado=UP recovery=true lag=0.0s")
                .contains("reincorporada a la rotación; rotación=[replica1, replica2]");

        used.clear();
        reads(4);
        assertThat(used).containsOnly("replica1", "replica2").contains("replica1");
    }

    @Test
    void whenAReplicaRejoinsAfterAllWereDownReadsLeaveThePrimary(CapturedOutput output) {
        replica1Db.setDown(true);
        replica2Db.setDown(true);
        read();
        assertThat(used).containsExactly("primary");

        replica2Db.setDown(false);
        monitor.checkAll();

        used.clear();
        reads(2);
        assertThat(used).containsExactly("replica2", "replica2");
        assertThat(output.getOut()).contains("DB health las lecturas vuelven a las réplicas");
    }

    @Test
    void transitionsAreLoggedOnceNotOnEveryCheck(CapturedOutput output) {
        replica1Db.setDown(true);
        for (int i = 0; i < 5; i++) {
            monitor.checkAll();
        }

        assertThat(output.getOut().split("node=replica1 estado=DOWN", -1)).hasSize(2);
    }

    // --- Lecturas críticas ---

    @Test
    void aCriticalReadGoesToThePrimaryEvenIfReadOnly(CapturedOutput output) {
        PrimaryRead.run(this::read);
        String result = PrimaryRead.call(() -> {
            read();
            return "ok";
        });
        read();

        assertThat(result).isEqualTo("ok");
        assertThat(used).containsExactly("primary", "primary", "replica1");
        assertThat(output.getOut()).contains("DB route=PRIMARY node=primary tx=read-only read=critical");
    }

    @Test
    void aWriteFollowedByACriticalReadAlwaysSeesThePrimary() {
        for (int i = 0; i < 5; i++) {
            write();
            PrimaryRead.run(this::read);
        }

        assertThat(used).hasSize(10).containsOnly("primary");
    }

    @Test
    void aCriticalReadInsideAnOpenReadOnlyTransactionIsWarned(CapturedOutput output) {
        readOnlyTx.executeWithoutResult(status -> PrimaryRead.run(() -> { }));

        assertThat(output.getOut()).contains("Lectura crítica iniciada dentro de una transacción de solo lectura");
    }

    @Test
    void readFromPrimaryMarksTheAnnotatedMethodsOnly() {
        Reports reports = proxy(new Reports(), new DefaultPointcutAdvisor(ReadFromPrimaryInterceptor.pointcut(),
                new ReadFromPrimaryInterceptor()));

        assertThat(reports.critical()).isTrue();
        assertThat(reports.ordinary()).isFalse();
        assertThat(proxy(new CriticalReports(), new DefaultPointcutAdvisor(ReadFromPrimaryInterceptor.pointcut(),
                new ReadFromPrimaryInterceptor())).anything()).isTrue();
    }

    @Test
    void readFromPrimaryWithReadOnlyTransactionUsesThePrimary() {
        Catalog catalog = transactionalProxy(new Catalog(this::statement), 2);

        catalog.criticalRead();
        catalog.ordinaryRead();

        assertThat(used).containsExactly("primary", "replica1");
    }

    // --- Reintento ---

    @Test
    void aReadThatLosesItsReplicaMidwayIsRetriedOnTheOther(CapturedOutput output) {
        Catalog catalog = transactionalProxy(new Catalog(this::statement), 2);
        catalog.failNextWithConnectionError(1);

        assertThat(catalog.ordinaryRead()).isEqualTo("ok");

        assertThat(catalog.calls()).isEqualTo(2);
        assertThat(used).containsExactly("replica1", "replica2");
        assertThat(output.getOut()).contains("DB retry Catalog.ordinaryRead: fallo de conexión")
                .contains("intento 2/2 en otra réplica");
    }

    @Test
    void retriesStopAtMaxAttempts() {
        Catalog catalog = transactionalProxy(new Catalog(this::statement), 2);
        catalog.failNextWithConnectionError(5);

        assertThatThrownBy(catalog::ordinaryRead).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(catalog.calls()).isEqualTo(2);
    }

    @Test
    void queryErrorsAreNotRetried() {
        Catalog catalog = transactionalProxy(new Catalog(this::statement), 3);
        catalog.failNextWith(new IllegalArgumentException("SQL inválido"));

        assertThatThrownBy(catalog::ordinaryRead).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.calls()).isEqualTo(1);
    }

    @Test
    void writesAreNotRetried() {
        Catalog catalog = transactionalProxy(new Catalog(this::statement), 3);
        catalog.failNextWithConnectionError(1);

        assertThatThrownBy(catalog::save).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(catalog.calls()).isEqualTo(1);
    }

    @Test
    void aReadInsideAnOuterTransactionIsNotRetriedOnItsOwn() {
        Catalog catalog = transactionalProxy(new Catalog(this::statement), 3);
        catalog.failNextWithConnectionError(1);

        assertThatThrownBy(() -> readOnlyTx.executeWithoutResult(status -> catalog.ordinaryRead()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(catalog.calls()).isEqualTo(1);
    }

    @Test
    void recognizesConnectionFailures() {
        assertThat(ConnectionFailures.isConnectionFailure(new SQLException("I/O error", "08006"))).isTrue();
        assertThat(ConnectionFailures.isConnectionFailure(
                new IllegalStateException(new SQLException("refused", "08001")))).isTrue();
        assertThat(ConnectionFailures.isConnectionFailure(new SQLException("syntax error", "42601"))).isFalse();
        assertThat(ConnectionFailures.isConnectionFailure(new IllegalStateException("otro"))).isFalse();
    }

    // --- Utilidades ---

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, DefaultPointcutAdvisor advisor) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(advisor);
        return (T) factory.getProxy();
    }

    /** Proxy como el de Spring: lectura crítica, reintento y transacción, en ese orden. */
    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(T target, int maxAttempts) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new DefaultPointcutAdvisor(ReadFromPrimaryInterceptor.pointcut(),
                new ReadFromPrimaryInterceptor()));
        factory.addAdvisor(new DefaultPointcutAdvisor(ReadOnlyRetryInterceptor.pointcut(),
                new ReadOnlyRetryInterceptor(() -> maxAttempts)));
        factory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    static class Reports {

        @ReadFromPrimary
        public boolean critical() {
            return PrimaryRead.isActive();
        }

        public boolean ordinary() {
            return PrimaryRead.isActive();
        }
    }

    @ReadFromPrimary
    static class CriticalReports {

        public boolean anything() {
            return PrimaryRead.isActive();
        }
    }

    /** Servicio de ejemplo: ejecuta una sentencia y puede fallar como si su réplica se cayera. */
    static class Catalog {

        private final Runnable statement;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger failuresLeft = new AtomicInteger();
        private RuntimeException failure;

        Catalog() {
            this(() -> { });
        }

        Catalog(Runnable statement) {
            this.statement = statement;
        }

        void failNextWithConnectionError(int times) {
            failuresLeft.set(times);
            failure = new DataAccessResourceFailureException("I/O error",
                    new SQLException("An I/O error occurred while sending to the backend.", "08006"));
        }

        void failNextWith(RuntimeException ex) {
            failuresLeft.set(1);
            failure = ex;
        }

        int calls() {
            return calls.get();
        }

        private String run() {
            calls.incrementAndGet();
            statement.run();
            if (failuresLeft.getAndDecrement() > 0) {
                throw failure;
            }
            return "ok";
        }

        @Transactional(readOnly = true)
        public String ordinaryRead() {
            return run();
        }

        @ReadFromPrimary
        @Transactional(readOnly = true)
        public String criticalRead() {
            return run();
        }

        @Transactional
        public String save() {
            return run();
        }
    }
}
