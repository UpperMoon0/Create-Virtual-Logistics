package dev.nstut.virtuallogistics.forge;

import dev.nstut.virtuallogistics.core.TransportGraph;
import dev.nstut.virtuallogistics.core.VirtualTransportEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** World-local, persistent ownership journal. Vanilla world saves persist this with other SavedData. */
public final class VirtualLedger extends SavedData {
    public record Pending(UUID id, TransportGraph.Node source, byte[] cargo) {
        public Pending { cargo = cargo.clone(); }
        @Override public byte[] cargo() { return cargo.clone(); }
    }

    public final Map<UUID, Pending> pending = new LinkedHashMap<>();
    public final Map<TransportGraph.Node, VirtualTransportEngine> active = new HashMap<>();
    private final Map<TransportGraph.Node, VirtualTransportEngine.Snapshot> unloaded = new HashMap<>();

    public static VirtualLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(VirtualLedger::load, VirtualLedger::new,
            "create_virtual_logistics");
    }

    public void capture(TransportGraph.Node start, byte[] cargo) {
        UUID id = UUID.randomUUID();
        pending.put(id, new Pending(id, start, cargo));
        setDirty();
    }

    public VirtualTransportEngine engine(TransportGraph.Node source, long currentTick) {
        return active.computeIfAbsent(source, key -> {
            var snapshot = unloaded.remove(key);
            return snapshot == null
                ? new VirtualTransportEngine(currentTick)
                : VirtualTransportEngine.restore(snapshot, currentTick);
        });
    }

    public int size() {
        int n = pending.size();
        for (var engine : active.values()) n += engine.size();
        for (var snapshot : unloaded.values()) n += snapshot.shipments().size();
        return n;
    }

    public static VirtualLedger load(CompoundTag tag) {
        VirtualLedger ledger = new VirtualLedger();
        for (Tag raw : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag)raw;
            var pending = new Pending(item.getUUID("Id"), readNode(item.getCompound("Source")),
                item.getByteArray("Cargo"));
            ledger.pending.put(pending.id(), pending);
        }
        for (Tag raw : tag.getList("Engines", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag)raw;
            TransportGraph.Node source = readNode(entry.getCompound("Source"));
            List<VirtualTransportEngine.Shipment> shipments = new ArrayList<>();
            for (Tag sr : entry.getList("Shipments", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag)sr;
                var endpoint = new TransportGraph.Endpoint(readNode(s.getCompound("EndNode")),
                    readNode(s.getCompound("Port")), s.getString("Address"));
                List<TransportGraph.Node> nodes = new ArrayList<>();
                for (Tag nr : s.getList("Path", Tag.TAG_COMPOUND))
                    nodes.add(readNode((CompoundTag)nr));
                var route = new TransportGraph.Route(nodes, endpoint, s.getDouble("Distance"));
                var shipment = new VirtualTransportEngine.Shipment(s.getUUID("Id"),
                    s.getByteArray("Cargo"), route, s.getDouble("Arrival"),
                    VirtualTransportEngine.Status.valueOf(s.getString("Status")), s.getLong("RetryTick"));
                shipments.add(shipment);
            }
            ledger.unloaded.put(source,
                new VirtualTransportEngine.Snapshot(entry.getDouble("Odometer"),
                    entry.getLong("LastTick"), shipments));
        }
        return ledger;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag pendingTag = new ListTag();
        for (var item : pending.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", item.id());
            t.put("Source", writeNode(item.source()));
            t.putByteArray("Cargo", item.cargo());
            pendingTag.add(t);
        }
        tag.put("Pending", pendingTag);
        ListTag engines = new ListTag();
        Map<TransportGraph.Node, VirtualTransportEngine.Snapshot> snapshots = new HashMap<>(unloaded);
        active.forEach((node,engine) -> snapshots.put(node, engine.snapshot()));
        for (var entry : snapshots.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.put("Source", writeNode(entry.getKey()));
            t.putDouble("Odometer", entry.getValue().odometer());
            t.putLong("LastTick", entry.getValue().lastTick());
            ListTag shipments = new ListTag();
            for (var s : entry.getValue().shipments()) {
                CompoundTag item = new CompoundTag();
                item.putUUID("Id", s.id());
                item.putByteArray("Cargo", s.cargo());
                item.putDouble("Arrival", s.arrivalDistance());
                item.putString("Status", s.status().name());
                item.putLong("RetryTick", s.retryTick());
                item.put("EndNode", writeNode(s.route().endpoint().node()));
                item.put("Port", writeNode(s.route().endpoint().port()));
                item.putString("Address", s.route().endpoint().address());
                item.putDouble("Distance", s.route().distance());
                ListTag path = new ListTag();
                s.route().nodes().forEach(node -> path.add(writeNode(node)));
                item.put("Path", path);
                shipments.add(item);
            }
            t.put("Shipments", shipments);
            engines.add(t);
        }
        tag.put("Engines", engines);
        return tag;
    }

    static CompoundTag writeNode(TransportGraph.Node node) {
        CompoundTag t = new CompoundTag();
        t.putInt("X",node.x());
        t.putInt("Y",node.y());
        t.putInt("Z",node.z());
        return t;
    }
    static TransportGraph.Node readNode(CompoundTag tag) {
        return new TransportGraph.Node(tag.getInt("X"),tag.getInt("Y"),tag.getInt("Z"));
    }
    static TransportGraph.Node node(BlockPos pos) {
        return new TransportGraph.Node(pos.getX(),pos.getY(),pos.getZ());
    }
    static BlockPos block(TransportGraph.Node node) {
        return new BlockPos(node.x(),node.y(),node.z());
    }
}
