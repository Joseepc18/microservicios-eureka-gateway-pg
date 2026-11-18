package ec.edu.upse.distribuidas.persistencerouting;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * DataSource falso para las pruebas: no abre conexiones reales y anota en {@code log} el
 * nombre del nodo cada vez que se le pide una conexión física.
 */
class RecordingDataSource extends AbstractDataSource {

    private final String name;
    private final List<String> log;

    RecordingDataSource(String name, List<String> log) {
        this.name = name;
        this.log = log;
    }

    @Override
    public Connection getConnection() {
        log.add(name);
        return fakeConnection();
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }

    private static Connection fakeConnection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> fakeStatement();
                    case "getAutoCommit" -> true;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "isWrapperFor" -> false;
                    case "toString" -> "fake-connection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Statement fakeStatement() {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == long.class) {
            return type == int.class ? 0 : 0L;
        }
        return null;
    }

    /** Ejecuta una sentencia para forzar la obtención de la conexión física. */
    static void touch(Connection connection) throws SQLException {
        connection.createStatement();
    }
}
