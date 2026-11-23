package dev.nstut.virtuallogistics.core;

import java.util.List;
import java.util.UUID;

/**
 * Repeatable standalone transport-engine workload; no Forge, world generation
 * or remote production service is involved. Does NOT measure Minecraft MSPT,
 * stock Create, Fluid Logistics, client FPS or network bandwidth.
 */
public final class TransportBenchmark {
    private static final TransportGraph.Node SOURCE = new TransportGraph.Node(0, 64, 0);
    private static final TransportGraph.Node DEST = new TransportGraph.Node(100, 64, 0);
    private static final TransportGraph.Route ROUTE = new TransportGraph.Route(
        List.of(SOURCE, DEST), new TransportGraph.Endpoint(DEST, DEST, "benchmark"), 100);
    private static final int DELIVERY_BUDGET = 128;

    private TransportBenchmark() {}

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static void run(int count) {
        VirtualTransportEngine engine = new VirtualTransportEngine(0);
        byte[] payload = new byte[64];
        long startHeap = usedHeap();
        long start = System.nanoTime();
        for (int i = 0; i < count; ++i) {
            // Each package has independent identity and immutable payload data.
            payload[0] = (byte) i;
            UUID id = new UUID(0, i);
            engine.submit(new VirtualTransportEngine.Shipment(id, payload, ROUTE,
                100, VirtualTransportEngine.Status.IN_TRANSIT, 0));
        }
        long afterEnqueue = System.nanoTime();
        if (engine.size() != count || engine.sample(24).size() != Math.min(24, count))
            throw new AssertionError("Enqueue or sample conservation failed");

        // Movement is event-driven: a single shared clock step advances every package.
        engine.clock(99, 360);
        if (engine.deliverReady(99, DELIVERY_BUDGET, 20, s -> true).attempted() != 0)
            throw new AssertionError("Delivered before actual travel time");
        engine.clock(100, 360);
        long afterTravel = System.nanoTime();
        int[] received = {0};
        long now = 100;
        while (engine.size() != 0) {
            var report = engine.deliverReady(now, DELIVERY_BUDGET, 20, shipment -> {
                if (shipment.cargo().length != 64) throw new AssertionError("Cargo truncated");
                received[0]++;
                return true;
            });
            if (report.attempted() == 0) throw new AssertionError("Stalled delivery");
            now++;
        }
        long afterDrain = System.nanoTime();
        if (received[0] != count || engine.inFlight() != 0 || engine.waiting() != 0)
            throw new AssertionError("Lost or duplicated cargo");

        long heapDelta = Math.max(0, usedHeap() - startHeap);
        System.out.printf("%d,%.3f,%.3f,%.3f,%d,%.2f,%.2f,%d,OK%n",
            count, (afterEnqueue - start) / 1e6, (afterTravel - afterEnqueue) / 1e6,
            (afterDrain - afterTravel) / 1e6, now - 100,
            (double) count / (now - 100), heapDelta / (1024.0 * 1024.0),
            Math.min(24, count));
    }

    public static void main(String[] args) {
        System.out.println("logical_packages,enqueue_ms,advance_ms,drain_ms,drain_ticks,delivered_per_tick,heap_delta_mib,render_sample,conservation");
        for (int count : new int[]{1_000, 10_000, 100_000}) run(count);
    }
}
