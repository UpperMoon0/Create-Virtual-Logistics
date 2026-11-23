package dev.nstut.virtuallogistics.forge;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.nio.channels.FileChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Operator inspection and immutable offline-recovery exports.
 * Restoring exports automatically would be unsafe without an idempotent
 * destination transaction protocol, so this never imports cargo.
 */
public final class VirtualLogisticsCommands {
    private VirtualLogisticsCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("cvl").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(ctx -> {
                ServerLevel level = ctx.getSource().getLevel();
                VirtualLedger ledger = VirtualLedger.get(level);
                int queued = ledger.pending.size();
                int corrupt = ledger.quarantined.size();
                int flying = ledger.active.values().stream().mapToInt(e -> e.inFlight()).sum();
                int waiting = ledger.active.values().stream().mapToInt(e -> e.waiting()).sum();
                boolean enabled = VirtualLogisticsMod.ENABLED.get();
                ctx.getSource().sendSuccess(() -> Component.literal(
                    "CVL " + (enabled ? "enabled" : "disabled") +
                    " | total=" + ledger.size() + " pending=" + queued +
                    " in-flight=" + flying + " awaiting storage=" + waiting +
                    " quarantined=" + corrupt +
                    " | unsafe external insertion=" +
                    VirtualLogisticsMod.ALLOW_UNSAFE_EXTERNAL_INSERTION.get()), false);
                return ledger.size();
            }))
            .then(Commands.literal("export").requires(source -> source.hasPermission(4))
                .executes(ctx -> {
                    try {
                        Path exported = exportSnapshot(ctx.getSource().getLevel());
                        ctx.getSource().sendSuccess(() ->
                            Component.literal("CVL immutable recovery export: " + exported), false);
                        return 1;
                    } catch (IOException e) {
                        ctx.getSource().sendFailure(
                            Component.literal("CVL recovery export FAILED: " + e.getMessage()));
                        return 0;
                    }
                })));
    }

    /**
     * Export every pending and active serialized payload to a fsynced NBT backup.
     * This is not an atomic world/inventory checkpoint; use only for inspection.
     */
    public static Path exportSnapshot(ServerLevel level) throws IOException {
        String dimension = level.dimension().location().toString().replace(':', '_').replace('/', '_');
        Path directory = level.getServer().getWorldPath(LevelResource.ROOT)
            .resolve("cvl-recovery").normalize();
        Files.createDirectories(directory);
        String name = "cvl-" + dimension + "-" + Instant.now().toEpochMilli() + ".nbt";
        Path destination = directory.resolve(name);
        Path temporary = directory.resolve(name + ".tmp");
        try {
            // NbtIo.writeCompressed closes its OutputStream. Reopen for fsync;
            // syncing the closed descriptor would throw SyncFailedException.
            NbtIo.writeCompressed(VirtualLedger.get(level).save(new CompoundTag()),
                temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
        return destination;
    }
}
