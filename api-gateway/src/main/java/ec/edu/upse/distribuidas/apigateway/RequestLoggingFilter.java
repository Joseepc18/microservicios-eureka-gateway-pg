package ec.edu.upse.distribuidas.apigateway;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Registra una línea por solicitud con la ruta, la instancia destino elegida por el
 * LoadBalancer, el código de respuesta y la duración. Es la base de la evidencia de
 * balanceo (P02) y de fallo de instancia (P03).
 *
 * <p>Formato: {@code GW method=GET path=/api/productos route=servicio-productos
 * target=servicio-productos-1:8081 instanceId=productos-1 status=200 ms=12}. El valor de
 * {@code target} depende de cómo cada instancia se anuncia en Eureka (hostname o IP).
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    static final String INSTANCE_HEADER = "X-Instance-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.nanoTime();
        return chain.filter(exchange)
                .doFinally(signal -> logRequest(exchange, start));
    }

    private void logRequest(ServerWebExchange exchange, long startNanos) {
        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        URI target = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        String instanceId = exchange.getResponse().getHeaders().getFirst(INSTANCE_HEADER);

        log.info("GW method={} path={} route={} target={} instanceId={} status={} ms={}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getRawPath(),
                route != null ? route.getId() : "-",
                target != null ? target.getAuthority() : "-",
                instanceId != null ? instanceId : "-",
                status != null ? status.value() : "-",
                millis);
    }

    @Override
    public int getOrder() {
        // Se ejecuta primero para medir la solicitud completa; los atributos se leen al terminar.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
