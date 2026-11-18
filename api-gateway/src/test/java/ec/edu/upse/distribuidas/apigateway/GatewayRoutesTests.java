package ec.edu.upse.distribuidas.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

@SpringBootTest(properties = "eureka.client.enabled=false")
class GatewayRoutesTests {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void routesUseLogicalNamesResolvedByLoadBalancer() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).extracting(Route::getId)
                .containsExactlyInAnyOrder("servicio-productos", "servicio-pedidos");
        assertThat(routes).allSatisfy(route -> assertThat(route.getUri().getScheme()).isEqualTo("lb"));
        assertThat(routes).extracting(route -> route.getUri().getHost())
                .containsExactlyInAnyOrder("servicio-productos", "servicio-pedidos");
    }
}
