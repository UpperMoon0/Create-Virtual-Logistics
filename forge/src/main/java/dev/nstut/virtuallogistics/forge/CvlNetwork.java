package dev.nstut.virtuallogistics.forge;

import dev.nstut.virtuallogistics.core.TransportGraph;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Sampled visuals only. No inventory contents or authoritative transport state go to clients. */
public final class CvlNetwork {
    private static final String VERSION = "1";
    public static final int MAX_VISUAL_ROUTE_NODES = 96;
    private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
        .named(new ResourceLocation(VirtualLogisticsMod.ID, "visual"))
        .networkProtocolVersion(() -> VERSION)
        .clientAcceptedVersions(VERSION::equals)
        .serverAcceptedVersions(VERSION::equals)
        .simpleChannel();
    private CvlNetwork() {}

    public static void init() {
        CHANNEL.registerMessage(0, Visuals.class, CvlNetwork::encode, CvlNetwork::decode, CvlNetwork::handle);
    }

    public record Sample(List<TransportGraph.Node> route, double progress, double speedPerTick) {
        public Sample { route = List.copyOf(route); }
    }
    public record Visuals(List<Sample> samples) {
        public Visuals { samples = List.copyOf(samples); }
    }

    private static void encode(Visuals message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.samples().size());
        for (var sample : message.samples()) {
            buffer.writeVarInt(sample.route().size());
            for (var node : sample.route()) buffer.writeBlockPos(VirtualLedger.block(node));
            buffer.writeDouble(sample.progress());
            buffer.writeDouble(sample.speedPerTick());
        }
    }
    private static Visuals decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > 256) throw new IllegalArgumentException("Invalid visual count");
        List<Sample> list = new ArrayList<>(count);
        for (int i=0;i<count;i++) {
            int size = buffer.readVarInt();
            if (size<1 || size>MAX_VISUAL_ROUTE_NODES) throw new IllegalArgumentException("Invalid visual route");
            List<TransportGraph.Node> route = new ArrayList<>(size);
            for (int n=0;n<size;n++) route.add(VirtualLedger.node(buffer.readBlockPos()));
            list.add(new Sample(route,buffer.readDouble(),buffer.readDouble()));
        }
        return new Visuals(list);
    }
    private static void handle(Visuals message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> ClientVirtualVisuals.receive(message)));
        context.get().setPacketHandled(true);
    }
    public static void send(ServerPlayer player, Visuals visuals) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), visuals);
    }
}
