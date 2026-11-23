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
}
