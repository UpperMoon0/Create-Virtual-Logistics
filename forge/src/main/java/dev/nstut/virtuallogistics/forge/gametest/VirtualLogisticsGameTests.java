package dev.nstut.virtuallogistics.forge.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import dev.nstut.virtuallogistics.core.TransportGraph;
import dev.nstut.virtuallogistics.forge.VirtualLedger;
import dev.nstut.virtuallogistics.forge.VirtualLogisticsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Native acceptance coverage: run with :forge:runGameTestServer on an isolated world. */
@GameTestHolder(VirtualLogisticsMod.ID)
@PrefixGameTestTemplate(false)
public final class VirtualLogisticsGameTests {
    private VirtualLogisticsGameTests() {}

    @GameTest(template="empty",timeoutTicks=40)
    public static void interceptsCreateConveyorWithoutPhysicalPackage(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1,1,1);
        helper.setBlock(relative,AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        ChainConveyorBlockEntity conveyor = (ChainConveyorBlockEntity)helper.getBlockEntity(relative);
        ItemStack cargo = PackageStyles.getDefaultBox();
        PackageItem.addAddress(cargo, "0_hno3");
        int before = VirtualLedger.get(helper.getLevel()).pending.size();
        if (!conveyor.addLoopingPackage(new ChainConveyorPackage(0,cargo)))
            helper.fail("Create package interception rejected valid cargo");
        if (VirtualLedger.get(helper.getLevel()).pending.size() != before+1)
            helper.fail("Virtual package not captured in world ledger");
        if (!conveyor.getLoopingPackages().isEmpty())
            helper.fail("Physical Create package was still created");
        if (!conveyor.canAcceptMorePackages() || !conveyor.canAcceptMorePackagesFromOtherConveyor())
            helper.fail("Virtual conveyor still enforces physical capacity");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void ledgerSurvivesNbtRoundTrip(GameTestHelper helper) {
        var ledger = new VirtualLedger();
        var source = new TransportGraph.Node(1,2,3);
        ledger.capture(source,new byte[]{5,6,7});
        var restored = VirtualLedger.load(ledger.save(new CompoundTag()));
        if (restored.pending.size()!=1 || restored.size()!=1)
            helper.fail("Logical cargo missing from saved data");
        var pending=restored.pending.values().iterator().next();
        if (!pending.source().equals(source) || !java.util.Arrays.equals(pending.cargo(),new byte[]{5,6,7}))
            helper.fail("Cargo payload damaged by persistence");
        helper.succeed();
    }
}
