package ec.edu.upse.distribuidas.persistencerouting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RoundRobinReplicaSelectionPolicyTests {

    private static List<DataSourceNode> replicas(String... names) {
        List<DataSourceNode> nodes = new ArrayList<>();
        for (String name : names) {
            nodes.add(new DataSourceNode(name, DataSourceRole.REPLICA, new RecordingDataSource(name, new ArrayList<>())));
        }
        return nodes;
    }

    private static List<String> select(ReplicaSelectionPolicy policy, List<DataSourceNode> replicas, int times) {
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            chosen.add(policy.select(replicas).getName());
        }
        return chosen;
    }

    @Test
    void tenSelectionsAlternateBetweenTwoReplicasInOrder() {
        List<String> chosen = select(new RoundRobinReplicaSelectionPolicy(), replicas("replica1", "replica2"), 10);

        assertThat(chosen).containsExactly("replica1", "replica2", "replica1", "replica2", "replica1",
                "replica2", "replica1", "replica2", "replica1", "replica2");
    }

    @Test
    void cyclesThroughEveryReplicaWhenThereAreMoreThanTwo() {
        List<String> chosen = select(new RoundRobinReplicaSelectionPolicy(), replicas("r1", "r2", "r3"), 6);

        assertThat(chosen).containsExactly("r1", "r2", "r3", "r1", "r2", "r3");
    }

    @Test
    void aSingleReplicaReceivesEveryRead() {
        assertThat(select(new RoundRobinReplicaSelectionPolicy(), replicas("replica1"), 3))
                .containsOnly("replica1");
    }

    @Test
    void keepsAlternatingWhenTheCounterOverflows() {
        RoundRobinReplicaSelectionPolicy policy = new RoundRobinReplicaSelectionPolicy(Integer.MAX_VALUE - 1);

        List<String> chosen = select(policy, replicas("replica1", "replica2"), 4);

        // Turnos MAX-1, MAX, MIN, MIN+1: floorMod los deja en 0, 1, 0, 1. Con el operador %, el
        // turno MIN daría un índice negativo y una excepción.
        assertThat(chosen).containsExactly("replica1", "replica2", "replica1", "replica2");
    }

    @Test
    void distributesEvenlyUnderConcurrency() throws InterruptedException {
        RoundRobinReplicaSelectionPolicy policy = new RoundRobinReplicaSelectionPolicy();
        List<DataSourceNode> nodes = replicas("replica1", "replica2");
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 1000; i++) {
            executor.submit(() -> counts.computeIfAbsent(policy.select(nodes).getName(), k -> new AtomicInteger())
                    .incrementAndGet());
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(counts.get("replica1")).hasValue(500);
        assertThat(counts.get("replica2")).hasValue(500);
    }

    @Test
    void rejectsAnEmptyListOfReplicas() {
        assertThatThrownBy(() -> new RoundRobinReplicaSelectionPolicy().select(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
