package ec.edu.upse.distribuidas.serviciopedidos.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pedido")
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Valor simple: no hay clave foránea hacia la base de servicio-productos. */
    @Column(name = "producto_id", nullable = false)
    private Long productoId;

    @Column(nullable = false)
    private Integer cantidad;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoPedido estado;

    @Column(nullable = false)
    private Instant fecha;

    protected Pedido() {
    }

    public Pedido(Long productoId, Integer cantidad, BigDecimal total) {
        this.productoId = productoId;
        this.cantidad = cantidad;
        this.total = total;
        this.estado = EstadoPedido.CREADO;
        this.fecha = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProductoId() {
        return productoId;
    }

    public Integer getCantidad() {
        return cantidad;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public EstadoPedido getEstado() {
        return estado;
    }

    public Instant getFecha() {
        return fecha;
    }

    public void cancelar() {
        this.estado = EstadoPedido.CANCELADO;
    }
}
