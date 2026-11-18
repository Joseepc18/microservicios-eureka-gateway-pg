package ec.edu.upse.distribuidas.persistencerouting;

import java.util.List;

/**
 * Política que elige qué réplica atiende una lectura ordinaria.
 *
 * <p>La implementación por defecto es {@link RoundRobinReplicaSelectionPolicy}. Un servicio
 * puede reemplazarla declarando su propio bean de este tipo.
 */
@FunctionalInterface
public interface ReplicaSelectionPolicy {

    /**
     * Elige una réplica.
     *
     * @param replicas réplicas candidatas; nunca vacía
     * @return la réplica que atenderá la lectura
     */
    DataSourceNode select(List<DataSourceNode> replicas);

    /** Nombre corto de la política, para el log de arranque. */
    default String name() {
        return getClass().getSimpleName();
    }
}
