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
    /**
     * Malformed or unparseable records remain persistently owned by the world.
     * They must NEVER be discarded or automatically replayed into an inventory.
     */
    public final Map<UUID, CompoundTag> quarantined = new LinkedHashMap<>();
    public final Map<TransportGraph.Node, VirtualTransportEngine> active = new HashMap<>();
    private final Map<TransportGraph.Node, VirtualTransportEngine.Snapshot> unloaded = new HashMap<>();

    public static VirtualLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(VirtualLedger::load, VirtualLedger::new,
            "create_virtual_logistics");
    }

    public UUID capture(TransportGraph.Node start, byte[] cargo) {
        UUID id = UUID.randomUUID();
        pending.put(id, new Pending(id, start, cargo));
        setDirty();
        return id;
    }

    /** Transfer a captured shipment from pending to in-flight in the SAME SavedData.
     *  No physical inventory is altered and no parcel is made more than once. */
    public boolean promote(UUID id, TransportGraph.Route route, long nowTick) {
        Pending item = pending.get(id);
        if (item == null) return false;
        var engine = engine(item.source(), nowTick);
        engine.submit(new VirtualTransportEngine.Shipment(id, item.cargo(), route,
            engine.odometer() + route.distance(),
            VirtualTransportEngine.Status.IN_TRANSIT, 0));
        pending.remove(id);
        setDirty();
        return true;
    }

    public void quarantine(Pending pending, String reason) {
        CompoundTag record = new CompoundTag();
        record.putUUID("Id", pending.id());
        record.put("Source", writeNode(pending.source()));
        record.putByteArray("Cargo", pending.cargo());
        record.putString("Reason", reason);
        quarantined.put(pending.id(), record);
        setDirty();
    }

    private void quarantineRaw(CompoundTag record, String reason) {
        CompoundTag preserved = record.copy();
        preserved.putString("Reason", reason);
        UUID id = preserved.hasUUID("Id") ? preserved.getUUID("Id") : UUID.randomUUID();
        preserved.putUUID("Id", id);
        while (quarantined.containsKey(id)) {
            id = UUID.randomUUID();
            preserved.putUUID("Id", id);
        }
        quarantined.put(id, preserved);
    }

    public VirtualTransportEngine engine(TransportGraph.Node source, long currentTick) {
        return active.computeIfAbsent(source, key -> {
            var snapshot = unloaded.remove(key);
            return snapshot == null
                ? new VirtualTransportEngine(currentTick)
                : VirtualTransportEngine.restore(snapshot, currentTick);
        });
    }

    /** Reattach all restored clocks at the current game tick, never accounting for offline time. */
    public void restoreEngines(long currentTick) {
        if (unloaded.isEmpty()) return;
        unloaded.forEach((source, snapshot) -> active.put(source,
            VirtualTransportEngine.restore(snapshot, currentTick)));
        unloaded.clear();
    }

    public int size() {
        int n = pending.size() + quarantined.size();
        for (var engine : active.values()) n += engine.size();
        for (var snapshot : unloaded.values()) n += snapshot.shipments().size();
        return n;
    }

    public static VirtualLedger load(CompoundTag tag) {
        VirtualLedger ledger = new VirtualLedger();
        for (Tag raw : tag.getList("Quarantined", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw;
            ledger.quarantineRaw(item, item.getString("Reason"));
        }
        for (Tag raw : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag)raw;
            try {
                if (!item.hasUUID("Id") || !item.contains("Cargo", Tag.TAG_BYTE_ARRAY)
                    || !item.contains("Source", Tag.TAG_COMPOUND))
                    throw new IllegalArgumentException("Missing required pending fields");
                var pending = new Pending(item.getUUID("Id"), readNode(item.getCompound("Source")),
                    item.getByteArray("Cargo"));
                if (ledger.pending.putIfAbsent(pending.id(), pending) != null)
                    ledger.quarantineRaw(item, "Duplicate pending shipment id");
            } catch (RuntimeException error) {
                ledger.quarantineRaw(item, "Malformed pending NBT: " + error.getClass().getSimpleName());
            }
        }
        for (Tag raw : tag.getList("Engines", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag)raw;
            TransportGraph.Node source = readNode(entry.getCompound("Source"));
            List<VirtualTransportEngine.Shipment> shipments = new ArrayList<>();
            for (Tag sr : entry.getList("Shipments", Tag.TAG_COMPOUND)) {
                CompoundTag payload = (CompoundTag)sr;
                try {
                    if (!payload.hasUUID("Id") || !payload.contains("Cargo", Tag.TAG_BYTE_ARRAY))
                        throw new IllegalArgumentException("Missing shipment identity or cargo");
                    var endpoint = new TransportGraph.Endpoint(readNode(payload.getCompound("EndNode")),
                        readNode(payload.getCompound("Port")), payload.getString("Address"));
                    List<TransportGraph.Node> nodes = new ArrayList<>();
                    for (Tag nr : payload.getList("Path", Tag.TAG_COMPOUND))
                        nodes.add(readNode((CompoundTag)nr));
                    var route = new TransportGraph.Route(nodes, endpoint, payload.getDouble("Distance"));
                    var shipment = new VirtualTransportEngine.Shipment(payload.getUUID("Id"),
                        payload.getByteArray("Cargo"), route, payload.getDouble("Arrival"),
                        VirtualTransportEngine.Status.valueOf(payload.getString("Status")),
                        payload.getLong("RetryTick"));
                    if (ledger.pending.containsKey(shipment.id())
                        || shipments.stream().anyMatch(saved -> saved.id().equals(shipment.id())))
                        throw new IllegalArgumentException("Duplicate shipment ID");
                    shipments.add(shipment);
                } catch (RuntimeException failure) {
                    CompoundTag preserved = payload.copy();
                    preserved.put("Source", entry.getCompound("Source").copy());
                    ledger.quarantineRaw(preserved,
                        "Malformed in-flight record: " + failure.getClass().getSimpleName());
                }
            }
            try {
                var snapshot = new VirtualTransportEngine.Snapshot(entry.getDouble("Odometer"),
                    entry.getLong("LastTick"), shipments);
                // Validate restore invariants before committing the snapshot to runtime.
                VirtualTransportEngine.restore(snapshot, Math.max(0, entry.getLong("LastTick")));
                if (ledger.unloaded.putIfAbsent(source, snapshot) != null)
                    throw new IllegalArgumentException("Duplicate source engine");
            } catch (RuntimeException failure) {
                for (var shipment : shipments) {
                    CompoundTag record = new CompoundTag();
                    record.putUUID("Id", shipment.id());
                    record.putByteArray("Cargo", shipment.cargo());
                    record.put("Source", entry.getCompound("Source").copy());
                    ledger.quarantineRaw(record, "Invalid source engine");
                }
            }
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
        ListTag quarantineTags = new ListTag();
        for (var item : quarantined.values()) quarantineTags.add(item.copy());
        tag.put("Quarantined", quarantineTags);
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
