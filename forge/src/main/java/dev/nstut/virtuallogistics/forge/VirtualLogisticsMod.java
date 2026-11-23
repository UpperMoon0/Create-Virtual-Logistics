package dev.nstut.virtuallogistics.forge;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(VirtualLogisticsMod.ID)
public final class VirtualLogisticsMod {
    public static final String ID = "createvirtuallogistics";

    private static final ForgeConfigSpec.Builder CONFIG_BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.BooleanValue ENABLED = CONFIG_BUILDER
        .comment("Enable virtual transport instead of Create's physical chain package lists.")
        .define("enabled", false);
    public static final ForgeConfigSpec.BooleanValue ALLOW_UNSAFE_EXTERNAL_INSERTION = CONFIG_BUILDER
        .comment("EXPERIMENTAL: insert into an arbitrary adjacent IItemHandler. Unsafe after crashes or partial insertion; for disposable worlds only.")
        .define("allowUnsafeExternalInsertion", false);
    public static final ForgeConfigSpec.IntValue DELIVERY_BUDGET = CONFIG_BUILDER
        .comment("Maximum actual inventory operations per world tick; no artificial network throughput cap.")
        .defineInRange("deliveryBudgetPerTick", 128, 1, 10000);
    public static final ForgeConfigSpec.IntValue RENDER_CAP = CONFIG_BUILDER
        .comment("Maximum decorative packages rendered per client; transport is unaffected.")
        .defineInRange("renderCap", 24, 0, CvlNetwork.MAX_VISUAL_SAMPLES);
    private static final ForgeConfigSpec CONFIG = CONFIG_BUILDER.build();

    public VirtualLogisticsMod() {
        CvlNetwork.init();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG);
        MinecraftForge.EVENT_BUS.addListener(VirtualTransportService::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(VirtualLogisticsCommands::register);
    }
}
