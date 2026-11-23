package dev.nstut.virtuallogistics.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Server-authoritative transport. Each physical source owns one distance clock; a shipment
 * consumes no per-tick work until its route length is traversed.
 * There is no per-link or per-conveyor package limit.
 */
public final class VirtualTransportEngine {
    public enum Status { IN_TRANSIT, WAITING_FOR_STORAGE }

    public record Shipment(UUID id, byte[] cargo, TransportGraph.Route route,
                           double arrivalDistance, Status status, long retryTick) {
        public Shipment {
            Objects.requireNonNull(id);
            Objects.requireNonNull(cargo);
            Objects.requireNonNull(route);
            Objects.requireNonNull(status);
            cargo = cargo.clone();
            if (!Double.isFinite(arrivalDistance) || arrivalDistance < 0)
                throw new IllegalArgumentException("Invalid arrival distance");
        }
        @Override public byte[] cargo() { return cargo.clone(); }
    }
    public record Snapshot(double odometer, long lastTick, List<Shipment> shipments) {
        public Snapshot { shipments = List.copyOf(shipments); }
    }
    public record DeliveryReport(int attempted, int delivered) {}

    @FunctionalInterface public interface Receiver {
        /** Return true only after one complete, authoritative inventory insertion. */
        boolean accept(Shipment shipment);
    }

    private static final Comparator<Shipment> DUE = Comparator
        .comparingDouble(Shipment::arrivalDistance).thenComparing(Shipment::id);
    private static final Comparator<Shipment> RETRY = Comparator
        .comparingLong(Shipment::retryTick).thenComparing(Shipment::id);

    private final Map<UUID, Shipment> shipments = new HashMap<>();
    private final PriorityQueue<Shipment> inflight = new PriorityQueue<>(DUE);
    private final PriorityQueue<Shipment> waiting = new PriorityQueue<>(RETRY);
    private double odometer;
    private long lastTick;

    public VirtualTransportEngine(long currentTick) {
        if (currentTick < 0) throw new IllegalArgumentException("Negative tick");
        lastTick = currentTick;
    }

    public UUID submit(byte[] cargo, TransportGraph.Route route) {
        UUID id = UUID.randomUUID();
        submit(new Shipment(id, cargo, route, odometer + route.distance(), Status.IN_TRANSIT, 0));
        return id;
    }

    public void submit(Shipment shipment) {
        if (shipments.containsKey(shipment.id())) throw new IllegalArgumentException("Duplicate shipment ID");
        shipments.put(shipment.id(), shipment);
        if (shipment.status() == Status.IN_TRANSIT) inflight.add(shipment);
        else waiting.add(shipment);
    }

    public void clock(long nowTick, double rpm) {
        if (nowTick < lastTick) throw new IllegalArgumentException("World clock moved backwards");
        if (!Double.isFinite(rpm)) throw new IllegalArgumentException("Invalid RPM");
        // Create chain movement: |RPM / 360| blocks per game tick.
        odometer += (nowTick - lastTick) * Math.abs(rpm) / 360.0;
        lastTick = nowTick;
    }

    /** No cargo is discarded on refused insertion or receiver error. */
    public DeliveryReport deliverReady(long nowTick, int budget, long retryDelayTicks, Receiver receiver) {
        if (budget < 0 || retryDelayTicks < 1) throw new IllegalArgumentException("Invalid work budget");
        Objects.requireNonNull(receiver);
        int delivered = 0;
        int processed = 0;
        while (processed < budget) {
            Shipment current = !waiting.isEmpty() && waiting.peek().retryTick() <= nowTick
                ? waiting.poll() : (!inflight.isEmpty() && inflight.peek().arrivalDistance() <= odometer
                    ? inflight.poll() : null);
            if (current == null) break;
            processed++;
            boolean accepted;
            try {
                accepted = receiver.accept(current);
            } catch (RuntimeException ignored) {
                accepted = false;
            }
            if (accepted) {
                shipments.remove(current.id());
                delivered++;
            } else {
                Shipment retry = new Shipment(current.id(), current.cargo(), current.route(),
                    current.arrivalDistance(), Status.WAITING_FOR_STORAGE, nowTick + retryDelayTicks);
                shipments.put(retry.id(), retry);
                waiting.add(retry);
            }
        }
        return new DeliveryReport(processed, delivered);
    }

    public int inFlight() { return inflight.size(); }
    public int waiting() { return waiting.size(); }
    public int size() { return shipments.size(); }
    public double odometer() { return odometer; }
    public Collection<Shipment> shipments() { return List.copyOf(shipments.values()); }

    public Snapshot snapshot() { return new Snapshot(odometer, lastTick, new ArrayList<>(shipments.values())); }

    /** Restore at the current server tick: downtime must never teleport packages. */
    public static VirtualTransportEngine restore(Snapshot snapshot, long currentTick) {
        if (!Double.isFinite(snapshot.odometer()) || snapshot.odometer() < 0)
            throw new IllegalArgumentException("Invalid odometer");
        VirtualTransportEngine engine = new VirtualTransportEngine(currentTick);
        engine.odometer = snapshot.odometer();
        for (Shipment shipment : snapshot.shipments()) engine.submit(shipment);
        return engine;
    }

    /** Deterministic bounded sample. Rendering does not consume or mutate cargo. */
    public List<Shipment> sample(int cap) {
        if (cap < 0) throw new IllegalArgumentException("Negative render cap");
        if (cap == 0) return List.of();
        List<Shipment> selected = new ArrayList<>(Math.min(cap, shipments.size()));
        for (Shipment shipment : shipments.values()) {
            if (shipment.status() != Status.IN_TRANSIT) continue;
            selected.add(shipment);
            if (selected.size() == cap) break;
        }
        return List.copyOf(selected);
    }
}
