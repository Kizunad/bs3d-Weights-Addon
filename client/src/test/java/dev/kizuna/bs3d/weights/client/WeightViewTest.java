package dev.kizuna.bs3d.weights.client;

import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.ContainerWeight;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeightViewTest {
    @Test
    void protobufRoundTripPreservesWholeSnapshot() throws Exception {
        var container = ContainerWeight.newBuilder()
                .setContainerId("bag")
                .setContentsGrams(900)
                .setTotalGrams(1001);
        var message = state("a", 4, 1001).toBuilder()
                .addContainers(container)
                .build();
        var view = new WeightView();
        view.accept(WeightState.parseFrom(message.toByteArray()));
        assertEquals(message, view.state());
    }

    @Test
    void oldRevisionCannotReplaceCurrentAndClearAllowsReconnect() {
        var view = new WeightView();
        view.accept(state("a", 3, 1000));
        view.accept(state("a", 2, 2000));
        assertEquals(1000, view.state().getTotalGrams());
        view.clear();
        assertEquals(Status.STATUS_UNSPECIFIED, view.state().getStatus());
        view.accept(state("b", 1, 3000));
        assertEquals(3000, view.state().getTotalGrams());
    }

    @Test
    void failedCalculationReplacesOldWeight() {
        var view = new WeightView();
        view.accept(state("a", 1, 1000));
        view.accept(state("a", 2, 0).toBuilder().setStatus(Status.INVALID_INVENTORY).build());
        assertEquals(Status.INVALID_INVENTORY, view.state().getStatus());
    }

    @Test
    void invalidPayloadDoesNotReplaceCache() {
        var view = new WeightView();
        view.accept(state("a", 1, 1000));
        assertThrows(IllegalArgumentException.class, () -> view.accept(state("a", 2, -1)));
        assertEquals(1000, view.state().getTotalGrams());
    }

    @Test
    void kilogramDisplayPreservesGrams() {
        assertEquals("1.001 kg", WeightView.kilograms(1001));
        assertEquals("0.001 kg", WeightView.kilograms(1));
        assertEquals("0 kg", WeightView.kilograms(0));
        assertEquals("64000000 kg", WeightView.kilograms(64_000_000_000L));
    }

    private static WeightState state(String session, long revision, long grams) {
        return WeightState.newBuilder()
                .setSession(session)
                .setRevision(revision)
                .setStatus(Status.OK)
                .setTotalGrams(grams)
                .build();
    }
}
