package ec.edu.upse.distribuidas.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;

import reactor.netty.http.client.HttpClient;

/**
 * El cliente HTTP del Gateway usa una caché DNS corta. El efecto (volver a resolver el nombre
 * de una instancia que cambió de IP) se comprobó en Docker: ver la descripción del PR.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
class ResolucionDnsConfigTests {

    @Autowired
    private List<HttpClientCustomizer> customizers;

    @Test
    void elGatewayRegistraLaCacheDnsCorta() {
        assertThat(customizers).hasSize(1);

        HttpClient base = HttpClient.create();
        HttpClient configurado = customizers.get(0).customize(base);

        // resolver(...) devuelve una configuración nueva: la original queda intacta.
        assertThat(configurado).isNotSameAs(base);
        assertThat(configurado.configuration().resolver()).isNotNull();
    }
}
