package ec.edu.upse.distribuidas.persistencerouting;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

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
     * {@code true} si en la cadena de causas hay un error de conexión: SQLState de la clase
     * {@code 08} (connection exception), las excepciones JDBC de conexión o sus traducciones de
     * Spring.
     */
    static boolean isConnectionFailure(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
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
