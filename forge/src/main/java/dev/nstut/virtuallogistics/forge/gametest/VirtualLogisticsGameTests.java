package dev.nstut.virtuallogistics.forge.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.box.PackageStyles;
import com.simibubi.create.content.logistics.packagePort.PackagePortTarget;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
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

    @GameTest(template="empty",timeoutTicks=40)
    public static void restoresActiveInFlightCargoWithoutOfflineMovement(GameTestHelper helper) {
        var ledger = new VirtualLedger();
        var source = new TransportGraph.Node(0,1,0);
        var destination = new TransportGraph.Node(20,1,0);
        var route = new TransportGraph.Route(java.util.List.of(source,destination),
            new TransportGraph.Endpoint(destination,destination,"test"),20);
        var engine = ledger.engine(source, 0);
        engine.submit(new byte[]{1,2,3},route);
        engine.clock(10,360);
        var stored = ledger.save(new CompoundTag());
        var restored = VirtualLedger.load(stored);
        restored.restoreEngines(10000);
        var recovered = restored.active.get(source);
        if (recovered == null || recovered.size()!=1 || recovered.odometer()!=10)
            helper.fail("In-flight cargo was not restored after restart");
        recovered.clock(10009,360);
        if (recovered.deliverReady(10009,2,20,s->true).delivered()!=0)
            helper.fail("Cargo traversed distance while server was offline");
        recovered.clock(10010,360);
        if (recovered.deliverReady(10010,2,20,s->true).delivered()!=1)
            helper.fail("Cargo failed to resume after persisted world load");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=160)
    public static void realChainRouteDeliversSixteenWithoutFrogAnimation(GameTestHelper helper) {
        BlockPos sourcePosition = new BlockPos(1,1,1);
        BlockPos portPosition = new BlockPos(2,1,1);
        helper.setBlock(sourcePosition,AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        helper.setBlock(portPosition,AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        var chain = (ChainConveyorBlockEntity)helper.getBlockEntity(sourcePosition);
        var port = (FrogportBlockEntity)helper.getBlockEntity(portPosition);
        chain.setSpeed(128);
        port.acceptsPackages=true;
        port.addressFilter="destination";
        port.target=new PackagePortTarget.ChainConveyorFrogportTarget(
            chain.getBlockPos().subtract(port.getBlockPos()),0,null);
        port.target.register(port,helper.getLevel(),port.getBlockPos());
        for (int i=0;i<16;i++) {
            ItemStack box = PackageStyles.getDefaultBox();
            PackageItem.addAddress(box,"destination");
            if (!chain.addLoopingPackage(new ChainConveyorPackage(0,box)))
                helper.fail("Virtual conveyor rejected package " + i);
        }
        helper.succeedWhen(() -> {
            int received=0;
            for (int i=0;i<port.inventory.getSlots();i++)
                received += port.inventory.getStackInSlot(i).getCount();
            helper.assertTrue(received==16,"16 cargo manifests must reach the real Frogport inventory");
            helper.assertTrue(chain.getLoopingPackages().isEmpty(),
                "Chain must not accumulate physical Create packages");
        });
    }
}
