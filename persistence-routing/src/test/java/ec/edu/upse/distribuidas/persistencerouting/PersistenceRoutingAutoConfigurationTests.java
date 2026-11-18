package ec.edu.upse.distribuidas.persistencerouting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import com.zaxxer.hikari.HikariDataSource;

/**
 * La autoconfiguración, cargada igual que en un servicio: solo con la dependencia y las
 * propiedades. Ninguna prueba abre conexiones reales (los pools se inician con la primera).
 */
class PersistenceRoutingAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PersistenceRoutingAutoConfiguration.class,
                    DataSourceAutoConfiguration.class));

    private static final String[] PROPERTIES = {
            "persistence.routing.primary.url=jdbc:postgresql://primary:5432/productos_db",
            "persistence.routing.primary.username=productos_app",
            "persistence.routing.primary.password=clave",
            "persistence.routing.replicas[0].url=jdbc:postgresql://replica1:5432/productos_db",
            "persistence.routing.replicas[1].url=jdbc:postgresql://replica2:5432/productos_db"};

    private static HikariDataSource hikari(DataSourceNode node) {
        return (HikariDataSource) node.getTargetDataSource();
    }

    @Test
    void replacesTheDefaultDataSourceWithTheLazyRoutingProxy() {
        runner.withPropertyValues(PROPERTIES).run(context -> {
            assertThat(context).hasNotFailed();
            DataSource dataSource = context.getBean(DataSource.class);
            assertThat(dataSource).isInstanceOf(LazyConnectionDataSourceProxy.class);
            assertThat(((LazyConnectionDataSourceProxy) dataSource).getTargetDataSource())
                    .isSameAs(context.getBean(ReadWriteRoutingDataSource.class));
        });
    }

    @Test
    void buildsThePrimaryAndTheReplicasFromTheProperties() {
        runner.withPropertyValues(PROPERTIES).run(context -> {
            ReadWriteRoutingDataSource routing = context.getBean(ReadWriteRoutingDataSource.class);

            assertThat(routing.getPrimary().getName()).isEqualTo("primary");
            assertThat(hikari(routing.getPrimary()).getJdbcUrl()).isEqualTo("jdbc:postgresql://primary:5432/productos_db");
            assertThat(routing.getReplicas()).extracting(DataSourceNode::getName).containsExactly("replica1", "replica2");
            assertThat(routing.getReplicas()).extracting(node -> hikari(node).getJdbcUrl()).containsExactly(
                    "jdbc:postgresql://replica1:5432/productos_db", "jdbc:postgresql://replica2:5432/productos_db");
            assertThat(routing.getPolicy()).isInstanceOf(RoundRobinReplicaSelectionPolicy.class);
        });
    }

    @Test
    void replicasInheritTheCredentialsOfThePrimary() {
        runner.withPropertyValues(PROPERTIES).run(context -> {
            for (DataSourceNode replica : context.getBean(ReadWriteRoutingDataSource.class).getReplicas()) {
                assertThat(hikari(replica).getUsername()).isEqualTo("productos_app");
                assertThat(hikari(replica).getPassword()).isEqualTo("clave");
            }
        });
    }

    @Test
    void aReplicaCanOverrideNameAndCredentialsAndPoolSettingsApplyToEveryNode() {
        runner.withPropertyValues(PROPERTIES)
                .withPropertyValues("persistence.routing.replicas[1].name=lectura-b",
                        "persistence.routing.replicas[1].username=otro_usuario",
                        "persistence.routing.replicas[1].password=otra_clave",
                        "persistence.routing.pool.maximum-pool-size=7",
                        "persistence.routing.pool.connection-timeout=1500ms")
                .run(context -> {
                    ReadWriteRoutingDataSource routing = context.getBean(ReadWriteRoutingDataSource.class);
                    DataSourceNode second = routing.getReplicas().get(1);

                    assertThat(second.getName()).isEqualTo("lectura-b");
                    assertThat(hikari(second).getUsername()).isEqualTo("otro_usuario");
                    assertThat(hikari(second).getPassword()).isEqualTo("otra_clave");
                    assertThat(hikari(second).getPoolName()).isEqualTo("routing-lectura-b");
                    assertThat(List.of(routing.getPrimary(), routing.getReplicas().get(0), second))
                            .allSatisfy(node -> {
                                assertThat(hikari(node).getMaximumPoolSize()).isEqualTo(7);
                                assertThat(hikari(node).getConnectionTimeout())
                                        .isEqualTo(Duration.ofMillis(1500).toMillis());
                            });
                });
    }

    @Test
    void readsPropertiesFromEnvironmentVariableStyleNames() {
        // Spring Boot traduce PERSISTENCE_ROUTING_REPLICAS_0_URL a persistence.routing.replicas[0].url.
        // Se simulan las variables con una fuente de tipo "entorno del sistema", que es la que
        // Spring Boot trata con esa traducción.
        Map<String, Object> env = Map.of(
                "PERSISTENCE_ROUTING_PRIMARY_URL", "jdbc:postgresql://primary:5432/pedidos_db",
                "PERSISTENCE_ROUTING_PRIMARY_USERNAME", "pedidos_app",
                "PERSISTENCE_ROUTING_REPLICAS_0_URL", "jdbc:postgresql://replica1:5432/pedidos_db");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PersistenceRoutingAutoConfiguration.class))
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", env)))
                .run(context -> {
                    ReadWriteRoutingDataSource routing = context.getBean(ReadWriteRoutingDataSource.class);
                    assertThat(hikari(routing.getPrimary()).getUsername()).isEqualTo("pedidos_app");
                    assertThat(routing.getReplicas()).extracting(node -> hikari(node).getJdbcUrl())
                            .containsExactly("jdbc:postgresql://replica1:5432/pedidos_db");
                });
    }

    @Test
    void usesACustomPolicyWhenTheServiceDeclaresOne() {
        ReplicaSelectionPolicy alwaysFirst = replicas -> replicas.get(0);
        runner.withPropertyValues(PROPERTIES)
                .withBean(ReplicaSelectionPolicy.class, () -> alwaysFirst)
                .run(context -> assertThat(context.getBean(ReadWriteRoutingDataSource.class).getPolicy())
                        .isSameAs(alwaysFirst));
    }

    @Test
    void staysInactiveWithoutThePrimaryUrl() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PersistenceRoutingAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ReadWriteRoutingDataSource.class);
                    assertThat(context).doesNotHaveBean(DataSource.class);
                });
    }

    @Test
    void failsAtStartupWhenAReplicaHasNoUrl() {
        runner.withPropertyValues(PROPERTIES)
                .withPropertyValues("persistence.routing.replicas[2].name=replica3")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("persistence.routing.replicas[n].url del nodo replica3"));
    }

    @Test
    void closesTheConnectionPoolsWhenTheContextCloses() {
        runner.withPropertyValues(PROPERTIES).run(context -> {
            ReadWriteRoutingDataSource routing = context.getBean(ReadWriteRoutingDataSource.class);
            context.close();
            assertThat(hikari(routing.getPrimary()).isClosed()).isTrue();
            assertThat(routing.getReplicas()).allSatisfy(node -> assertThat(hikari(node).isClosed()).isTrue());
        });
    }
}
