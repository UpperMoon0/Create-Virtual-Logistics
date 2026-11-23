package dev.nstut.virtuallogistics.forge;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import dev.nstut.virtuallogistics.core.TransportGraph;
import dev.nstut.virtuallogistics.core.VirtualTransportEngine;
import dev.nstut.virtuallogistics.core.VirtualVisualMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.items.IItemHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Main-thread world adapter. All writes pass through Forge item handlers.
 * Routing is cached per source; transport clocks advance once per source, not per package.
 * No chunk is force-loaded or generated.
 */
public final class VirtualTransportService {
    private static final int MAX_GRAPH_NODES = 8192;
    private static final int PENDING_BUDGET_PER_TICK = 128;
    private static final int RETRY_TICKS = 20;
    private record CachedGraph(TransportGraph graph, long expiresTick) {}
    private static final Map<ServerLevel, Map<TransportGraph.Node, CachedGraph>> GRAPHS = new WeakHashMap<>();

    private VirtualTransportService() {}

    public static boolean capture(ServerLevel level, ChainConveyorBlockEntity origin, ChainConveyorPackage packageToMove) {
        if (packageToMove == null || packageToMove.item.isEmpty()
            || !PackageItem.isPackage(packageToMove.item) || packageToMove.item.getCount() != 1)
            return false;
        try {
            // The serialized ItemStack, including fluid, fragment and order metadata, is owned by the ledger.
            byte[] cargo = encode(packageToMove.item);
            if (cargo.length > 2 * 1024 * 1024) return false;
            VirtualLedger.get(level).capture(VirtualLedger.node(origin.getBlockPos()), cargo);
            return true;
        } catch (IOException e) {
            return false; // Keep original Create package if persistence encoding failed.
        }
    }

    static byte[] encode(ItemStack cargo) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(cargo.save(new CompoundTag()), output);
        return output.toByteArray();
    }

    static ItemStack decode(byte[] bytes) throws IOException {
        return ItemStack.of(NbtIo.readCompressed(new ByteArrayInputStream(bytes)));
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            VirtualLedger ledger = VirtualLedger.get(level);
            long now = level.getGameTime();
            // Disabling the addon must freeze existing cargo, not silently deliver it.
            // Operators may disable it for inspection without mutating inventories.
            if (!VirtualLogisticsMod.ENABLED.get()) {
                if (now % 10 == 0 && !level.players().isEmpty())
                    sendEmptyVisuals(level);
                continue;
            }
            if (ledger.size() == 0) {
                if (now % 10 == 0 && !level.players().isEmpty())
                    sendVisuals(level, ledger); // Clear previously rendered sample proxies.
                continue;
            }
            ledger.restoreEngines(now);
            routePending(level, ledger, now);
            int left = VirtualLogisticsMod.DELIVERY_BUDGET.get();
            // Advance every source clock, including sources whose deliveries are budget-deferred.
            for (var entry : ledger.active.entrySet()) {
                var block = level.hasChunkAt(VirtualLedger.block(entry.getKey()))
                    ? level.getBlockEntity(VirtualLedger.block(entry.getKey())) : null;
                // Reverse rotation must not advance shipments toward the sink.
                double rpm = block instanceof ChainConveyorBlockEntity conveyor
                    ? Math.max(0, conveyor.getSpeed()) : 0;
                entry.getValue().clock(now, rpm);
                if (left > 0) {
                    var report = entry.getValue().deliverReady(now, left, RETRY_TICKS,
                        shipment -> deliver(level, shipment));
                    left -= report.attempted();
                    if (report.attempted() > 0) ledger.setDirty();
                }
            }
            if (now % 10 == 0) sendVisuals(level, ledger);
        }
    }

    private static void sendEmptyVisuals(ServerLevel level) {
        for (ServerPlayer player : level.players())
            CvlNetwork.send(player, new CvlNetwork.Visuals(List.of()));
    }

    private static void sendVisuals(ServerLevel level, VirtualLedger ledger) {
        int cap = VirtualLogisticsMod.RENDER_CAP.get();
        for (ServerPlayer player : level.players()) {
            List<CvlNetwork.Sample> visuals = new ArrayList<>(cap);
            for (var entry : ledger.active.entrySet()) {
                if (visuals.size() >= cap) break;
                var engine = entry.getValue();
                BlockPos startPos = VirtualLedger.block(entry.getKey());
                if (startPos.distSqr(player.blockPosition()) > 128 * 128) continue;
                var source = level.hasChunkAt(startPos) ? level.getBlockEntity(startPos) : null;
                double speed = source instanceof ChainConveyorBlockEntity conveyor
                    ? Math.max(0, conveyor.getSpeed()) / 360.0 : 0;
                for (var shipment : engine.sample(cap - visuals.size())) {
                    var route = shipment.route();
                    // Do not send a huge graph inside a visual packet.
                    if (route.nodes().size() + 1 > CvlNetwork.MAX_VISUAL_ROUTE_NODES)
                        continue;
                    double start = shipment.arrivalDistance() - route.distance();
                    double progress = Math.max(0, engine.odometer() - start);
                    visuals.add(new CvlNetwork.Sample(VirtualVisualMath.routePoints(route),progress,speed));
                }
            }
            CvlNetwork.send(player,new CvlNetwork.Visuals(visuals));
        }
    }

    private static void routePending(ServerLevel level, VirtualLedger ledger, long now) {
        if (now % 10 != 0) return;
        int checked = 0;
        java.util.List<VirtualLedger.Pending> deferred = new ArrayList<>();
        var iterator = ledger.pending.entrySet().iterator();
        while (iterator.hasNext() && checked++ < PENDING_BUDGET_PER_TICK) {
            var item = iterator.next().getValue();
            ItemStack cargo;
            try { cargo = decode(item.cargo()); }
            catch (IOException e) {
                deferred.add(item);
                iterator.remove();
                continue; // Keep undecodable cargo but don't starve other orders.
            }
            var route = graph(level, item.source(), now).route(item.source(),
                PackageItem.getAddress(cargo),
                (requested, filter) -> PackageItem.matchAddress(requested, filter));
            if (route.isEmpty()) {
                deferred.add(item);
                iterator.remove();
                continue;
            }
            var engine = ledger.engine(item.source(), now);
            engine.submit(new VirtualTransportEngine.Shipment(item.id(), item.cargo(), route.get(),
                engine.odometer() + route.get().distance(),
                VirtualTransportEngine.Status.IN_TRANSIT, 0));
            iterator.remove();
            ledger.setDirty();
        }
        // Rotate unresolved orders after the current slice, providing fairness for later arrivals.
        deferred.forEach(pending -> ledger.pending.put(pending.id(), pending));
    }

    private static TransportGraph graph(ServerLevel level, TransportGraph.Node source, long tick) {
        Map<TransportGraph.Node, CachedGraph> cache = GRAPHS.computeIfAbsent(level, ignored -> new HashMap<>());
        CachedGraph stored = cache.get(source);
        if (stored != null && tick < stored.expiresTick()) return stored.graph();

        TransportGraph graph = new TransportGraph();
        ArrayDeque<TransportGraph.Node> queue = new ArrayDeque<>();
        Set<TransportGraph.Node> seen = new HashSet<>();
        queue.add(source);
        while (!queue.isEmpty() && seen.size() < MAX_GRAPH_NODES) {
            TransportGraph.Node pos = queue.removeFirst();
            if (!seen.add(pos)) continue;
            BlockPos physical = VirtualLedger.block(pos);
            if (!level.hasChunkAt(physical)) continue;
            BlockEntity be = level.getBlockEntity(physical);
            if (!(be instanceof ChainConveyorBlockEntity conveyor)) continue;
            graph.addNode(pos);
            for (BlockPos offset : conveyor.connections) {
                BlockPos other = physical.offset(offset);
                if (!level.hasChunkAt(other)) continue;
                BlockEntity neighbour = level.getBlockEntity(other);
                if (!(neighbour instanceof ChainConveyorBlockEntity target)
                    || !target.connections.contains(offset.multiply(-1))) continue;
                graph.connect(pos,VirtualLedger.node(other),
                    Math.max(0.01, Math.sqrt(offset.distSqr(BlockPos.ZERO))));
                if (!seen.contains(VirtualLedger.node(other))) queue.add(VirtualLedger.node(other));
            }
            for (BlockPos offset : conveyor.loopPorts.keySet())
                registerPort(level, graph, pos, physical.offset(offset));
            for (BlockPos offset : conveyor.travelPorts.keySet())
                registerPort(level, graph, pos, physical.offset(offset));
        }
        cache.put(source, new CachedGraph(graph,tick + 20));
        return graph;
    }

    private static void registerPort(ServerLevel level, TransportGraph graph, TransportGraph.Node node, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return;
        if (level.getBlockEntity(pos) instanceof FrogportBlockEntity port
            && port.acceptsPackages && port.target != null) {
            graph.addEndpoint(new TransportGraph.Endpoint(node,VirtualLedger.node(pos),port.getFilterString()));
        }
    }

    private static boolean deliver(ServerLevel level, VirtualTransportEngine.Shipment shipment) {
        TransportGraph.Route route = shipment.route();
        // Reject stale routes rather than teleporting across broken infrastructure.
        if (!routeStillConnected(level, route)) return false;
        BlockPos destination = VirtualLedger.block(route.endpoint().port());
        if (!level.hasChunkAt(destination)) return false;
        BlockEntity be = level.getBlockEntity(destination);
        if (!(be instanceof FrogportBlockEntity frogport) || !frogport.acceptsPackages)
            return false;
        try {
            ItemStack cargo = decode(shipment.cargo());
            if (!PackageItem.matchAddress(cargo, frogport.getFilterString())) return false;
            // Prefer the actual crate/pipe below. Never allocate fake storage or exceed a Forge item handler.
            BlockPos below = destination.below();
            if (VirtualLogisticsMod.ALLOW_UNSAFE_EXTERNAL_INSERTION.get() && level.hasChunkAt(below)) {
                BlockEntity inventory = level.getBlockEntity(below);
                if (inventory != null) {
                    Optional<IItemHandler> handler = inventory.getCapability(ForgeCapabilities.ITEM_HANDLER,
                        Direction.UP).resolve();
                    if (handler.isPresent() && insertFully(handler.get(), cargo)) return true;
                }
            }
            if (insertFully(frogport.inventory, cargo)) {
                frogport.notifyUpdate();
                return true;
            }
        } catch (IOException ignored) {
            // Retain pending cargo rather than discarding it.
        }
        return false;
    }

    /**
     * Validate loaded infrastructure without chunk generation or forced loads.
     * This is not yet a per-segment speed or topology-revision simulation.
     */
    public static boolean routeStillConnected(ServerLevel level, TransportGraph.Route route) {
        List<TransportGraph.Node> path = route.nodes();
        for (int i = 0; i < path.size(); i++) {
            BlockPos pos = VirtualLedger.block(path.get(i));
            if (!level.hasChunkAt(pos)) return false;
            if (!(level.getBlockEntity(pos) instanceof ChainConveyorBlockEntity chain)
                || chain.getSpeed() <= 0) return false;
            if (i + 1 < path.size()) {
                BlockPos next = VirtualLedger.block(path.get(i + 1));
                BlockPos offset = next.subtract(pos);
                if (!chain.connections.contains(offset) || !level.hasChunkAt(next)) return false;
                if (!(level.getBlockEntity(next) instanceof ChainConveyorBlockEntity other)
                    || !other.connections.contains(offset.multiply(-1))) return false;
            } else {
                BlockPos port = VirtualLedger.block(route.endpoint().port());
                BlockPos offset = port.subtract(pos);
                if (!chain.loopPorts.containsKey(offset) && !chain.travelPorts.containsKey(offset))
                    return false;
                if (!level.hasChunkAt(port)
                    || !(level.getBlockEntity(port) instanceof FrogportBlockEntity destination)
                    || !destination.acceptsPackages || destination.target == null)
                    return false;
            }
        }
        return true;
    }

    private static boolean insertFully(IItemHandler handler, ItemStack stack) {
        // Create packages are non-stackable. A single-item slot insertion cannot partially
        // succeed, even if a neighbouring mod changes its handler between simulate/execute.
        if (stack.isEmpty() || stack.getCount() != 1) return false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.isItemValid(slot, stack)) continue;
            ItemStack simulated = handler.insertItem(slot, stack.copy(), true);
            if (!simulated.isEmpty()) continue;
            return handler.insertItem(slot, stack.copy(), false).isEmpty();
        }
        return false;
    }
}
