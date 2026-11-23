package dev.nstut.virtuallogistics.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class VirtualVisualMathTest {

    @Test void followsCornersAtPhysicalDistance() {
        var a=new TransportGraph.Node(0,0,0);
        var b=new TransportGraph.Node(10,0,0);
        var c=new TransportGraph.Node(10,0,10);
        var route=new TransportGraph.Route(List.of(a,b),new TransportGraph.Endpoint(b,c,"test"),20);
        assertEquals(List.of(a,b,c),VirtualVisualMath.routePoints(route));
        assertEquals(new VirtualVisualMath.Point(5.5,.5,.5),VirtualVisualMath.pointAt(List.of(a,b,c),5));
        assertEquals(new VirtualVisualMath.Point(10.5,.5,5.5),VirtualVisualMath.pointAt(List.of(a,b,c),15));
        assertEquals(new VirtualVisualMath.Point(10.5,.5,10.5),VirtualVisualMath.pointAt(List.of(a,b,c),999));
    }

    @Test void zeroLengthAndSingleNodeAreSafe() {
        var a=new TransportGraph.Node(2,3,4);
        var b=new TransportGraph.Node(3,3,4);
        assertEquals(new VirtualVisualMath.Point(2.5,3.5,4.5),VirtualVisualMath.pointAt(List.of(a,a),0));
        assertEquals(new VirtualVisualMath.Point(2.5,3.5,4.5),VirtualVisualMath.pointAt(List.of(a),20));
        assertEquals(new VirtualVisualMath.Point(2.5,3.5,4.5),VirtualVisualMath.pointAt(List.of(a,b),-100));
        assertThrows(IllegalArgumentException.class,()->VirtualVisualMath.pointAt(List.of(),0));
        assertThrows(IllegalArgumentException.class,()->VirtualVisualMath.pointAt(List.of(a),Double.NaN));
    }

    @Test void sourcePortCoincidentNotDuplicated() {
        var a=new TransportGraph.Node(0,0,0);
        var route=new TransportGraph.Route(List.of(a),new TransportGraph.Endpoint(a,a,"x"),0);
        assertEquals(List.of(a),VirtualVisualMath.routePoints(route));
    }
}
