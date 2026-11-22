package ec.edu.upse.distribuidas.apigateway;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import reactor.netty.http.client.HttpClient;

/**
 * Caché DNS corta para los clientes HTTP del Gateway (Reactor Netty).
 *
 * <p>Las instancias se anuncian en Eureka por su nombre de servicio de Compose
 * ({@code servicio-productos-1}, ...), y el Gateway resuelve ese nombre al conectar. Reactor Netty
 * guarda cada resolución según el TTL del DNS de Docker, que es largo. Si un contenedor se
 * reinicia y Docker le asigna otra IP (por ejemplo, al detener e iniciar las dos instancias de
 * productos, que pueden intercambiar sus IP), el Gateway seguía conectando a la IP vieja aunque el
 * registro de Eureka y el DNS ya estuvieran bien. Con el TTL acotado, el nombre se vuelve a
 * resolver en segundos.
 *
 * <p>Hay dos clientes que resuelven esos nombres y los dos necesitan el ajuste:
 * <ul>
 *   <li>El que reenvía las solicitudes a las instancias ({@link HttpClientCustomizer}).</li>
 *   <li>El {@code WebClient} con el que el health-check del LoadBalancer consulta
 *       {@code /actuator/health} de cada instancia ({@link WebClientCustomizer}). Sin él, una
 *       instancia que vuelve con otra IP no pasa nunca el health-check y el Gateway deja de usarla
 *       (o responde 503 si le pasa a todas), aunque esté sana.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ResolucionDnsConfig {

    @Bean
    HttpClientCustomizer cacheDnsCorta(@Value("${gateway.dns.cache-ttl:5s}") Duration ttl) {
        return httpClient -> conCacheDnsCorta(httpClient, ttl);
    }

    @Bean
    WebClientCustomizer cacheDnsCortaWebClient(@Value("${gateway.dns.cache-ttl:5s}") Duration ttl) {
        return builder -> builder.clientConnector(new ReactorClientHttpConnector(conCacheDnsCorta(HttpClient.create(), ttl)));
    }

    static HttpClient conCacheDnsCorta(HttpClient httpClient, Duration ttl) {
        return httpClient.resolver(spec -> spec
                .cacheMaxTimeToLive(ttl)
                // Un fallo de resolución (contenedor aún sin red) no se recuerda.
                .cacheNegativeTimeToLive(Duration.ZERO));
    }
}
