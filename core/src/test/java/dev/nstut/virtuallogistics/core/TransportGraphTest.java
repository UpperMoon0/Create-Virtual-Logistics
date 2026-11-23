package dev.nstut.virtuallogistics.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransportGraphTest {
    private static final TransportGraph.Node A = new TransportGraph.Node(0, 0, 0);
    private static final TransportGraph.Node B = new TransportGraph.Node(10, 0, 0);
    private static final TransportGraph.Node C = new TransportGraph.Node(0, 0, 10);
    private static final TransportGraph.Node D = new TransportGraph.Node(20, 0, 0);
    private static boolean matches(String address, String filter) {
        return filter.equals("*") || address.equals(filter);
    }

    @Test void routesOnShortestWeightedPhysicalPath() {
        var graph = new TransportGraph();
        graph.connect(A,B,10);
        graph.connect(B,D,10);
        graph.connect(A,C,4);
        graph.connect(C,D,4);
        graph.addEndpoint(new TransportGraph.Endpoint(D,new TransportGraph.Node(21,0,0),"0_hno3"));
        var route = graph.route(A,"0_hno3", TransportGraphTest::matches).orElseThrow();
        assertEquals(List.of(A,C,D), route.nodes());
        assertEquals(9.0,route.distance(),0.001);
    }

    @Test void unreachableAndWrongAddressNeverTeleport() {
        var g = new TransportGraph();
        g.addNode(A);
        g.addEndpoint(new TransportGraph.Endpoint(D,D,"destination"));
        assertTrue(g.route(A,"destination",TransportGraphTest::matches).isEmpty());
        assertTrue(g.route(D,"other",TransportGraphTest::matches).isEmpty());
        assertTrue(g.route(B,"destination",TransportGraphTest::matches).isEmpty());
    }

    @Test void picksNearestMatchingPort() {
        var g = new TransportGraph();
        g.connect(A,B,10);
        g.connect(A,C,5);
        g.addEndpoint(new TransportGraph.Endpoint(B,B,"*"));
        g.addEndpoint(new TransportGraph.Endpoint(C,C,"test"));
        assertEquals(C,g.route(A,"test",TransportGraphTest::matches).orElseThrow().endpoint().node());
        assertEquals(B,g.route(A,"other",TransportGraphTest::matches).orElseThrow().endpoint().node());
        assertEquals(3,g.nodes());
        assertEquals(2,g.endpoints());
    }

    @Test void rejectsImpossibleGraphAndRouteStates() {
        var g = new TransportGraph();
        assertThrows(IllegalArgumentException.class,()->g.connect(A,A,1));
        assertThrows(IllegalArgumentException.class,()->g.connect(A,B,0));
        assertThrows(IllegalArgumentException.class,()->g.connect(A,B,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new TransportGraph.Route(List.of(A),new TransportGraph.Endpoint(B,B,"x"),2));
        assertThrows(IllegalArgumentException.class,()->new TransportGraph.Route(List.of(A),new TransportGraph.Endpoint(A,A,"x"),-1));
        assertEquals(5,A.distanceTo(new TransportGraph.Node(3,4,0)));
    }

    @Test void comparatorOrdersCoordinatesLexicographically() {
        var a = new TransportGraph.Node(0,0,0);
        assertTrue(a.compareTo(new TransportGraph.Node(1,0,0)) < 0);
        assertTrue(a.compareTo(new TransportGraph.Node(0,1,0)) < 0);
        assertTrue(a.compareTo(new TransportGraph.Node(0,0,1)) < 0);
        assertTrue(new TransportGraph.Node(0,0,1).compareTo(a) > 0);
        assertEquals(0,a.compareTo(a));
    }

    @Test void removesStaleDijkstraEntriesAndResolvesEqualLengthTies() {
        var g=new TransportGraph();
        var a=new TransportGraph.Node(0,0,0);
        var b=new TransportGraph.Node(1,0,0);
        var c=new TransportGraph.Node(0,0,1);
        var d=new TransportGraph.Node(2,0,0);
        g.connect(a,b,10);
        g.connect(a,c,1);
        g.connect(c,b,1); // expensive entry for b becomes stale
        g.connect(b,d,1);
        g.addEndpoint(new TransportGraph.Endpoint(d,d,"goal"));
        assertEquals(List.of(a,c,b,d),g.route(a,"goal",TransportGraphTest::matches).orElseThrow().nodes());

        var tie=new TransportGraph();
        tie.connect(a,b,1);
        tie.connect(a,c,1);
        tie.addEndpoint(new TransportGraph.Endpoint(b,b,"goal"));
        tie.addEndpoint(new TransportGraph.Endpoint(c,c,"goal"));
        assertEquals(c,tie.route(a,"goal",TransportGraphTest::matches).orElseThrow().endpoint().port());
    }

    @Test void invalidEmptyRouteAndNanDistanceAreRejected() {
        var ep=new TransportGraph.Endpoint(A,A,"x");
        assertThrows(IllegalArgumentException.class,()->new TransportGraph.Route(List.of(),ep,0));
        assertThrows(IllegalArgumentException.class,()->new TransportGraph.Route(List.of(A),ep,Double.NaN));
    }

    @Test void equalDistancesPreferSmallestPortRegardlessOfRegistrationOrder() {
        var a=new TransportGraph.Node(0,0,0);
        var b=new TransportGraph.Node(1,0,0);
        var c=new TransportGraph.Node(0,0,1);
        var g=new TransportGraph();
        g.connect(a,b,1);
        g.connect(a,c,1);
        g.addEndpoint(new TransportGraph.Endpoint(c,c,"goal"));
        g.addEndpoint(new TransportGraph.Endpoint(b,b,"goal"));
        assertEquals(c,g.route(a,"goal",TransportGraphTest::matches).orElseThrow().endpoint().port());
        var fail=new TransportGraph();
        fail.addNode(a);
        fail.addEndpoint(new TransportGraph.Endpoint(a,a,"goal"));
        fail.addEndpoint(new TransportGraph.Endpoint(a,a,"goal"));
        assertEquals(a,fail.route(a,"goal",TransportGraphTest::matches).orElseThrow().endpoint().port());
    }

    @Test void invalidOverflowCostDoesNotCreateImpossibleRoute() {
        var g=new TransportGraph();
        g.connect(A,B,Double.MAX_VALUE);
        g.connect(B,D,Double.MAX_VALUE);
        g.addEndpoint(new TransportGraph.Endpoint(D,D,"goal"));
        assertTrue(g.route(A,"goal",TransportGraphTest::matches).isEmpty());
    }

    @Test void longerMatchingEndpointNeverDisplacesNearest() {
        var g=new TransportGraph();
        g.connect(A,C,5);
        g.connect(A,B,10);
        g.addEndpoint(new TransportGraph.Endpoint(C,C,"goal"));
        g.addEndpoint(new TransportGraph.Endpoint(B,B,"goal"));
        assertEquals(C,g.route(A,"goal",TransportGraphTest::matches).orElseThrow().endpoint().port());
    }
}
