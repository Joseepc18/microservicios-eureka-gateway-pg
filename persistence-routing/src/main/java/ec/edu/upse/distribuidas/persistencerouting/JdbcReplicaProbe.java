package ec.edu.upse.distribuidas.persistencerouting;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * Sonda real: abre una conexión directa al pool de la réplica (sin pasar por el enrutamiento,
 * así que no consume turnos del round-robin ni deja líneas {@code DB route}) y consulta:
 *
 * <ul>
 *   <li>{@code pg_is_in_recovery()}: si es {@code false}, la réplica fue promovida y ya no
 *       recibe cambios del primario.</li>
 *   <li>El retraso de replicación: 0 si ya aplicó todo el WAL recibido; si no, el tiempo desde
 *       la última transacción aplicada. Así, un primario sin escrituras no hace parecer
 *       retrasada a una réplica al día.</li>
 *   <li>Si tiene un receptor de WAL activo ({@code pg_stat_wal_receiver}): sin él, la réplica no
 *       está conectada al primario y su retraso medido no crece aunque se quede atrás. La vista
 *       solo tiene fila mientras el proceso receptor existe, y un rol sin privilegios de
 *       monitoreo ve esa fila (con el {@code pid}) aunque no vea sus detalles: basta con
 *       {@code EXISTS}.</li>
 * </ul>
 */
public class JdbcReplicaProbe implements ReplicaProbe {

    static final String QUERY = """
            SELECT pg_is_in_recovery(),
                   CASE
                     WHEN NOT pg_is_in_recovery() THEN 0
                     WHEN pg_last_wal_receive_lsn() = pg_last_wal_replay_lsn() THEN 0
                     ELSE COALESCE(EXTRACT(EPOCH FROM now() - pg_last_xact_replay_timestamp()), 0)
                   END,
                   EXISTS (SELECT 1 FROM pg_stat_wal_receiver)""";

    private final int queryTimeoutSeconds;

    public JdbcReplicaProbe(Duration queryTimeout) {
        this.queryTimeoutSeconds = (int) Math.max(1, queryTimeout.toSeconds());
    }

    @Override
    public Status probe(DataSourceNode replica) throws SQLException {
        try (Connection connection = replica.getConnectionWithoutRouting();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = statement.executeQuery(QUERY)) {
                rs.next();
                return new Status(rs.getBoolean(1), rs.getDouble(2), rs.getBoolean(3));
            }
        }
    }
}
