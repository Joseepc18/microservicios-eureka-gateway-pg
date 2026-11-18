package ec.edu.upse.distribuidas.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

@SpringBootTest(properties = "eureka.client.enabled=false")
class GatewayRoutesTests {

    @Autowired
    private RouteLocator routeLocator;

    private List<Route> routes() {
        return routeLocator.getRoutes().collectList().block();
    }

    private boolean matches(Route route, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        return Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchange)).block());
    }

    private Route route(String id) {
        return routes().stream().filter(route -> route.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void routesUseLogicalNamesResolvedByLoadBalancer() {
        assertThat(routes()).extracting(Route::getId)
                .containsExactlyInAnyOrder("servicio-productos", "servicio-pedidos");
        assertThat(routes()).allSatisfy(route -> assertThat(route.getUri().getScheme()).isEqualTo("lb"));
        assertThat(route("servicio-productos").getUri().getHost()).isEqualTo("servicio-productos");
        assertThat(route("servicio-pedidos").getUri().getHost()).isEqualTo("servicio-pedidos");
    }

    @Test
    void eachPathPrefixMatchesOnlyItsOwnRoute() {
        Route productos = route("servicio-productos");
        Route pedidos = route("servicio-pedidos");

        assertThat(matches(productos, "/api/productos")).isTrue();
        assertThat(matches(productos, "/api/productos/42")).isTrue();
        assertThat(matches(productos, "/api/pedidos/42")).isFalse();

        assertThat(matches(pedidos, "/api/pedidos/42")).isTrue();
        assertThat(matches(pedidos, "/api/productos/42")).isFalse();

        assertThat(matches(productos, "/api/productosX")).isFalse();
        assertThat(matches(pedidos, "/otra/ruta")).isFalse();
    }

    @Test
    void routesStripThePrefixAndShareTheGetOnlyRetry() {
        for (Route route : routes()) {
            List<String> filters = route.getFilters().stream().map(Object::toString).toList();

            assertThat(filters).anyMatch(filter -> filter.contains("StripPrefix"));
            assertThat(filters).anyMatch(filter -> filter.contains("Retry") && filter.contains("GET"));
        }
    }
}
