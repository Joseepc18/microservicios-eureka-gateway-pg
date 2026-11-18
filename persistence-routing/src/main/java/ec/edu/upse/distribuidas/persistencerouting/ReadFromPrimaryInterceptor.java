package ec.edu.upse.distribuidas.persistencerouting;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;

/**
 * Aplica {@link ReadFromPrimary}: ejecuta el método anotado como {@link PrimaryRead lectura
 * crítica}. Se registra con mayor precedencia que el interceptor de transacciones, así que la
 * marca ya está puesta cuando la transacción obtiene su conexión.
 */
public class ReadFromPrimaryInterceptor implements MethodInterceptor {

    /** Métodos anotados, o métodos de clases anotadas. */
    public static Pointcut pointcut() {
        return new ComposablePointcut(new AnnotationMatchingPointcut(ReadFromPrimary.class, true))
                .union(new AnnotationMatchingPointcut(null, ReadFromPrimary.class, true));
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        PrimaryRead.enter();
        try {
            return invocation.proceed();
        } finally {
            PrimaryRead.exit();
        }
    }
}
