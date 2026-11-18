package ec.edu.upse.distribuidas.persistencerouting;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Lecturas críticas: ejecuta una lectura en el primario aunque sea de solo lectura.
 *
 * <p>La replicación es asíncrona, así que una réplica puede no tener todavía un dato recién
 * escrito. Una lectura que debe ver la escritura anterior (por ejemplo, consultar un pedido justo
 * después de crearlo) se marca como crítica y va al primario, que siempre tiene el dato:
 *
 * <pre>{@code
 * Producto producto = PrimaryRead.call(() -> productoService.buscar(id));
 * }</pre>
 *
 * <p>Equivale a anotar el método con {@link ReadFromPrimary}. La marca vale para el hilo actual
 * mientras dura la llamada, incluidas las llamadas anidadas.
 *
 * <p><b>Uso correcto:</b> la marca debe estar puesta antes de que la transacción obtenga su
 * conexión, es decir, alrededor del método {@code @Transactional(readOnly = true)}, no dentro de
 * él. Si se usa dentro de una transacción de solo lectura que ya ejecutó SQL en una réplica, esa
 * transacción sigue en su réplica; en ese caso se registra un aviso.
 */
public final class PrimaryRead {

    private static final Logger log = LoggerFactory.getLogger(PrimaryRead.class);

    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private PrimaryRead() {
    }

    /** Ejecuta {@code read} como lectura crítica y devuelve su resultado. */
    public static <T> T call(Supplier<T> read) {
        enter();
        try {
            return read.get();
        } finally {
            exit();
        }
    }

    /** Ejecuta {@code read} como lectura crítica. */
    public static void run(Runnable read) {
        enter();
        try {
            read.run();
        } finally {
            exit();
        }
    }

    /** {@code true} si el hilo actual está dentro de una lectura crítica. */
    public static boolean isActive() {
        return DEPTH.get() != null;
    }

    static void enter() {
        Integer depth = DEPTH.get();
        if (depth == null && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            log.warn("Lectura crítica iniciada dentro de una transacción de solo lectura ya abierta: "
                    + "si esa transacción ya usó una réplica, la lectura seguirá en ella. "
                    + "Marque el método transaccional externo con @ReadFromPrimary.");
        }
        DEPTH.set(depth == null ? 1 : depth + 1);
    }

    static void exit() {
        Integer depth = DEPTH.get();
        if (depth == null || depth <= 1) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth - 1);
        }
    }
}
