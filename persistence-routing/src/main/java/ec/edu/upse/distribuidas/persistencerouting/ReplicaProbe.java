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
     * @param inRecovery  {@code pg_is_in_recovery()}: {@code true} mientras sea standby
     * @param lagSeconds  retraso de replicación en segundos (0 si ya aplicó todo lo recibido)
     * @param receivingWal si tiene un receptor de WAL activo ({@code pg_stat_wal_receiver}), es
     *                    decir, si sigue conectada al primario. Sin receptor, un retraso 0 no
     *                    significa que esté al día: solo que aplicó lo poco que recibió.
     */
    record Status(boolean inRecovery, double lagSeconds, boolean receivingWal) {

        /** Réplica conectada al primario (receptor de WAL activo). */
        public Status(boolean inRecovery, double lagSeconds) {
            this(inRecovery, lagSeconds, true);
        }
    }
}
