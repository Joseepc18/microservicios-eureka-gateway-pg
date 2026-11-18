package ec.edu.upse.distribuidas.serviciopedidos.cliente;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class ClienteProductosConfig {

    /** Nombre lógico en Eureka (spring.application.name de servicio-productos), no un host. */
    static final String BASE_URL = "http://servicio-productos";

    /** Builder con balanceo de carga: resuelve el nombre lógico contra el registro de Eureka. */
    @Bean
    @LoadBalanced
    RestClient.Builder productosRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    ProductosClient productosClient(@LoadBalanced RestClient.Builder builder,
                                    @Value("${productos.client.connect-timeout}") Duration connectTimeout,
                                    @Value("${productos.client.read-timeout}") Duration readTimeout) {
        return crear(builder, BASE_URL, connectTimeout, readTimeout);
    }

    /**
     * Arma el cliente con timeouts y propagación de X-Request-Id. Se usa el cliente HTTP de Java
     * porque HttpURLConnection (el predeterminado de Spring) no soporta el método PATCH.
     */
    static ProductosClient crear(RestClient.Builder builder, String baseUrl,
                                 Duration connectTimeout, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(http);
        fabrica.setReadTimeout(readTimeout);
        RestClient rest = builder
                .baseUrl(baseUrl)
                .requestFactory(fabrica)
                .requestInterceptor(new RequestIdInterceptor())
                .build();
        return new ProductosClient(rest);
    }
}
