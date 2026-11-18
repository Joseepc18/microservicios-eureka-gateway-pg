package ec.edu.upse.distribuidas.serviciopedidos.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Trazabilidad de cada solicitud: usa el X-Request-Id recibido (o genera uno), lo deja en el MDC
 * para que aparezca en todos los logs, lo devuelve en la respuesta y lo propaga a servicio-productos.
 * También agrega X-Instance-Id para identificar la instancia que respondió.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrazaFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String INSTANCE_ID_HEADER = "X-Instance-Id";
    public static final String MDC_KEY = "requestId";

    /** Solo se acepta un identificador simple, para no registrar contenido arbitrario en los logs. */
    private static final Pattern ID_VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Logger log = LoggerFactory.getLogger(TrazaFilter.class);

    private final String instanceId;

    public TrazaFilter(@Value("${instance.id}") String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String recibido = request.getHeader(REQUEST_ID_HEADER);
        String requestId = recibido != null && ID_VALIDO.matcher(recibido).matches()
                ? recibido : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(INSTANCE_ID_HEADER, instanceId);
        long inicio = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("REQ method={} path={} status={} ms={}", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), (System.nanoTime() - inicio) / 1_000_000);
            MDC.remove(MDC_KEY);
        }
    }
}
