package ec.edu.upse.distribuidas.serviciopedidos.web;

import ec.edu.upse.distribuidas.serviciopedidos.dominio.Pedido;
import ec.edu.upse.distribuidas.serviciopedidos.servicio.PedidoService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/pedidos")
public class PedidoController {

    private final PedidoService servicio;

    public PedidoController(PedidoService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<PedidoResponse> crear(@Valid @RequestBody PedidoRequest datos) {
        PedidoResponse creado = PedidoResponse.de(servicio.crear(datos.productoId(), datos.cantidad()));
        return ResponseEntity.created(URI.create("/pedidos/" + creado.id())).body(creado);
    }

    /**
     * Obtiene un pedido. Por defecto es una lectura ordinaria, atendida por una réplica
     * ({@code consistencia=eventual}). Con {@code consistencia=fuerte} es una lectura crítica,
     * atendida por el primario: la que debe usar quien acaba de crear o cancelar el pedido.
     */
    @GetMapping("/{id}")
    public PedidoResponse obtener(@PathVariable Long id,
                                  @RequestParam(defaultValue = "eventual") String consistencia) {
        Pedido pedido = switch (consistencia) {
            case "eventual" -> servicio.obtener(id);
            case "fuerte" -> servicio.obtenerDesdePrimario(id);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "consistencia debe ser 'eventual' o 'fuerte'");
        };
        return PedidoResponse.de(pedido);
    }

    @GetMapping
    public List<PedidoResponse> listar() {
        return servicio.listar().stream().map(PedidoResponse::de).toList();
    }

    @PatchMapping("/{id}/cancelar")
    public PedidoResponse cancelar(@PathVariable Long id) {
        return PedidoResponse.de(servicio.cancelar(id));
    }
}
