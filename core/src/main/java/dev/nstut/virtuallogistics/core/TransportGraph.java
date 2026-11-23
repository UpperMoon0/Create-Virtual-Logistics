package dev.nstut.virtuallogistics.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiPredicate;

/** Immutable route results from a snapshot of the loaded, physically connected Create graph. */
public final class TransportGraph {
    public record Node(int x, int y, int z) implements Comparable<Node> {
        @Override public int compareTo(Node other) {
            int a = Integer.compare(x, other.x);
            if (a != 0) return a;
            a = Integer.compare(y, other.y);
            return a != 0 ? a : Integer.compare(z, other.z);
        }
        public double distanceTo(Node other) {
            long dx = (long)x - other.x, dy = (long)y - other.y, dz = (long)z - other.z;
            return Math.sqrt((double)dx * dx + (double)dy * dy + (double)dz * dz);
        }
    }

    public record Endpoint(Node node, Node port, String address) {
        public Endpoint {
            Objects.requireNonNull(node);
            Objects.requireNonNull(port);
            Objects.requireNonNull(address);
        }
    }
    public record Route(List<Node> nodes, Endpoint endpoint, double distance) {
        public Route {
            nodes = List.copyOf(nodes);
            if (nodes.isEmpty() || !nodes.get(nodes.size() - 1).equals(endpoint.node()))
                throw new IllegalArgumentException("Route must terminate at endpoint node");
            if (!Double.isFinite(distance) || distance < 0)
                throw new IllegalArgumentException("Invalid distance");
        }
    }

    private record Edge(Node to, double length) {}
    private record State(Node node, double cost) {}
    private final Map<Node, List<Edge>> edges = new HashMap<>();
    private final List<Endpoint> endpoints = new ArrayList<>();

    public void addNode(Node node) {
        edges.computeIfAbsent(Objects.requireNonNull(node), ignored -> new ArrayList<>());
    }

    public void connect(Node a, Node b, double length) {
        if (a.equals(b) || !Double.isFinite(length) || length <= 0)
            throw new IllegalArgumentException("Only distinct nodes and positive finite distances");
        addNode(a);
        addNode(b);
        edges.get(a).add(new Edge(b, length));
        edges.get(b).add(new Edge(a, length));
    }

    public void addEndpoint(Endpoint endpoint) {
        addNode(endpoint.node());
        endpoints.add(endpoint);
    }

    public int nodes() { return edges.size(); }
    public int endpoints() { return endpoints.size(); }

    /** Dijkstra shortest route, deterministic for equal-weight paths. */
    public Optional<Route> route(Node source, String packageAddress, BiPredicate<String, String> matches) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(packageAddress);
        Objects.requireNonNull(matches);
        if (!edges.containsKey(source)) return Optional.empty();

        Map<Node, Double> distance = new HashMap<>();
        Map<Node, Node> previous = new HashMap<>();
        Set<Node> settled = new HashSet<>();
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator
            .comparingDouble(State::cost).thenComparing(State::node));
        distance.put(source, 0.0);
        queue.add(new State(source, 0.0));
        while (!queue.isEmpty()) {
            State entry = queue.poll();
            if (!settled.add(entry.node())) continue;
            for (Edge edge : edges.getOrDefault(entry.node(), List.of())) {
                if (settled.contains(edge.to())) continue;
                double candidate = entry.cost() + edge.length();
                double existing = distance.getOrDefault(edge.to(), Double.POSITIVE_INFINITY);
                if (candidate < existing) {
                    distance.put(edge.to(), candidate);
                    previous.put(edge.to(), entry.node());
                    queue.add(new State(edge.to(), candidate));
                }
            }
        }

        Endpoint winner = null;
        double best = Double.POSITIVE_INFINITY;
        for (Endpoint endpoint : endpoints) {
            if (!matches.test(packageAddress, endpoint.address())) continue;
            Double cost = distance.get(endpoint.node());
            if (cost == null) continue;
            double total = cost + endpoint.node().distanceTo(endpoint.port());
            if (total < best || (Double.compare(total, best) == 0 &&
                winner != null && endpoint.port().compareTo(winner.port()) < 0)) {
                best = total;
                winner = endpoint;
            }
        }
        if (winner == null) return Optional.empty();
        List<Node> reversed = new ArrayList<>();
        for (Node at = winner.node(); at != null; at = previous.get(at)) reversed.add(at);
        java.util.Collections.reverse(reversed);
        return Optional.of(new Route(reversed, winner, best));
    }
}
