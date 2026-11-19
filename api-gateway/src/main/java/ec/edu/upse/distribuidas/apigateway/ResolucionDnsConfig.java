package ec.edu.upse.distribuidas.apigateway;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caché DNS corta para el cliente HTTP del Gateway (Reactor Netty).
 *
 * <p>Las instancias se anuncian en Eureka por su nombre de servicio de Compose
 * ({@code servicio-productos-1}, ...), y el Gateway resuelve ese nombre al conectar. Reactor Netty
 * guarda cada resolución según el TTL del DNS de Docker, que es largo. Si un contenedor se
 * reinicia y Docker le asigna otra IP (por ejemplo, al detener e iniciar las dos instancias de
 * productos, que pueden intercambiar sus IP), el Gateway seguía conectando a la IP vieja y
 * respondía 500 a todas las solicitudes de ese servicio, aunque el registro de Eureka y el DNS ya
 * estuvieran bien. Con el TTL acotado, el nombre se vuelve a resolver en segundos.
 */
@Configuration(proxyBeanMethods = false)
public class ResolucionDnsConfig {

    @Bean
    HttpClientCustomizer cacheDnsCorta(@Value("${gateway.dns.cache-ttl:5s}") Duration ttl) {
        return httpClient -> httpClient.resolver(spec -> spec
                .cacheMaxTimeToLive(ttl)
                // Un fallo de resolución (contenedor aún sin red) no se recuerda.
                .cacheNegativeTimeToLive(Duration.ZERO));
    }
}
