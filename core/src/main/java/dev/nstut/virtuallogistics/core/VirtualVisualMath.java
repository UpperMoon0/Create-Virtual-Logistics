package dev.nstut.virtuallogistics.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Interpolates sampled visual proxies without modifying authoritative transport state. */
public final class VirtualVisualMath {
    private VirtualVisualMath() {}
    public record Point(double x, double y, double z) {}

    public static List<TransportGraph.Node> routePoints(TransportGraph.Route route) {
        List<TransportGraph.Node> points = new ArrayList<>(route.nodes());
        if (!points.get(points.size() - 1).equals(route.endpoint().port()))
            points.add(route.endpoint().port());
        return List.copyOf(points);
    }

    public static Point pointAt(List<TransportGraph.Node> nodes, double distance) {
        Objects.requireNonNull(nodes);
        if (nodes.isEmpty() || !Double.isFinite(distance))
            throw new IllegalArgumentException("Path and distance must be valid");
        if (nodes.size() == 1) return center(nodes.get(0));
        double left = Math.max(0,distance);
        for (int i=1;i<nodes.size();i++) {
            var from=nodes.get(i-1);
            var to=nodes.get(i);
            double len=from.distanceTo(to);
            if (len <= 0) continue;
            double t=Math.min(1,left/len);
            if (left <= len || i==nodes.size()-1)
                return new Point(from.x()+.5+(to.x()-from.x())*t,
                    from.y()+.5+(to.y()-from.y())*t,
                    from.z()+.5+(to.z()-from.z())*t);
            left-=len;
        }
        return center(nodes.get(nodes.size()-1));
    }

    private static Point center(TransportGraph.Node node) {
        return new Point(node.x()+.5,node.y()+.5,node.z()+.5);
    }
}
