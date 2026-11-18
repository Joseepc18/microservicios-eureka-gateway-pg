package ec.edu.upse.distribuidas.persistencerouting;

import java.sql.SQLException;

/**
 * Consulta el estado de una réplica para {@link ReplicaHealthMonitor}. La implementación por
 * defecto es {@link JdbcReplicaProbe}; las pruebas usan una falsa.
 */
@FunctionalInterface
public interface ReplicaProbe {

    /**
     * @return el estado de la réplica
     * @throws SQLException si no se pudo conectar o consultar
     */
    Status probe(DataSourceNode replica) throws SQLException;

    /**
     * Estado observado de una réplica.
     *
     * @param inRecovery {@code pg_is_in_recovery()}: {@code true} mientras sea standby
     * @param lagSeconds retraso de replicación en segundos (0 si ya aplicó todo lo recibido)
     */
    record Status(boolean inRecovery, double lagSeconds) {
    }
}
