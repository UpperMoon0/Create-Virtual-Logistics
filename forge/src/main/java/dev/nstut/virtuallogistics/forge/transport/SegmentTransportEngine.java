package dev.nstut.virtuallogistics.forge.transport;

import dev.nstut.virtuallogistics.core.TransportGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Experimental per-segment logical transport clock.
 *
 * Each distinct physical Create route owns a clock for each driven chain leg.
 * A parcel consumes NO work on intermediate ticks: ordered threshold queues
 * wake it only when it crosses a segment boundary (forward or reverse).
 * Distinct routes have independent clocks; breaking one route does not freeze
 * unrelated cargo. Shipments are not rendered, physically cloned or discarded
 * during motion. This model is not yet wired into SavedData/Forge production.
 *
 * A real inventory transaction protocol is required before deployment.
 */
public final class SegmentTransportEngine {
    public enum State { IN_TRANSIT, AT_SOURCE, WAITING_FOR_STORAGE }

    /** A route with positive-distance physical legs; the final leg ends at its Frogport. */
    public record Path(List<TransportGraph.Node> points, List<Double> lengths) {
        public Path {
            points = List.copyOf(points);
            lengths = List.copyOf(lengths);
            if (points.size() < 2 || lengths.size() != points.size()-1)
                throw new IllegalArgumentException("Route must contain one or more segments");
            for (int i=0;i<lengths.size();i++) {
                double distance = lengths.get(i);
                if (!Double.isFinite(distance) || distance <= 0)
                    throw new IllegalArgumentException("Invalid segment distance");
            }
        }
        public int legs() { return lengths.size(); }
        public TransportGraph.Node source(int leg) { return points.get(leg); }
        public double totalDistance() {
            double distance=0;
            for(double length:lengths) distance+=length;
            return distance;
        }
    }

    public record Cargo(UUID id, byte[] bytes, Path route, int leg,
                        double entry, State state, long retryTick) {
        public Cargo {
            Objects.requireNonNull(id);
            Objects.requireNonNull(bytes);
            Objects.requireNonNull(route);
            Objects.requireNonNull(state);
            bytes=bytes.clone();
            if (leg < 0 || leg >= route.legs() || !Double.isFinite(entry))
                throw new IllegalArgumentException("Invalid segment cursor");
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record Snapshot(long lastTick, Map<Path, List<Double>> clocks, List<Cargo> cargo) {
        public Snapshot {
            clocks = Map.copyOf(clocks);
            cargo = List.copyOf(cargo);
        }
    }
    public record DeliveryReport(int attempted, int delivered) {}

    @FunctionalInterface public interface SpeedSource {
        /** Signed Create RPM for the physical conveyor at the start of each leg. */
        double rpm(Path path, int leg);
    }
    @FunctionalInterface public interface Receiver {
        boolean accept(Cargo cargo);
    }

    private static final class Cursor {
        UUID id;
        byte[] bytes;
        Path route;
        int leg;
        double entry;
        State state;
        long retryTick;

        Cursor(Cargo saved) {
            id=saved.id();
            bytes=saved.bytes();
            route=saved.route();
            leg=saved.leg();
            entry=saved.entry();
            state=saved.state();
            retryTick=saved.retryTick();
        }
        Cargo snapshot() {
            return new Cargo(id,bytes,route,leg,entry,state,retryTick);
        }
    }

    private static final class Leg {
        double odometer;
        double previous;
        double delta;
        // Forward crossing occurs at entry+length, reverse crossing at entry.
        final NavigableMap<Double,LinkedHashMap<UUID,Cursor>> forward = new TreeMap<>();
        final NavigableMap<Double,LinkedHashMap<UUID,Cursor>> reverse = new TreeMap<>();

        private static void add(NavigableMap<Double,LinkedHashMap<UUID,Cursor>> events,
                                double threshold, Cursor cursor) {
            events.computeIfAbsent(threshold,ignored->new LinkedHashMap<>()).put(cursor.id,cursor);
        }
        private static void remove(NavigableMap<Double,LinkedHashMap<UUID,Cursor>> events,
                                   double threshold, Cursor cursor) {
            var batch=events.get(threshold);
            if (batch==null || batch.remove(cursor.id)==null)
                throw new IllegalStateException("Corrupt segment event index");
            if(batch.isEmpty()) events.remove(threshold);
        }
        void enqueue(Cursor cursor) {
            add(forward,cursor.entry+cursor.route.lengths().get(cursor.leg),cursor);
            add(reverse,cursor.entry,cursor);
        }
        void remove(Cursor cursor) {
            remove(forward,cursor.entry+cursor.route.lengths().get(cursor.leg),cursor);
            remove(reverse,cursor.entry,cursor);
        }
        Cursor due() {
            if(delta>0 && !forward.isEmpty()
                && forward.firstKey()<=odometer)
                return forward.firstEntry().getValue().values().iterator().next();
            if(delta<0 && !reverse.isEmpty()
                && reverse.lastKey()>=odometer)
                return reverse.lastEntry().getValue().values().iterator().next();
            return null;
        }
        double crossedAt(Cursor cursor) {
            double threshold = delta>0
                ? cursor.entry + cursor.route.lengths().get(cursor.leg)
                : cursor.entry;
            if(delta==0)throw new IllegalStateException("Stationary segment emitted event");
            return Math.max(0,Math.min(1,(threshold-previous)/delta));
        }
    }

    private static final class RouteState {
        final List<Leg> legs;
        final Set<UUID> held = new LinkedHashSet<>();
        RouteState(Path path) {
            legs=new ArrayList<>(path.legs());
            for(int i=0;i<path.legs();i++)legs.add(new Leg());
        }
    }

    private final Map<UUID,Cursor> cargo=new LinkedHashMap<>();
    private final Map<Path,RouteState> routes=new HashMap<>();
    private final Set<UUID> transit=new LinkedHashSet<>();
    private final Set<UUID> ready=new LinkedHashSet<>();
    private final NavigableMap<Long,Set<UUID>> retry=new TreeMap<>();
    private long lastTick;

    public SegmentTransportEngine(long initialTick) {
        if(initialTick<0)throw new IllegalArgumentException("Negative game tick");
        lastTick=initialTick;
    }

    public UUID submit(byte[] bytes,Path route) {
        UUID id=UUID.randomUUID();
        submit(new Cargo(id,bytes,route,0,clock(route,0),State.IN_TRANSIT,0));
        return id;
    }
    public void submit(Cargo shipment) {
        if(cargo.containsKey(shipment.id()))
            throw new IllegalArgumentException("Duplicate cargo identity");
        var cursor=new Cursor(shipment);
        cargo.put(cursor.id,cursor);
        RouteState state=routes.computeIfAbsent(cursor.route,RouteState::new);
        switch(cursor.state) {
            case IN_TRANSIT -> {
                state.legs.get(cursor.leg).enqueue(cursor);
                transit.add(cursor.id);
            }
            case AT_SOURCE -> {
                state.held.add(cursor.id);
                transit.add(cursor.id);
            }
            case WAITING_FOR_STORAGE -> {
                retry.computeIfAbsent(cursor.retryTick,ignored->new LinkedHashSet<>()).add(cursor.id);
                ready.add(cursor.id);
            }
        }
    }

    private double clock(Path path,int leg) {
        var state=routes.get(path);
        return state==null?0:state.legs.get(leg).odometer;
    }

    /**
     * Advance only the unique active segment clocks, then process the boundary
     * events that actually became due. Signed RPM handles real backwards travel
     * across previous legs; speed zero and disconnected routes freeze position.
     */
    public void advance(long nowTick,SpeedSource speed,Predicate<Path> connected) {
        Objects.requireNonNull(speed);
        Objects.requireNonNull(connected);
        if(nowTick<lastTick)throw new IllegalArgumentException("World clock reversed");
        long elapsed=nowTick-lastTick;
        if(elapsed==0)return;

        for(var entry:routes.entrySet()) {
            Path path=entry.getKey();
            RouteState routeState=entry.getValue();
            boolean valid=connected.test(path);
            for(int i=0;i<path.legs();i++) {
                Leg leg=routeState.legs.get(i);
                leg.previous=leg.odometer;
                double rpm=valid?speed.rpm(path,i):0;
                if(!Double.isFinite(rpm))throw new IllegalArgumentException("Invalid conveyor RPM");
                leg.delta=elapsed*rpm/360.0;
                if(!Double.isFinite(leg.delta))throw new IllegalArgumentException("Invalid elapsed movement");
                leg.odometer+=leg.delta;
                if(!Double.isFinite(leg.odometer))throw new IllegalArgumentException("Segment clock overflow");
            }
            // A parcel reversed all the way to its origin is safely parked.
            // Resuming forward motion re-enters it at the beginning of this tick.
            Leg origin=routeState.legs.get(0);
            if(origin.delta>0 && !routeState.held.isEmpty()) {
                for(UUID id:List.copyOf(routeState.held)) {
                    Cursor cursor=cargo.get(id);
                    cursor.leg=0;
                    cursor.entry=origin.previous;
                    cursor.state=State.IN_TRANSIT;
                    origin.enqueue(cursor);
                }
                routeState.held.clear();
            }
        }
        lastTick=nowTick;

        // A parcel may cross multiple short segments in one game tick.
        // Revisit at most (number of legs+1) waves; each event changes stage.
        for(var routeEntry:routes.entrySet()) {
            Path path=routeEntry.getKey();
            RouteState route=routeEntry.getValue();
            int waves=0;
            boolean advanced;
            do {
                advanced=false;
                for(int i=0;i<path.legs();i++) {
                    Leg leg=route.legs.get(i);
                    Cursor cursor;
                    while((cursor=leg.due())!=null) {
                        double at=leg.crossedAt(cursor);
                        leg.remove(cursor);
                        if(leg.delta>0) {
                            if(i+1==path.legs()) {
                                cursor.state=State.WAITING_FOR_STORAGE;
                                cursor.retryTick=nowTick;
                                transit.remove(cursor.id);
                                ready.add(cursor.id);
                                retry.computeIfAbsent(nowTick,ignored->new LinkedHashSet<>())
                                    .add(cursor.id);
                            } else {
                                Leg next=route.legs.get(i+1);
                                cursor.leg=i+1;
                                cursor.entry=next.previous+at*next.delta;
                                next.enqueue(cursor);
                            }
                        } else if(i==0) {
                            cursor.leg=0;
                            cursor.state=State.AT_SOURCE;
                            route.held.add(cursor.id);
                        } else {
                            Leg previous=route.legs.get(i-1);
                            cursor.leg=i-1;
                            cursor.entry=previous.previous+at*previous.delta
                                -path.lengths().get(i-1);
                            previous.enqueue(cursor);
                        }
                        advanced=true;
                    }
                }
                waves++;
            } while(advanced && waves <= path.legs()+1);
        }
    }

    /** Only actual inventory insertions consume the receiver work budget. */
    public DeliveryReport deliver(long nowTick,int budget,long retryTicks,Receiver receiver) {
        if(budget<0 || retryTicks<1)throw new IllegalArgumentException("Invalid receiver budget");
        Objects.requireNonNull(receiver);
        int attempts=0,delivered=0;
        while(attempts<budget && !retry.isEmpty() && retry.firstKey()<=nowTick) {
            var batch=retry.firstEntry().getValue();
            UUID id=batch.iterator().next();
            batch.remove(id);
            if(batch.isEmpty())retry.pollFirstEntry();
            Cursor item=cargo.get(id);
            if(item==null || item.state!=State.WAITING_FOR_STORAGE)
                throw new IllegalStateException("Corrupt receiver retry queue");
            attempts++;
            boolean accepted;
            try { accepted=receiver.accept(item.snapshot()); }
            catch(RuntimeException failure) { accepted=false; }
            if(accepted) {
                cargo.remove(id);
                ready.remove(id);
                delivered++;
            } else {
                item.retryTick=nowTick+retryTicks;
                retry.computeIfAbsent(item.retryTick,ignored->new LinkedHashSet<>()).add(id);
            }
        }
        // Discard idle route clocks only when ALL shipment state is gone.
        if(!cargo.isEmpty()) {
            var livePaths=new HashSet<Path>();
            for(var item:cargo.values()) livePaths.add(item.route);
            routes.keySet().retainAll(livePaths);
        } else routes.clear();
        return new DeliveryReport(attempts,delivered);
    }

    public int size(){return cargo.size();}
    public int inFlight(){return transit.size();}
    public int waiting(){return ready.size();}
    public Collection<Path> activePaths(){return List.copyOf(routes.keySet());}

    public List<Cargo> sample(int limit) {
        if(limit<0)throw new IllegalArgumentException("Negative render cap");
        List<Cargo> selected=new ArrayList<>(Math.min(limit,transit.size()));
        for(UUID id:transit) {
            selected.add(cargo.get(id).snapshot());
            if(selected.size()==limit)break;
        }
        return List.copyOf(selected);
    }
    public double traveled(UUID id) {
        Cursor item=cargo.get(id);
        if(item==null)throw new IllegalArgumentException("Unknown cargo");
        if(item.state==State.WAITING_FOR_STORAGE)return item.route.totalDistance();
        if(item.state==State.AT_SOURCE)return 0;
        double progress=0;
        for(int i=0;i<item.leg;i++)progress+=item.route.lengths().get(i);
        Leg leg=routes.get(item.route).legs.get(item.leg);
        return progress+Math.max(0,Math.min(item.route.lengths().get(item.leg),
            leg.odometer-item.entry));
    }

    public Snapshot snapshot() {
        var clocks=new HashMap<Path,List<Double>>();
        for(var route:routes.entrySet()) {
            var ticks=new ArrayList<Double>();
            for(var leg:route.getValue().legs) ticks.add(leg.odometer);
            clocks.put(route.getKey(),List.copyOf(ticks));
        }
        var parcels=new ArrayList<Cargo>(cargo.size());
        for(Cursor item:cargo.values())parcels.add(item.snapshot());
        return new Snapshot(lastTick,clocks,parcels);
    }

    public static SegmentTransportEngine restore(Snapshot saved,long currentTick) {
        SegmentTransportEngine engine=new SegmentTransportEngine(currentTick);
        for(var entry:saved.clocks().entrySet()) {
            Path path=entry.getKey();
            List<Double> odos=entry.getValue();
            if(odos.size()!=path.legs())throw new IllegalArgumentException("Invalid route clock count");
            RouteState state=new RouteState(path);
            for(int i=0;i<odos.size();i++) {
                double value=odos.get(i);
                if(!Double.isFinite(value))throw new IllegalArgumentException("Invalid route clock");
                state.legs.get(i).odometer=value;
            }
            engine.routes.put(path,state);
        }
        for(Cargo item:saved.cargo()) {
            if(!engine.routes.containsKey(item.route))
                throw new IllegalArgumentException("Shipment without clocks");
            engine.submit(item);
        }
        return engine;
    }
}
