package ec.edu.upse.distribuidas.persistencerouting;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * El {@code DataSource} que ven los servicios: un {@link LazyConnectionDataSourceProxy} delante
 * del {@link ReadWriteRoutingDataSource}.
 *
 * <p>El proxy perezoso retrasa la obtención de la conexión física hasta la primera sentencia
 * SQL, cuando ya se sabe si la transacción es de solo lectura. Los valores por defecto se fijan
 * aquí para que el proxy no abra una conexión al arrancar solo para averiguarlos (son los de
 * PostgreSQL).
 *
 * <p>Es el único bean {@code DataSource} del módulo. El routing queda dentro, sin registrarse
 * como bean ni anunciarse con {@code unwrap}: si Spring Boot lo encontrara, el health check de
 * Actuator pediría conexiones a las réplicas fuera de toda transacción. Eso consumiría turnos del
 * round-robin y llenaría el log con {@code DB route=REPLICA ... tx=none} en cada comprobación. Por
 * eso este bean también se encarga de cerrar los pools al apagar.
 */
public class LazyRoutingDataSource extends LazyConnectionDataSourceProxy implements DisposableBean {

    private final ReadWriteRoutingDataSource routingDataSource;

    public LazyRoutingDataSource(ReadWriteRoutingDataSource routingDataSource) {
        this.routingDataSource = routingDataSource;
        setTargetDataSource(routingDataSource);
        setDefaultAutoCommit(true);
        setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        afterPropertiesSet();
    }

    public ReadWriteRoutingDataSource getRoutingDataSource() {
        return routingDataSource;
    }

    /**
     * No se anuncia como envoltorio del routing. Spring Boot busca con
     * {@code isWrapperFor(AbstractRoutingDataSource.class)} los {@code DataSource} con routing
     * para comprobar la salud de cada destino, y el destino {@code REPLICA} es la política: cada
     * health check consumiría turnos del round-robin. Así el health check usa este proxy como un
     * {@code DataSource} normal, sin transacción, y va al primario.
     */
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return !isRoutingType(iface) && super.isWrapperFor(iface);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (isRoutingType(iface)) {
            throw new SQLException("El routing es interno; use getRoutingDataSource()");
        }
        return super.unwrap(iface);
    }

    private static boolean isRoutingType(Class<?> iface) {
        return AbstractRoutingDataSource.class.isAssignableFrom(iface);
    }

    /** Cierra los pools de conexiones de todos los nodos al apagar la aplicación. */
    @Override
    public void destroy() throws Exception {
        routingDataSource.destroy();
    }
}
