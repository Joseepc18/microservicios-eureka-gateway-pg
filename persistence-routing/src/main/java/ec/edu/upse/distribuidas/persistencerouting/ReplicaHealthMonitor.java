package ec.edu.upse.distribuidas.persistencerouting;

import java.time.Duration;
import java.util.Locale;
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
 * ({@code pg_is_in_recovery() = true}) y su retraso no supera {@code max-lag}.
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

    /** Verifica todas las réplicas una vez. */
    public void checkAll() {
        if (pool == null) {
            return;
        }
        for (DataSourceNode replica : pool.getReplicas()) {
            check(replica);
        }
    }

    void check(DataSourceNode replica) {
        String problem;
        double lag = 0;
        try {
            ReplicaProbe.Status status = probe.probe(replica);
            lag = status.lagSeconds();
            if (!status.inRecovery()) {
                problem = "no está en modo recuperación (pg_is_in_recovery() = false): ya no es una réplica";
            } else if (lag > maxLag.toMillis() / 1000.0) {
                problem = String.format(Locale.ROOT, "retraso de replicación %.1f s > máximo %d s", lag,
                        maxLag.toSeconds());
            } else {
                problem = null;
            }
        } catch (Exception ex) {
            problem = "sin conexión: " + ConnectionFailures.describe(ex);
        }

        if (problem == null) {
            replica.resetFailures();
            pool.markUp(replica, String.format(Locale.ROOT, "recovery=true lag=%.1fs", lag));
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
