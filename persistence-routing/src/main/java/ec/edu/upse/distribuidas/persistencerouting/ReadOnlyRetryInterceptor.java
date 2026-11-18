package ec.edu.upse.distribuidas.persistencerouting;

import java.lang.reflect.Method;
import java.util.function.IntSupplier;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Pointcut;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Reintenta en otra réplica una lectura que falló a mitad de camino porque su réplica se cayó.
 *
 * <p>Envuelve los métodos {@code @Transactional(readOnly = true)} por fuera del interceptor de
 * transacciones. Si el método falla por un error de <b>conexión</b> (la réplica dejó de
 * responder durante la consulta), se repite en una transacción nueva, que obtiene una conexión
 * nueva: el round-robin la asigna a la siguiente réplica y las réplicas caídas ya no están en la
 * rotación. Los errores de la propia consulta (SQL inválido, datos) no se reintentan.
 *
 * <p>Solo reintenta la transacción más externa (si el método participa en una transacción ya
 * abierta, el error sube hasta ella) y no reintenta las lecturas críticas, que van al primario.
 * Repetir una lectura no tiene efectos secundarios, por eso es seguro.
 */
public class ReadOnlyRetryInterceptor implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyRetryInterceptor.class);

    private static final TransactionAttributeSource TX_ATTRIBUTES = new AnnotationTransactionAttributeSource();

    private final IntSupplier maxAttempts;

    /** @param maxAttempts intentos totales (1 = sin reintento); se lee en cada llamada */
    public ReadOnlyRetryInterceptor(IntSupplier maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** Métodos con {@code @Transactional(readOnly = true)}, en el método o en la clase. */
    public static Pointcut pointcut() {
        return new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                TransactionAttribute attribute = TX_ATTRIBUTES.getTransactionAttribute(method, targetClass);
                return attribute != null && attribute.isReadOnly();
            }
        };
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive() || PrimaryRead.isActive()
                || !(invocation instanceof ProxyMethodInvocation proxyInvocation)) {
            return invocation.proceed();
        }
        int attempts = Math.max(1, maxAttempts.getAsInt());
        for (int attempt = 1; ; attempt++) {
            try {
                // Cada intento recorre de nuevo la cadena de interceptores (nueva transacción).
                return proxyInvocation.invocableClone().proceed();
            } catch (Throwable ex) {
                if (attempt >= attempts || !ConnectionFailures.isConnectionFailure(ex)) {
                    throw ex;
                }
                log.warn("DB retry {}.{}: fallo de conexión en una lectura ({}); intento {}/{} en otra réplica",
                        invocation.getMethod().getDeclaringClass().getSimpleName(), invocation.getMethod().getName(),
                        ConnectionFailures.describe(ex), attempt + 1, attempts);
            }
        }
    }
}
