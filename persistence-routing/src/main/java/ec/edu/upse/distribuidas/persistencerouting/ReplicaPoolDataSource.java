package ec.edu.upse.distribuidas.persistencerouting;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Destino de la clave {@link DataSourceRole#REPLICA}: reparte cada conexión entre las réplicas
 * según la {@link ReplicaSelectionPolicy} configurada.
 */
public class ReplicaPoolDataSource extends AbstractDataSource {

    private final List<DataSourceNode> replicas;
    private final ReplicaSelectionPolicy policy;

    public ReplicaPoolDataSource(List<DataSourceNode> replicas, ReplicaSelectionPolicy policy) {
        if (replicas.isEmpty()) {
            throw new IllegalArgumentException("El conjunto de réplicas no puede estar vacío");
        }
        this.replicas = List.copyOf(replicas);
        this.policy = policy;
    }

    public List<DataSourceNode> getReplicas() {
        return replicas;
    }

    public ReplicaSelectionPolicy getPolicy() {
        return policy;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return policy.select(replicas).getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return policy.select(replicas).getConnection(username, password);
    }
}
