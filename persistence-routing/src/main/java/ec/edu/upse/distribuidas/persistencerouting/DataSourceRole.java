package ec.edu.upse.distribuidas.persistencerouting;

/**
 * Claves de enrutamiento de {@link ReadWriteRoutingDataSource}.
 */
public enum DataSourceRole {

    /** Nodo primario: recibe las escrituras y todo lo que no sea de solo lectura. */
    PRIMARY,

    /** Conjunto de réplicas: recibe las transacciones de solo lectura. */
    REPLICA
}
