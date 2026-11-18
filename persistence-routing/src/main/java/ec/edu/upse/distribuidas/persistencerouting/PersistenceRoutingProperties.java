package ec.edu.upse.distribuidas.persistencerouting;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades {@code persistence.routing.*}: un nodo primario y una lista de réplicas.
 *
 * <p>Cada valor puede venir de una variable de entorno con la notación de Spring Boot, por
 * ejemplo {@code PERSISTENCE_ROUTING_PRIMARY_URL} o {@code PERSISTENCE_ROUTING_REPLICAS_0_URL}.
 * Ver el {@code README.md} del módulo.
 */
@ConfigurationProperties(prefix = PersistenceRoutingProperties.PREFIX)
public class PersistenceRoutingProperties {

    public static final String PREFIX = "persistence.routing";

    /** Nodo primario: recibe las escrituras. Su {@code url} activa el módulo. */
    private Node primary = new Node();

    /**
     * Réplicas de lectura, en el orden en que las recorre el round-robin. Si una réplica no
     * define usuario o clave, usa los del primario.
     */
    private List<Node> replicas = new ArrayList<>();

    /** Ajustes comunes del pool de conexiones de cada nodo. */
    private Pool pool = new Pool();

    public Node getPrimary() {
        return primary;
    }

    public void setPrimary(Node primary) {
        this.primary = primary;
    }

    public List<Node> getReplicas() {
        return replicas;
    }

    public void setReplicas(List<Node> replicas) {
        this.replicas = replicas;
    }

    public Pool getPool() {
        return pool;
    }

    public void setPool(Pool pool) {
        this.pool = pool;
    }

    /** Un nodo PostgreSQL. */
    public static class Node {

        /** Nombre del nodo en los logs. Por defecto: "primary", "replica1", "replica2"... */
        private String name;

        /** URL JDBC, por ejemplo jdbc:postgresql://primary:5432/productos_db. */
        private String url;

        /** Usuario de la base. */
        private String username;

        /** Clave del usuario. */
        private String password;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /** Ajustes del pool de conexiones (HikariCP), iguales para todos los nodos. */
    public static class Pool {

        /** Conexiones máximas por nodo. */
        private int maximumPoolSize = 5;

        /** Tiempo máximo de espera para obtener una conexión de un nodo. */
        private Duration connectionTimeout = Duration.ofSeconds(3);

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }
    }
}
