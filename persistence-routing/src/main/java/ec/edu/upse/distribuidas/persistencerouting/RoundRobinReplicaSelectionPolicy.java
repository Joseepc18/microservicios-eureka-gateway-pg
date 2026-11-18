package ec.edu.upse.distribuidas.persistencerouting;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin: las lecturas se asignan por turnos a las réplicas, en el orden en que están
 * configuradas (replica1, replica2, replica1, ...).
 *
 * <p>El contador es atómico, así que el reparto es correcto aunque varias peticiones lleguen a
 * la vez. Se usa {@link Math#floorMod(int, int)} para que el turno siga siendo válido cuando el
 * contador da la vuelta al llegar a {@link Integer#MAX_VALUE}.
 */
public class RoundRobinReplicaSelectionPolicy implements ReplicaSelectionPolicy {

    private final AtomicInteger counter;

    public RoundRobinReplicaSelectionPolicy() {
        this(0);
    }

    RoundRobinReplicaSelectionPolicy(int initialValue) {
        this.counter = new AtomicInteger(initialValue);
    }

    @Override
    public DataSourceNode select(List<DataSourceNode> replicas) {
        if (replicas.isEmpty()) {
            throw new IllegalArgumentException("No hay réplicas entre las que elegir");
        }
        return replicas.get(Math.floorMod(counter.getAndIncrement(), replicas.size()));
    }

    @Override
    public String name() {
        return "round-robin";
    }
}
