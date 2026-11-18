package ec.edu.upse.distribuidas.persistencerouting;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.util.StringUtils;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Autoconfiguración del enrutamiento de lecturas y escrituras.
 *
 * <p>Se activa cuando existe {@code persistence.routing.primary.url}. Entonces reemplaza el
 * {@code DataSource} que Spring Boot crearía por defecto ({@code spring.datasource.*}) por:
 *
 * <pre>
 * LazyConnectionDataSourceProxy          (bean DataSource principal: lo usan JPA y JdbcTemplate)
 *   └─ ReadWriteRoutingDataSource        (PRIMARY / REPLICA según la transacción)
 *        ├─ PRIMARY → DataSourceNode primary  → pool HikariCP
 *        └─ REPLICA → ReplicaPoolDataSource   → política (round-robin)
 *                        ├─ DataSourceNode replica1 → pool HikariCP
 *                        └─ DataSourceNode replica2 → pool HikariCP
 * </pre>
 */
@AutoConfiguration(before = DataSourceAutoConfiguration.class)
@ConditionalOnProperty(prefix = PersistenceRoutingProperties.PREFIX, name = "primary.url")
@EnableConfigurationProperties(PersistenceRoutingProperties.class)
public class PersistenceRoutingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReplicaSelectionPolicy replicaSelectionPolicy() {
        return new RoundRobinReplicaSelectionPolicy();
    }

    @Bean
    public ReadWriteRoutingDataSource readWriteRoutingDataSource(PersistenceRoutingProperties properties,
            ReplicaSelectionPolicy policy) {
        PersistenceRoutingProperties.Node primaryProps = properties.getPrimary();
        DataSourceNode primary = node(primaryProps, "primary", DataSourceRole.PRIMARY, primaryProps,
                properties.getPool());

        List<DataSourceNode> replicas = new ArrayList<>();
        List<PersistenceRoutingProperties.Node> replicaProps = properties.getReplicas();
        for (int i = 0; i < replicaProps.size(); i++) {
            replicas.add(node(replicaProps.get(i), "replica" + (i + 1), DataSourceRole.REPLICA, primaryProps,
                    properties.getPool()));
        }

        ReplicaPoolDataSource pool = replicas.isEmpty() ? null : new ReplicaPoolDataSource(replicas, policy);
        return new ReadWriteRoutingDataSource(primary, pool);
    }

    /**
     * DataSource que ven los servicios. El proxy perezoso retrasa la obtención de la conexión
     * física hasta la primera sentencia SQL, cuando ya se sabe si la transacción es de solo
     * lectura. Los valores por defecto se fijan aquí para que el proxy no abra una conexión al
     * arrancar solo para averiguarlos (son los de PostgreSQL).
     */
    @Bean
    @Primary
    public DataSource dataSource(ReadWriteRoutingDataSource routingDataSource) {
        LazyConnectionDataSourceProxy proxy = new LazyConnectionDataSourceProxy();
        proxy.setTargetDataSource(routingDataSource);
        proxy.setDefaultAutoCommit(true);
        proxy.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return proxy;
    }

    private static DataSourceNode node(PersistenceRoutingProperties.Node props, String defaultName,
            DataSourceRole role, PersistenceRoutingProperties.Node primaryProps,
            PersistenceRoutingProperties.Pool poolProps) {
        String name = StringUtils.hasText(props.getName()) ? props.getName() : defaultName;
        if (!StringUtils.hasText(props.getUrl())) {
            throw new IllegalStateException("Falta " + PersistenceRoutingProperties.PREFIX
                    + (role == DataSourceRole.PRIMARY ? ".primary" : ".replicas[n]") + ".url del nodo " + name);
        }

        // HikariDataSource creado con el constructor vacío: el pool se inicia con la primera
        // conexión, así que un nodo caído no impide que el servicio arranque.
        HikariDataSource hikari = new HikariDataSource();
        hikari.setPoolName("routing-" + name);
        hikari.setJdbcUrl(props.getUrl());
        hikari.setUsername(StringUtils.hasText(props.getUsername()) ? props.getUsername() : primaryProps.getUsername());
        hikari.setPassword(props.getPassword() != null ? props.getPassword() : primaryProps.getPassword());
        hikari.setMaximumPoolSize(poolProps.getMaximumPoolSize());
        hikari.setConnectionTimeout(poolProps.getConnectionTimeout().toMillis());
        return new DataSourceNode(name, role, hikari);
    }
}
