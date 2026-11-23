package dev.nstut.virtuallogistics.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VirtualTransportEngineTest {
    private static final TransportGraph.Node A = new TransportGraph.Node(0,0,0);
    private static final TransportGraph.Node B = new TransportGraph.Node(20,0,0);
    private static final TransportGraph.Route ROUTE = new TransportGraph.Route(
        List.of(A,B),new TransportGraph.Endpoint(B,B,"dest"),20);

    @Test void enforcesPhysicalTravelTimeWithZeroCongestion() {
        var engine = new VirtualTransportEngine(100);
        for (int i=0;i<10_000;i++) engine.submit(new byte[]{(byte)i}, ROUTE);
        assertEquals(10_000,engine.inFlight());
        engine.clock(119,360); // 19 blocks at 1 block per tick
        assertEquals(0,engine.deliverReady(119,10_000,20,s -> true).delivered());
        engine.clock(120,360);
        assertEquals(10_000,engine.deliverReady(120,10_000,20,s -> true).delivered());
        assertEquals(0,engine.size());
    }

    @Test void stoppedNetworkFreezesArrival() {
        var engine = new VirtualTransportEngine(0);
        engine.submit(new byte[]{42},ROUTE);
        engine.clock(200,0);
        assertEquals(0,engine.deliverReady(200,10,20,s->true).delivered());
        engine.clock(219,-360);
        assertEquals(0,engine.deliverReady(219,10,20,s->true).delivered());
        engine.clock(220,-360);
        assertEquals(1,engine.deliverReady(220,10,20,s->true).delivered());
    }

    @Test void destFullDoesNotDropCargoAndRetryIsBounded() {
        var engine=new VirtualTransportEngine(0);
        var id=engine.submit(new byte[]{17,18,19},ROUTE);
        engine.clock(30,360);
        assertEquals(0,engine.deliverReady(30,1,10,s->false).delivered());
        assertEquals(1,engine.waiting());
        assertEquals(0,engine.deliverReady(35,1,10,s->true).delivered());
        assertEquals(1,engine.deliverReady(40,1,10,s->{ assertEquals(id,s.id()); assertArrayEquals(new byte[]{17,18,19},s.cargo()); return true; }).delivered());
        assertEquals(0,engine.size());
    }

    @Test void receiverFailureNeverDiscardsShipment() {
        var engine=new VirtualTransportEngine(0);
        engine.submit(new byte[]{2},ROUTE);
        engine.clock(30,360);
        assertEquals(0,engine.deliverReady(30,5,5,s->{throw new IllegalStateException("inventory inaccessible");}).delivered());
        assertEquals(1,engine.size());
        assertEquals(1,engine.deliverReady(35,5,5,s->true).delivered());
    }

    @Test void workBudgetKeepsServerTickBounded() {
        var engine=new VirtualTransportEngine(0);
        for(int i=0;i<1000;i++) engine.submit(new byte[]{1},ROUTE);
        engine.clock(100,360);
        assertEquals(25,engine.deliverReady(100,25,1,s->true).delivered());
        assertEquals(975,engine.inFlight());
    }

    @Test void snapshotRestoreDoesNotAdvanceDuringShutdown() {
        var engine=new VirtualTransportEngine(5);
        engine.submit(new byte[]{1},ROUTE);
        engine.clock(15,360);
        var saved=engine.snapshot();
        var restored=VirtualTransportEngine.restore(saved,200_000);
        assertEquals(10.0,restored.odometer());
        assertEquals(0,restored.deliverReady(200_000,1,20,s->true).delivered());
        restored.clock(200_010,360);
        assertEquals(1,restored.deliverReady(200_010,1,20,s->true).delivered());
    }

    @Test void renderSamplingNeverTouchesActualCargo() {
        var engine=new VirtualTransportEngine(0);
        for (int i=0;i<100;i++) engine.submit(new byte[]{5},ROUTE);
        assertEquals(16,engine.sample(16).size());
        assertEquals(0,engine.sample(0).size());
        assertEquals(100,engine.size());
        assertThrows(IllegalArgumentException.class,()->engine.sample(-1));
    }

    @Test void cargoCopyPreventsExternalMutation() {
        var engine=new VirtualTransportEngine(0);
        byte[] source={1,2,3};
        engine.submit(source,ROUTE);
        source[0]=50;
        var shipment=engine.sample(1).get(0);
        byte[] retrieved=shipment.cargo();retrieved[1]=77;
        assertArrayEquals(new byte[]{1,2,3},engine.sample(1).get(0).cargo());
    }

    @Test void invalidOperationsCannotCorruptLedger() {
        var engine=new VirtualTransportEngine(0);
        assertThrows(IllegalArgumentException.class,()->new VirtualTransportEngine(-1));
        assertThrows(IllegalArgumentException.class,()->engine.clock(-1,100));
        assertThrows(IllegalArgumentException.class,()->engine.clock(1,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->engine.deliverReady(0,-1,10,s->true).delivered());
        assertThrows(IllegalArgumentException.class,()->engine.deliverReady(0,1,0,s->true).delivered());
        assertThrows(IllegalArgumentException.class,()->VirtualTransportEngine.restore(
            new VirtualTransportEngine.Snapshot(-1,0,List.of()),1));
        var id=UUID.randomUUID();
        var shipment=new VirtualTransportEngine.Shipment(id,new byte[]{1},ROUTE,20,VirtualTransportEngine.Status.IN_TRANSIT,0);
        engine.submit(shipment);
        assertThrows(IllegalArgumentException.class,()->engine.submit(shipment));
    }
}
