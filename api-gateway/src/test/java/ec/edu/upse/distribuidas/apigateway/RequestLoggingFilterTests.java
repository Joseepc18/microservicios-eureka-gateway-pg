package ec.edu.upse.distribuidas.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTests {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void logsRouteTargetInstanceStatusAndDuration(CapturedOutput output) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/productos/7"));
        Route route = Route.async().id("servicio-productos").uri("lb://servicio-productos")
                .predicate(serverWebExchange -> true).build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
                URI.create("http://servicio-productos-2:8082/productos/7"));
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().add(RequestLoggingFilter.INSTANCE_HEADER, "productos-2");

        filter.filter(exchange, serverWebExchange -> Mono.empty()).block();

        assertThat(output.getOut()).contains("GW method=GET path=/api/productos/7 "
                + "route=servicio-productos target=servicio-productos-2:8082 "
                + "instanceId=productos-2 status=200 ms=");
    }

    @Test
    void usesPlaceholdersWhenThereIsNoRouteOrInstance(CapturedOutput output) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/desconocida"));

        filter.filter(exchange, serverWebExchange -> Mono.empty()).block();

        assertThat(output.getOut()).contains("path=/desconocida route=- target=- instanceId=- status=-");
    }
}
