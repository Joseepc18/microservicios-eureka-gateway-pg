package ec.edu.upse.distribuidas.serviciopedidos.cliente;

import ec.edu.upse.distribuidas.serviciopedidos.web.TrazaFilter;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Propaga el X-Request-Id de la solicitud actual hacia servicio-productos. */
public class RequestIdInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String requestId = MDC.get(TrazaFilter.MDC_KEY);
        if (requestId != null) {
            request.getHeaders().set(TrazaFilter.REQUEST_ID_HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
