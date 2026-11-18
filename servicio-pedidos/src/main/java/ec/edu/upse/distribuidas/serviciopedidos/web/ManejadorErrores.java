package ec.edu.upse.distribuidas.serviciopedidos.web;

import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoCancelableException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.PedidoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ProductoNoEncontradoException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.ServicioProductosNoDisponibleException;
import ec.edu.upse.distribuidas.serviciopedidos.excepcion.StockInsuficienteException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Respuestas de error JSON uniformes: 400, 404, 409, 503 y 500. */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler({PedidoNoEncontradoException.class, ProductoNoEncontradoException.class})
    public ResponseEntity<ErrorResponse> noEncontrado(RuntimeException ex, HttpServletRequest req) {
        return responder(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler({StockInsuficienteException.class, PedidoNoCancelableException.class})
    public ResponseEntity<ErrorResponse> conflicto(RuntimeException ex, HttpServletRequest req) {
        return responder(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(ServicioProductosNoDisponibleException.class)
    public ResponseEntity<ErrorResponse> noDisponible(ServicioProductosNoDisponibleException ex,
                                                      HttpServletRequest req) {
        log.warn("servicio-productos no disponible: {}", ex.getMessage(), ex.getCause());
        return responder(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validacion(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detalle = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return responder(HttpStatus.BAD_REQUEST, detalle, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> cuerpoInvalido(HttpServletRequest req) {
        return responder(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud no es un JSON válido", req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> parametroInvalido(MethodArgumentTypeMismatchException ex,
                                                           HttpServletRequest req) {
        return responder(HttpStatus.BAD_REQUEST, "Valor inválido para '" + ex.getName() + "'", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> inesperado(Exception ex, HttpServletRequest req) {
        // Las excepciones web de Spring (404 de ruta, 405, 415, etc.) ya traen su código HTTP.
        if (ex instanceof org.springframework.web.ErrorResponse web) {
            HttpStatus estado = HttpStatus.valueOf(web.getStatusCode().value());
            return responder(estado, estado.getReasonPhrase(), req);
        }
        log.error("Error no controlado en {}", req.getRequestURI(), ex);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor", req);
    }

    private ResponseEntity<ErrorResponse> responder(HttpStatus estado, String mensaje, HttpServletRequest req) {
        return ResponseEntity.status(estado).body(new ErrorResponse(
                Instant.now(), estado.value(), estado.getReasonPhrase(), mensaje, req.getRequestURI()));
    }
}
