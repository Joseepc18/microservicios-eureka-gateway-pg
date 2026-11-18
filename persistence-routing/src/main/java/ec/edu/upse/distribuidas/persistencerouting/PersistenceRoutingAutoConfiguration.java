package ec.edu.upse.distribuidas.persistencerouting;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Autoconfiguración del enrutamiento de lecturas y escrituras.
 *
 * <p>Se activa cuando existe {@code persistence.routing.primary.url}. Entonces reemplaza el
 * {@code DataSource} que Spring Boot crearía por defecto ({@code spring.datasource.*}) por:
 *
 * <pre>
 * LazyRoutingDataSource                  (único bean DataSource: proxy perezoso; lo usan JPA y JdbcTemplate)
 *   └─ ReadWriteRoutingDataSource        (PRIMARY / REPLICA según la transacción)
 *        ├─ PRIMARY → DataSourceNode primary  → pool HikariCP
 *        └─ REPLICA → ReplicaPoolDataSource   → política (round-robin)
 *                        ├─ DataSourceNode replica1 → pool HikariCP
 *                        └─ DataSourceNode replica2 → pool HikariCP
 * </pre>
 *
 * <p>Además registra {@link ReplicaHealthMonitor} (verificación de réplicas) y los interceptores
 * de {@link ReadFromPrimary} y de reintento de lecturas ({@link ReadOnlyRetryInterceptor}).
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

    /**
     * DataSource que ven los servicios: el proxy perezoso con el routing dentro (ver
     * {@link LazyRoutingDataSource}). El routing no se registra como bean aparte para que Spring
     * Boot no lo trate como otro {@code DataSource} (por ejemplo, en el health check de Actuator).
     */
    @Bean
    @Primary
    public LazyRoutingDataSource dataSource(PersistenceRoutingProperties properties, ReplicaSelectionPolicy policy) {
        PersistenceRoutingProperties.Node primaryProps = properties.getPrimary();
        DataSourceNode primary = node(primaryProps, "primary", DataSourceRole.PRIMARY, primaryProps,
                properties.getPool());

        List<DataSourceNode> replicas = new ArrayList<>();
        List<PersistenceRoutingProperties.Node> replicaProps = properties.getReplicas();
        for (int i = 0; i < replicaProps.size(); i++) {
            replicas.add(node(replicaProps.get(i), "replica" + (i + 1), DataSourceRole.REPLICA, primaryProps,
                    properties.getPool()));
        }

        ReplicaPoolDataSource pool = null;
        if (!replicas.isEmpty()) {
            // Sin réplicas disponibles, las lecturas se degradan al primario.
            pool = new ReplicaPoolDataSource(replicas, policy, primary);
            pool.setExcludeOnConnectionFailure(properties.getHealth().isEnabled());
        }
        return new LazyRoutingDataSource(new ReadWriteRoutingDataSource(primary, pool));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = PersistenceRoutingProperties.PREFIX, name = "health.enabled", matchIfMissing = true)
    public ReplicaProbe replicaProbe(PersistenceRoutingProperties properties) {
        return new JdbcReplicaProbe(properties.getHealth().getQueryTimeout());
    }

    /** Verificación periódica de las réplicas: detección, exclusión y reincorporación. */
    @Bean
    @ConditionalOnProperty(prefix = PersistenceRoutingProperties.PREFIX, name = "health.enabled", matchIfMissing = true)
    public ReplicaHealthMonitor replicaHealthMonitor(LazyRoutingDataSource dataSource, ReplicaProbe probe,
            PersistenceRoutingProperties properties) {
        PersistenceRoutingProperties.Health health = properties.getHealth();
        return new ReplicaHealthMonitor(dataSource.getRoutingDataSource().getReplicaPool(), probe,
                health.getInterval(), health.getMaxLag(), health.getFailureThreshold());
    }

    /**
     * Aplica {@link ReadFromPrimary}. Es infraestructura (como el soporte de
     * {@code @Transactional}), así que lo recoge el auto-proxy de Spring Boot. Va antes que el
     * reintento y que las transacciones.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor readFromPrimaryAdvisor() {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(ReadFromPrimaryInterceptor.pointcut(),
                new ReadFromPrimaryInterceptor());
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return advisor;
    }

    /** Reintenta en otra réplica las lecturas que fallan por un error de conexión. */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor readOnlyRetryAdvisor(ObjectProvider<PersistenceRoutingProperties> properties) {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(ReadOnlyRetryInterceptor.pointcut(),
                new ReadOnlyRetryInterceptor(() -> properties.getObject().getReadRetry().getMaxAttempts()));
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return advisor;
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
