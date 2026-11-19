package ec.edu.upse.distribuidas.persistencerouting;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Reconoce los errores de conexión con un nodo (caído, inalcanzable o que cortó la conexión),
 * a diferencia de los errores de la propia consulta.
 */
final class ConnectionFailures {

    private ConnectionFailures() {
    }

    /**
     * SQLState con los que PostgreSQL corta la sesión porque el servidor se detiene o aún no
     * acepta conexiones: {@code 57P01} (admin_shutdown, el que llega con {@code docker stop}, que
     * hace una parada rápida), {@code 57P02} (crash_shutdown) y {@code 57P03} (cannot_connect_now).
     * Hibernate los traduce a una excepción genérica, no a una de conexión.
     */
    private static final Set<String> SERVER_SHUTDOWN_STATES = Set.of("57P01", "57P02", "57P03");

    /**
     * {@code true} si en la cadena de causas hay un error de conexión: SQLState de la clase
     * {@code 08} (connection exception) o de parada del servidor ({@code 57P01}-{@code 57P03}),
     * las excepciones JDBC de conexión o sus traducciones de Spring.
     */
    static boolean isConnectionFailure(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || SERVER_SHUTDOWN_STATES.contains(sql.getSQLState()))) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * {@code true} si el pool de conexiones del nodo está agotado: todas sus conexiones están en
     * uso y la espera superó el {@code connection-timeout}. El nodo responde, solo está ocupado.
     *
     * <p>HikariCP lanza en los dos casos una {@link SQLTransientConnectionException} ("Connection is
     * not available, request timed out"), pero solo cuando el nodo no responde le pone como causa
     * el último error al abrir una conexión. Sin causa, el pool simplemente estaba lleno.
     */
    static boolean isPoolExhausted(Throwable ex) {
        return ex instanceof SQLTransientConnectionException && ex.getCause() == null;
    }

    /** {@code true} si en la cadena de causas hay un pool agotado ({@link #isPoolExhausted}). */
    static boolean hasPoolExhaustion(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (isPoolExhausted(cause)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** Mensaje breve de la causa raíz, para el log. */
    static String describe(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? "" : root.getMessage().lines().findFirst().orElse("");
        return root.getClass().getSimpleName() + (message.isEmpty() ? "" : ": " + message);
    }
}
