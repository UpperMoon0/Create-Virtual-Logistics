package dev.nstut.virtuallogistics.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.logistics.box.PackageStyles;
import dev.nstut.virtuallogistics.core.VirtualVisualMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only proxies, not interactable items. Cargo never exists on the client renderer. */
@Mod.EventBusSubscriber(modid = VirtualLogisticsMod.ID, value = Dist.CLIENT)
public final class ClientVirtualVisuals {
    private static CvlNetwork.Visuals current = new CvlNetwork.Visuals(java.util.List.of());
    private static long lastReceivedNanos;

    private ClientVirtualVisuals() {}

    static void receive(CvlNetwork.Visuals snapshot) {
        current = snapshot;
        lastReceivedNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        Minecraft game = Minecraft.getInstance();
        if (game.level == null || game.player == null || current.samples().isEmpty()) return;
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        double deltaTicks = Math.min(20,Math.max(0,(System.nanoTime()-lastReceivedNanos)/50_000_000.0));
        var buffers = game.renderBuffers().bufferSource();
        var visualPackage = PackageStyles.getDefaultBox();
        for (var sample : current.samples()) {
            var point = VirtualVisualMath.pointAt(sample.route(),
                sample.progress() + deltaTicks * Math.abs(sample.speedPerTick()));
            pose.pushPose();
            pose.translate(point.x()-camera.x,point.y()-camera.y,point.z()-camera.z);
            pose.scale(0.65f,0.65f,0.65f);
            game.getItemRenderer().renderStatic(visualPackage,ItemDisplayContext.FIXED,
                LightTexture.FULL_BRIGHT,OverlayTexture.NO_OVERLAY,pose,buffers,game.level,0);
            pose.popPose();
        }
        buffers.endBatch();
    }
}
