package ec.edu.upse.distribuidas.persistencerouting;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Verifica periódicamente cada réplica y actualiza la rotación de {@link ReplicaPoolDataSource}.
 *
 * <p>Una réplica está <b>apta</b> si responde, sigue en modo recuperación
 * ({@code pg_is_in_recovery() = true}), su retraso no supera {@code max-lag} y está recibiendo
 * WAL del primario. La última condición solo se exige si otra réplica sí lo recibe: si ninguna
 * lo recibe (primario caído), se mantienen para que las lecturas sigan atendiéndose.
 *
 * <ul>
 *   <li><b>Detección y exclusión:</b> tras {@code failure-threshold} verificaciones no aptas
 *       seguidas, la réplica se marca como caída y sale de la rotación.</li>
 *   <li><b>Reincorporación:</b> la primera verificación apta de una réplica caída la devuelve a
 *       la rotación. Como la verificación exige el retraso bajo el umbral, una réplica que
 *       vuelve tras una parada solo se reincorpora cuando se ha puesto al día.</li>
 * </ul>
 *
 * <p>Corre en un hilo propio ({@code routing-health}) desde que arranca el contexto de Spring
 * hasta que se detiene.
 */
public class ReplicaHealthMonitor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReplicaHealthMonitor.class);

    private final ReplicaPoolDataSource pool;
    private final ReplicaProbe probe;
    private final Duration interval;
    private final Duration maxLag;
    private final int failureThreshold;

    private ScheduledExecutorService executor;

    /** Si ya se avisó de que ninguna réplica recibe WAL (se avisa una vez por episodio). */
    private volatile boolean noWalWarned;

    /**
     * @param pool réplicas a verificar, o {@code null} si no hay réplicas (el monitor no hace nada)
     */
    public ReplicaHealthMonitor(ReplicaPoolDataSource pool, ReplicaProbe probe, Duration interval, Duration maxLag,
            int failureThreshold) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failure-threshold debe ser al menos 1");
        }
        this.pool = pool;
        this.probe = probe;
        this.interval = interval;
        this.maxLag = maxLag;
        this.failureThreshold = failureThreshold;
    }

    /**
     * Verifica todas las réplicas una vez. Primero consulta todas y después decide, porque saber
     * si alguna réplica sigue conectada al primario cambia cómo se juzga a las demás (ver
     * {@link #problemOf}).
     */
    public void checkAll() {
        if (pool == null) {
            return;
        }
        Map<DataSourceNode, Observation> observations = new LinkedHashMap<>();
        for (DataSourceNode replica : pool.getReplicas()) {
            observations.put(replica, observe(replica));
        }
        boolean anyReceivingWal = observations.values().stream()
                .anyMatch(o -> o.status() != null && o.status().inRecovery() && o.status().receivingWal());
        if (!anyReceivingWal && observations.values().stream().anyMatch(o -> o.status() != null)) {
            if (!noWalWarned) {
                log.warn("DB health ninguna réplica recibe WAL del primario (¿primario caído?): las que respondan "
                        + "siguen en la rotación, con datos posiblemente atrasados");
                noWalWarned = true;
            }
        } else {
            noWalWarned = false;
        }
        observations.forEach((replica, observation) -> apply(replica, observation, anyReceivingWal));
    }

    /** Resultado de consultar una réplica: su estado o el error al consultarla. */
    private record Observation(ReplicaProbe.Status status, Exception error) {
    }

    private Observation observe(DataSourceNode replica) {
        try {
            return new Observation(probe.probe(replica), null);
        } catch (Exception ex) {
            return new Observation(null, ex);
        }
    }

    /**
     * Motivo por el que la réplica no es apta, o {@code null} si lo es.
     *
     * <p>Una réplica sin receptor de WAL no está conectada al primario: su retraso medido es 0
     * aunque se quede atrás. Se excluye si <b>otra</b> réplica sí recibe WAL, porque entonces el
     * problema es solo suyo (por ejemplo, su slot se invalidó). Si ninguna lo recibe, lo más
     * probable es que haya caído el primario: excluirlas todas dejaría sin lecturas al servicio,
     * así que siguen en la rotación con datos posiblemente atrasados.
     */
    private String problemOf(ReplicaProbe.Status status, boolean anyReceivingWal) {
        if (!status.inRecovery()) {
            return "no está en modo recuperación (pg_is_in_recovery() = false): ya no es una réplica";
        }
        if (status.lagSeconds() > maxLag.toMillis() / 1000.0) {
            return String.format(Locale.ROOT, "retraso de replicación %.1f s > máximo %d s", status.lagSeconds(),
                    maxLag.toSeconds());
        }
        if (!status.receivingWal() && anyReceivingWal) {
            return "no recibe WAL del primario (sin receptor de WAL activo) y otra réplica sí";
        }
        return null;
    }

    private void apply(DataSourceNode replica, Observation observation, boolean anyReceivingWal) {
        if (observation.error() != null && ConnectionFailures.isPoolExhausted(observation.error())) {
            // Todas las conexiones del pool están en uso: la réplica atiende consultas, así que la
            // verificación no es concluyente. No cuenta como fallo ni cambia su estado.
            log.info("DB health node={} verificación omitida: pool agotado ({})", replica.getName(),
                    ConnectionFailures.describe(observation.error()));
            return;
        }
        String problem = observation.error() != null
                ? "sin conexión: " + ConnectionFailures.describe(observation.error())
                : problemOf(observation.status(), anyReceivingWal);

        if (problem == null) {
            replica.resetFailures();
            pool.markUp(replica, String.format(Locale.ROOT, "recovery=true lag=%.1fs wal=%s",
                    observation.status().lagSeconds(), observation.status().receivingWal() ? "streaming" : "sin-receptor"));
            return;
        }

        int failures = replica.recordFailure();
        if (!replica.isAvailable()) {
            log.debug("DB health node={} sigue no apta: {}", replica.getName(), problem);
        } else if (failures >= failureThreshold) {
            pool.markDown(replica, problem + " (" + failures + " verificaciones seguidas)");
        } else {
            log.info("DB health node={} verificación fallida {}/{}: {}", replica.getName(), failures,
                    failureThreshold, problem);
        }
    }

    private void checkSafely() {
        try {
            checkAll();
        } catch (RuntimeException ex) {
            log.error("DB health error inesperado al verificar las réplicas", ex);
        }
    }

    @Override
    public synchronized void start() {
        if (executor != null || pool == null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "routing-health");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::checkSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("DB health verificando {} réplicas cada {} s (retraso máximo {} s, caída tras {} fallos)",
                pool.getReplicas().size(), interval.toSeconds(), maxLag.toSeconds(), failureThreshold);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
