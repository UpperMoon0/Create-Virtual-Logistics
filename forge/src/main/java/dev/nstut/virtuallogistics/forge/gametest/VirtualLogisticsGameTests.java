package dev.nstut.virtuallogistics.forge.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.core.Direction;
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
        BlockPos motorPosition = new BlockPos(1,0,1);
        helper.setBlock(motorPosition, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(sourcePosition,AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        helper.setBlock(portPosition,AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        var chain = (ChainConveyorBlockEntity)helper.getBlockEntity(sourcePosition);
        var port = (FrogportBlockEntity)helper.getBlockEntity(portPosition);
        var motor = (CreativeMotorBlockEntity)helper.getBlockEntity(motorPosition);
        motor.generatedSpeed.setValue(128);
        motor.updateGeneratedRotation();
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
            var currentLedger = VirtualLedger.get(helper.getLevel());
            int queued = currentLedger.pending.size();
            int flying = currentLedger.active.values().stream().mapToInt(e -> e.inFlight()).sum();
            int waiting = currentLedger.active.values().stream().mapToInt(e -> e.waiting()).sum();
            helper.assertTrue(received==16, "Expected 16 cargo; received=" + received
                + " pending=" + queued + " flying=" + flying + " waiting=" + waiting
                + " rpm=" + chain.getSpeed() + " ports=" + chain.loopPorts.size()
                + " portTarget=" + (port.target == null ? "null" : port.target.getClass().getSimpleName()));
            helper.assertTrue(chain.getLoopingPackages().isEmpty(),
                "Chain must not accumulate physical Create packages");
        });
    }

    /**
     * Representative legacy-NBT package payloads. This proves the addon is byte
     * transparent; it does not substitute for running Fluid Logistics/GT itself.
     */
    @GameTest(template="empty",timeoutTicks=40)
    public static void preservesOrderAndFluidPayloadNbt(GameTestHelper helper) {
        BlockPos source = new BlockPos(1,1,1);
        helper.setBlock(source, AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        var chain = (ChainConveyorBlockEntity)helper.getBlockEntity(source);
        String[] fluids = {"gtceu:oxygen","gtceu:hydrogen","gtceu:nitrogen","gtceu:nitric_acid"};
        var ledger = VirtualLedger.get(helper.getLevel());
        int before = ledger.pending.size();
        for (int i=0; i<fluids.length; i++) {
            ItemStack box = PackageStyles.getDefaultBox();
            PackageItem.addAddress(box,"0_hno3");
            // Apply authentic Create fragment/order semantics (same API Fluid
            // Logistics 1.20.1 uses), rather than inventing the order keys.
            PackageItem.setOrder(box,8321,2,true,i,i==fluids.length-1,null);
            CompoundTag order = box.getOrCreateTag();
            CompoundTag fluid = new CompoundTag();
            fluid.putString("FluidName",fluids[i]);
            fluid.putLong("Amount",4_000_000L + i);
            order.put("FluidLogisticsTestPayload",fluid);
            if (!chain.addLoopingPackage(new ChainConveyorPackage(0,box)))
                helper.fail("Failed to capture modded package "+i);
            try {
                ItemStack expected = box;
                byte[] bytes = ledger.pending.values().stream().reduce((first,last)->last).orElseThrow().cargo();
                ItemStack recovered = ItemStack.of(net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(bytes)));
                if (!ItemStack.isSameItemSameTags(expected, recovered)
                    || expected.getCount()!=recovered.getCount()
                    || !PackageItem.hasFragmentData(recovered)
                    || PackageItem.getOrderId(recovered)!=8321
                    || PackageItem.getLinkIndex(recovered)!=2
                    || PackageItem.getIndex(recovered)!=i
                    || PackageItem.isFinal(recovered)!=(i==fluids.length-1))
                    helper.fail("NBT, Create order fragment or count changed for "+fluids[i]);
            } catch (java.io.IOException ex) {
                helper.fail("Could not decode captured package: "+ex);
            }
        }
        helper.assertTrue(ledger.pending.size()==before+fluids.length,
            "Expected four independent preserved shipments");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void changingPortConnectionInvalidatesReceiptRoute(GameTestHelper helper) {
        BlockPos source = new BlockPos(1,1,1);
        BlockPos portPosition = new BlockPos(2,1,1);
        BlockPos motorPosition = new BlockPos(1,0,1);
        helper.setBlock(motorPosition, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING, Direction.UP));
        helper.setBlock(source, AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        helper.setBlock(portPosition, AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        var chain = (ChainConveyorBlockEntity)helper.getBlockEntity(source);
        var port = (FrogportBlockEntity)helper.getBlockEntity(portPosition);
        var motor = (CreativeMotorBlockEntity)helper.getBlockEntity(motorPosition);
        motor.generatedSpeed.setValue(128);
        motor.updateGeneratedRotation();
        port.acceptsPackages=true;
        port.addressFilter="destination";
        port.target=new PackagePortTarget.ChainConveyorFrogportTarget(
            chain.getBlockPos().subtract(port.getBlockPos()),0,null);
        port.target.register(port,helper.getLevel(),port.getBlockPos());
        var n = new TransportGraph.Node(chain.getBlockPos().getX(),chain.getBlockPos().getY(),chain.getBlockPos().getZ());
        var destination = new TransportGraph.Node(port.getBlockPos().getX(),port.getBlockPos().getY(),port.getBlockPos().getZ());
        var route = new TransportGraph.Route(java.util.List.of(n),
            new TransportGraph.Endpoint(n,destination,"destination"),1);
        helper.assertTrue(dev.nstut.virtuallogistics.forge.VirtualTransportService
            .routeStillConnected(helper.getLevel(),route), "Expected initially valid route");
        chain.loopPorts.clear();
        chain.travelPorts.clear();
        helper.assertTrue(!dev.nstut.virtuallogistics.forge.VirtualTransportService
            .routeStillConnected(helper.getLevel(),route), "Disconnected link was still valid");
        port.target.register(port,helper.getLevel(),port.getBlockPos());
        helper.assertTrue(dev.nstut.virtuallogistics.forge.VirtualTransportService
            .routeStillConnected(helper.getLevel(),route), "Reconnected port was rejected");
        port.acceptsPackages=false;
        helper.assertTrue(!dev.nstut.virtuallogistics.forge.VirtualTransportService
            .routeStillConnected(helper.getLevel(),route), "Nonreceiving port was accepted");
        helper.succeed();
    }


    @GameTest(template="empty",timeoutTicks=40)
    public static void exportsAuditableLedgerSnapshotToIsolatedWorld(GameTestHelper helper) {
        var ledger = VirtualLedger.get(helper.getLevel());
        byte[] unique = new byte[]{78, 83, 84, 117, 116, 36};
        ledger.capture(new TransportGraph.Node(11,12,13),unique);
        java.nio.file.Path backup = null;
        try {
            backup = dev.nstut.virtuallogistics.forge.VirtualLogisticsCommands
                .exportSnapshot(helper.getLevel());
            helper.assertTrue(java.nio.file.Files.size(backup)>0, "Empty snapshot export");
            int expectedCount = ledger.size();
            long reloadStart = System.nanoTime();
            var restored = VirtualLedger.load(net.minecraft.nbt.NbtIo.readCompressed(
                new java.io.FileInputStream(backup.toFile())));
            long reloadNanos = System.nanoTime() - reloadStart;
            System.out.printf("CVL native NBT restore: %d packages, %.3f ms%n",
                expectedCount, reloadNanos/1e6);
            helper.assertTrue(restored.size()==expectedCount,
                "SavedData roundtrip lost or duplicated a shipment");
            helper.assertTrue(restored.pending.values().stream()
                .anyMatch(p -> java.util.Arrays.equals(p.cargo(),unique)),
                "Exported cargo was lost or modified");
            helper.succeed();
        } catch (java.io.IOException ex) {
            helper.fail("Could not export recovery snapshot: "+ex);
        } finally {
            if (backup != null) {
                try { java.nio.file.Files.deleteIfExists(backup); }
                catch (java.io.IOException ignored) { }
            }
        }
    }


    @GameTest(template="empty",timeoutTicks=40)
    public static void requiresPinnedFluidLogisticsWhenCompatibilityGateEnabled(GameTestHelper helper) {
        String requiredVersion = System.getProperty("cvl.requireFluidLogistics", "");
        var loaded = net.minecraftforge.fml.ModList.get()
            .getModContainerById("fluidlogistics");
        if (!requiredVersion.isEmpty())
            helper.assertTrue(loaded.isPresent(), "Pinned Fluid Logistics not loaded in real Forge runtime");
        if (loaded.isPresent()) {
            helper.assertTrue(requiredVersion.equals("1.3.2"),
                "Fluid Logistics loaded without the expected isolated compatibility gate");
            String actualVersion = loaded.orElseThrow().getModInfo().getVersion().toString();
            helper.assertTrue(actualVersion.equals(requiredVersion),
                "Fluid Logistics release mismatch: expected " + requiredVersion +
                ", actual " + actualVersion);
        }
        helper.succeed();
    }


    @GameTest(template="empty",timeoutTicks=80)
    public static void malformedCompressedShipmentIsQuarantinedWithoutLoss(GameTestHelper helper) {
        var ledger = VirtualLedger.get(helper.getLevel());
        var source = new TransportGraph.Node(helper.absolutePos(new BlockPos(1,1,1)).getX(),
            helper.absolutePos(new BlockPos(1,1,1)).getY(),
            helper.absolutePos(new BlockPos(1,1,1)).getZ());
        var before = ledger.quarantined.size();
        ledger.capture(source,new byte[]{1,2,3,4,5,6,7});
        var shipmentId = ledger.pending.values().stream()
            .filter(p -> p.source().equals(source) && p.cargo().length==7)
            .findFirst().orElseThrow().id();
        helper.runAfterDelay(35, () -> {
            helper.assertTrue(ledger.quarantined.containsKey(shipmentId),
                "Corrupt cargo should move to quarantine at route planning");
            helper.assertTrue(!ledger.pending.containsKey(shipmentId),
                "Corrupt cargo must not be repeatedly decoded");
            helper.assertTrue(ledger.quarantined.size()>=before+1,
                "Malformed cargo unexpectedly disappeared");
            var restored = VirtualLedger.load(ledger.save(new CompoundTag()));
            helper.assertTrue(restored.quarantined.containsKey(shipmentId),
                "Opaque quarantined cargo disappeared across NBT save/load");
            helper.assertTrue(java.util.Arrays.equals(
                new byte[]{1,2,3,4,5,6,7},
                restored.quarantined.get(shipmentId).getByteArray("Cargo")),
                "Corrupt payload bytes changed during recovery");
            helper.succeed();
        });
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void invalidPersistedShipmentCannotPreventWorldLedgerLoad(GameTestHelper helper) {
        CompoundTag file = new CompoundTag();
        var engines = new net.minecraft.nbt.ListTag();
        CompoundTag owner = new CompoundTag();
        CompoundTag ownerNode = new CompoundTag();
        ownerNode.putInt("X",100);
        ownerNode.putInt("Y",64);
        ownerNode.putInt("Z",100);
        owner.put("Source",ownerNode);
        owner.putDouble("Odometer",1);
        var badShipments = new net.minecraft.nbt.ListTag();
        CompoundTag invalid = new CompoundTag();
        java.util.UUID id = java.util.UUID.randomUUID();
        invalid.putUUID("Id",id);
        invalid.putByteArray("Cargo",new byte[]{89,11,8});
        invalid.putString("Status","UNKNOWN_CORRUPT_ENUM");
        invalid.putDouble("Distance",1);
        badShipments.add(invalid);
        owner.put("Shipments",badShipments);
        engines.add(owner);
        file.put("Engines",engines);
        var recovered = VirtualLedger.load(file);
        helper.assertTrue(recovered.quarantined.size()==1,
            "Unknown persisted enum crashed world load or discarded cargo");
        helper.assertTrue(recovered.quarantined.containsKey(id),
            "Corrupt shipment identity was lost");
        var again = VirtualLedger.load(recovered.save(new CompoundTag()));
        helper.assertTrue(again.quarantined.size()==1
            && java.util.Arrays.equals(new byte[]{89,11,8},
                again.quarantined.get(id).getByteArray("Cargo")),
            "Quarantined contents were not preserved across second load");
        helper.succeed();
    }


    @GameTest(template="empty",timeoutTicks=60)
    public static void routeSpeedGatePausesBrokenAndUnpoweredNetwork(GameTestHelper helper) {
        BlockPos source = new BlockPos(1,1,1);
        BlockPos portPos = new BlockPos(2,1,1);
        BlockPos motorPos = new BlockPos(1,0,1);
        helper.setBlock(motorPos, AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING,Direction.UP));
        helper.setBlock(source,AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        helper.setBlock(portPos,AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        var chain=(ChainConveyorBlockEntity)helper.getBlockEntity(source);
        var port=(FrogportBlockEntity)helper.getBlockEntity(portPos);
        var motor=(CreativeMotorBlockEntity)helper.getBlockEntity(motorPos);
        motor.generatedSpeed.setValue(128);
        motor.updateGeneratedRotation();
        port.acceptsPackages=true;
        port.addressFilter="speed";
        port.target = new PackagePortTarget.ChainConveyorFrogportTarget(
            chain.getBlockPos().subtract(port.getBlockPos()),0,null);
        port.target.register(port,helper.getLevel(),port.getBlockPos());
        var start = new TransportGraph.Node(chain.getBlockPos().getX(),
            chain.getBlockPos().getY(),chain.getBlockPos().getZ());
        var destination = new TransportGraph.Node(port.getBlockPos().getX(),
            port.getBlockPos().getY(),port.getBlockPos().getZ());
        var route = new TransportGraph.Route(java.util.List.of(start),
            new TransportGraph.Endpoint(start,destination,"speed"),1);
        var engine = new dev.nstut.virtuallogistics.core.VirtualTransportEngine(0);
        engine.submit(new byte[]{1},route);
        double rpm = dev.nstut.virtuallogistics.forge.VirtualTransportService
            .effectiveRpm(helper.getLevel(),engine);
        helper.assertTrue(rpm>0, "Powered connected route did not progress");
        chain.loopPorts.clear();
        chain.travelPorts.clear();
        helper.assertTrue(dev.nstut.virtuallogistics.forge.VirtualTransportService
            .effectiveRpm(helper.getLevel(),engine)==0,
            "Disconnected route still advanced logical distance");
        port.target.register(port,helper.getLevel(),port.getBlockPos());
        helper.assertTrue(dev.nstut.virtuallogistics.forge.VirtualTransportService
            .effectiveRpm(helper.getLevel(),engine)>0,
            "Reconnected route did not resume");
        port.acceptsPackages=false;
        helper.assertTrue(dev.nstut.virtuallogistics.forge.VirtualTransportService
            .effectiveRpm(helper.getLevel(),engine)==0,
            "Disabled destination still advanced shipment");
        helper.succeed();
    }


    @GameTest(template="empty",timeoutTicks=50)
    public static void realFluidLogisticsCanonicalPackageSurvivesVirtualCapture(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("fluidlogistics")) {
            // Baseline profile must stay independent of the optional runtime jar;
            // the separate pinned compatibility profile REQUIRES the mod to load.
            helper.succeed();
            return;
        }
        BlockPos source = new BlockPos(1,1,1);
        helper.setBlock(source,AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        var chain = (ChainConveyorBlockEntity) helper.getBlockEntity(source);
        var ledger = VirtualLedger.get(helper.getLevel());
        var previousIds = new java.util.HashSet<>(ledger.pending.keySet());
        var fluids = java.util.List.of(
            new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,1000),
            new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.LAVA,1000));
        int index=0;
        for(var fluid : fluids) {
            try {
                var helperType = Class.forName(
                    "com.yision.fluidlogistics.content.logistics.fluidPackage.FluidPackageContentHelper");
                var creator = helperType.getMethod(
                    "createCanonicalPackage",net.minecraftforge.fluids.FluidStack.class);
                var unpack = helperType.getMethod(
                    "getSingleContainedFluid",ItemStack.class);
                ItemStack box=(ItemStack)creator.invoke(null,fluid);
                PackageItem.addAddress(box,"0_hno3");
                PackageItem.setOrder(box,9011,5,true,index,index==fluids.size()-1,null);
                if(!chain.addLoopingPackage(new ChainConveyorPackage(0,box)))
                    helper.fail("CVL rejected actual Fluid Logistics fluid package");
                var latest = ledger.pending.values().stream()
                    .filter(p -> !previousIds.contains(p.id()))
                    .reduce((a,b) -> b).orElseThrow();
                previousIds.add(latest.id());
                ItemStack restored = ItemStack.of(net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(latest.cargo())));
                var actual=(net.minecraftforge.fluids.FluidStack)unpack.invoke(null,restored);
                helper.assertTrue(actual.isFluidEqual(fluid)
                    && actual.getAmount()==fluid.getAmount(),
                    "Actual Fluid Logistics fluid type or amount changed in transit");
                helper.assertTrue(PackageItem.getOrderId(restored)==9011
                    && PackageItem.getLinkIndex(restored)==5
                    && PackageItem.getIndex(restored)==index
                    && PackageItem.isFinal(restored)==(index==fluids.size()-1),
                    "Create order/fragment metadata changed in Fluid Logistics package");
                helper.assertTrue(ItemStack.isSameItemSameTags(box,restored),
                    "Actual Fluid Logistics item metadata changed");
            } catch (ReflectiveOperationException | java.io.IOException e) {
                helper.fail("Real Fluid Logistics codec integration failed: "+e);
            }
            index++;
        }
        helper.succeed();
    }


    @GameTest(template="empty",timeoutTicks=60)
    public static void mixedRpmOnConnectedChainCannotFastForwardCargo(GameTestHelper helper) {
        BlockPos first = new BlockPos(1,1,1);
        BlockPos second = new BlockPos(5,1,1);
        BlockPos portPos = new BlockPos(6,1,1);
        BlockPos motorA = new BlockPos(1,0,1);
        BlockPos motorB = new BlockPos(5,0,1);
        helper.setBlock(motorA,AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING,Direction.UP));
        helper.setBlock(motorB,AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING,Direction.UP));
        helper.setBlock(first,AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        helper.setBlock(second,AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        helper.setBlock(portPos,AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        var chainA=(ChainConveyorBlockEntity)helper.getBlockEntity(first);
        var chainB=(ChainConveyorBlockEntity)helper.getBlockEntity(second);
        var port=(FrogportBlockEntity)helper.getBlockEntity(portPos);
        var driveA=(CreativeMotorBlockEntity)helper.getBlockEntity(motorA);
        var driveB=(CreativeMotorBlockEntity)helper.getBlockEntity(motorB);
        driveA.generatedSpeed.setValue(128);
        driveA.updateGeneratedRotation();
        driveB.generatedSpeed.setValue(64);
        driveB.updateGeneratedRotation();
        var gap = chainB.getBlockPos().subtract(chainA.getBlockPos());
        chainA.connections.add(gap);
        chainB.connections.add(gap.multiply(-1));
        port.acceptsPackages = true;
        port.addressFilter = "two-speed";
        port.target = new PackagePortTarget.ChainConveyorFrogportTarget(
            chainB.getBlockPos().subtract(port.getBlockPos()),0,null);
        port.target.register(port,helper.getLevel(),port.getBlockPos());
        var start=new TransportGraph.Node(chainA.getBlockPos().getX(),
            chainA.getBlockPos().getY(),chainA.getBlockPos().getZ());
        var end=new TransportGraph.Node(chainB.getBlockPos().getX(),
            chainB.getBlockPos().getY(),chainB.getBlockPos().getZ());
        var destination=new TransportGraph.Node(port.getBlockPos().getX(),
            port.getBlockPos().getY(),port.getBlockPos().getZ());
        var route=new TransportGraph.Route(java.util.List.of(start,end),
            new TransportGraph.Endpoint(end,destination,"two-speed"),5);
        var engine=new dev.nstut.virtuallogistics.core.VirtualTransportEngine(0);
        engine.submit(new byte[]{1},route);
        helper.assertTrue(dev.nstut.virtuallogistics.forge.VirtualTransportService
            .routeStillConnected(helper.getLevel(),route),
            "Test setup must register a real connected two-segment route");
        helper.assertTrue(dev.nstut.virtuallogistics.forge.VirtualTransportService
            .effectiveRpm(helper.getLevel(),engine)==0,
            "Different node RPMs incorrectly advanced common source clock");
        helper.succeed();
    }


    @GameTest(template="empty",timeoutTicks=100)
    public static void fluidLogisticsUnpacksVirtualCargoIntoRealCreateTank(GameTestHelper helper) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("fluidlogistics")) {
            helper.succeed();
            return;
        }
        BlockPos chainPos = new BlockPos(1,1,1);
        BlockPos tankPos = new BlockPos(2,1,1);
        helper.setBlock(chainPos,AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        helper.setBlock(tankPos,AllBlocks.FLUID_TANK.getDefaultState());
        helper.runAfterDelay(10,() -> {
            var chain = (ChainConveyorBlockEntity)helper.getBlockEntity(chainPos);
            var tank = helper.getBlockEntity(tankPos);
            var ledger = VirtualLedger.get(helper.getLevel());
            var before = new java.util.HashSet<>(ledger.pending.keySet());
            try {
                var helperType = Class.forName(
                    "com.yision.fluidlogistics.content.logistics.fluidPackage.FluidPackageContentHelper");
                var create = helperType.getMethod(
                    "createCanonicalPackage",net.minecraftforge.fluids.FluidStack.class);
                var water = new net.minecraftforge.fluids.FluidStack(
                    net.minecraft.world.level.material.Fluids.WATER,1000);
                ItemStack box=(ItemStack)create.invoke(null,water);
                PackageItem.addAddress(box,"0_hno3");
                PackageItem.setOrder(box,4402,3,true,0,true,null);
                if (!chain.addLoopingPackage(new ChainConveyorPackage(0,box)))
                    helper.fail("Real fluid package was refused by virtual conveyor");
                var captured = ledger.pending.values().stream()
                    .filter(p -> !before.contains(p.id())).findFirst().orElseThrow();
                ItemStack received = ItemStack.of(net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(captured.cargo())));
                var contextType = Class.forName(
                    "com.yision.fluidlogistics.api.packager.PackageUnpackContext");
                var ctx = contextType.getConstructor(net.minecraft.world.level.Level.class,
                    BlockPos.class,net.minecraft.world.level.block.state.BlockState.class,
                    net.minecraft.world.level.block.entity.BlockEntity.class,
                    Direction.class,ItemStack.class,
                    com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.class)
                    .newInstance(helper.getLevel(),tank.getBlockPos(),tank.getBlockState(),
                        tank,Direction.UP,received,null);
                var resources = Class.forName(
                    "com.yision.fluidlogistics.api.packager.PackageResources");
                var unpack = resources.getMethod("unpackPackageResult",contextType,boolean.class);
                var handler = tank.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER,
                    Direction.UP).resolve().orElseThrow();
                int beforeAmount = handler.getFluidInTank(0).getAmount();
                Object simulated = unpack.invoke(null,ctx,true);
                int offered = (int)simulated.getClass().getMethod("transferred").invoke(simulated);
                helper.assertTrue(offered==1000
                    && handler.getFluidInTank(0).getAmount()==beforeAmount,
                    "Fluid Logistics simulation must neither refuse nor mutate real tank");
                Object executed=unpack.invoke(null,ctx,false);
                int inserted=(int)executed.getClass().getMethod("transferred").invoke(executed);
                boolean complete=(boolean)executed.getClass().getMethod("complete").invoke(executed);
                helper.assertTrue(inserted==1000 && complete,
                    "Real fluid package did not completely unpack");
                helper.assertTrue(handler.getFluidInTank(0).getAmount()==beforeAmount+1000
                    && handler.getFluidInTank(0).isFluidEqual(water),
                    "Real Create tank did not receive conserved fluid amount");
                helper.succeed();
            } catch (ReflectiveOperationException | java.io.IOException error) {
                helper.fail("Real Fluid Logistics unpacking failed: "+error);
            }
        });
    }


    @GameTest(template="empty",timeoutTicks=40)
    public static void handlerExceptionAfterInsertionDoesNotRetryDuplicate(GameTestHelper helper) {
        // Third-party handlers are disabled in production config, but a native
        // Frogport SmartInventory may also notify after mutating its slot.
        ItemStack box = PackageStyles.getDefaultBox();
        var handler = new net.minecraftforge.items.ItemStackHandler(1) {
            @Override
            public ItemStack insertItem(int slot, ItemStack incoming, boolean simulate) {
                ItemStack remainder=super.insertItem(slot,incoming,simulate);
                if (!simulate && remainder.isEmpty())
                    throw new IllegalStateException("onContentsChanged threw AFTER commit");
                return remainder;
            }
        };
        helper.assertTrue(dev.nstut.virtuallogistics.forge.VirtualTransportService
            .insertFully(handler,box),
            "A committed slot must be accepted despite post-insert notification error");
        helper.assertTrue(handler.getStackInSlot(0).getCount()==1
            && ItemStack.isSameItemSameTags(box,handler.getStackInSlot(0)),
            "One package must remain, with no duplicate or changed NBT");
        helper.assertTrue(!dev.nstut.virtuallogistics.forge.VirtualTransportService
            .insertFully(handler,box), "Full slot incorrectly accepted duplicate package");
        helper.succeed();
    }


    /**
     * This test distinguishes real immediate logical admission from the old
     * 128-item/10-tick route queue. Storage can still fill later; the network
     * must not reject or slow down in-flight logical packages.
     */
    @GameTest(template="empty",timeoutTicks=100)
    public static void admitsTwoThousandPackagesDirectlyWithoutPhysicalCongestion(GameTestHelper helper) {
        BlockPos source = new BlockPos(1,1,1);
        BlockPos portPos = new BlockPos(2,1,1);
        BlockPos motorPos = new BlockPos(1,0,1);
        helper.setBlock(motorPos,AllBlocks.CREATIVE_MOTOR.getDefaultState()
            .setValue(CreativeMotorBlock.FACING,Direction.UP));
        helper.setBlock(source,AllBlocks.CHAIN_CONVEYOR.getDefaultState());
        helper.setBlock(portPos,AllBlocks.PACKAGE_FROGPORT.getDefaultState());
        var chain=(ChainConveyorBlockEntity)helper.getBlockEntity(source);
        var port=(FrogportBlockEntity)helper.getBlockEntity(portPos);
        var motor=(CreativeMotorBlockEntity)helper.getBlockEntity(motorPos);
        motor.generatedSpeed.setValue(128);
        motor.updateGeneratedRotation();
        port.acceptsPackages=true;
        port.addressFilter="fast-admission";
        port.target=new PackagePortTarget.ChainConveyorFrogportTarget(
            chain.getBlockPos().subtract(port.getBlockPos()),0,null);
        port.target.register(port,helper.getLevel(),port.getBlockPos());
        var current=VirtualLedger.get(helper.getLevel());
        var sourceNode=new TransportGraph.Node(chain.getBlockPos().getX(),
            chain.getBlockPos().getY(),chain.getBlockPos().getZ());
        int beforePending=current.pending.size();
        int beforeFlying=current.engine(sourceNode,helper.getLevel().getGameTime()).inFlight();
        int count = Integer.getInteger("cvl.highVolumePackages", 2_000);
        helper.assertTrue(count >= 2_000 && count <= 100_000,
            "Invalid optional high-volume native benchmark count");
        long admissionStart = System.nanoTime();
        for(int i=0;i<count;i++) {
            ItemStack cargo=PackageStyles.getDefaultBox();
            PackageItem.addAddress(cargo,"fast-admission");
            if(!chain.addLoopingPackage(new ChainConveyorPackage(0,cargo)))
                helper.fail("Logical admission rejected cargo "+i);
        }
        helper.assertTrue(current.pending.size()==beforePending,
            "Known route still throttled in old deferred queue");
        long elapsedNanos = System.nanoTime() - admissionStart;
        System.out.printf("CVL native admission: %d packages, %.3f ms, %.0f packages/s%n",
            count, elapsedNanos/1e6, count/(elapsedNanos/1e9));
        helper.assertTrue(current.active.get(sourceNode).inFlight()==beforeFlying+count,
            "Packages not immediately admitted to physical-time flight");
        helper.assertTrue(chain.getLoopingPackages().isEmpty(),
            "Real Create package list grew under virtual load");
        helper.succeed();
    }


    @GameTest(template="empty",timeoutTicks=40)
    public static void visualNetworkRoundTripAndBoundsCannotOverflowClient(GameTestHelper helper) {
        try {
            var node = new TransportGraph.Node(5,6,7);
            var proxy = new dev.nstut.virtuallogistics.forge.CvlNetwork.Sample(
                java.util.List.of(node), 0.75, 0.25);
            var packet = new dev.nstut.virtuallogistics.forge.CvlNetwork.Visuals(
                java.util.List.of(proxy));
            var encoder = dev.nstut.virtuallogistics.forge.CvlNetwork.class.getDeclaredMethod(
                "encode",dev.nstut.virtuallogistics.forge.CvlNetwork.Visuals.class,
                net.minecraft.network.FriendlyByteBuf.class);
            var decoder = dev.nstut.virtuallogistics.forge.CvlNetwork.class.getDeclaredMethod(
                "decode",net.minecraft.network.FriendlyByteBuf.class);
            encoder.setAccessible(true);
            decoder.setAccessible(true);
            var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            encoder.invoke(null,packet,buffer);
            var recovered=(dev.nstut.virtuallogistics.forge.CvlNetwork.Visuals)
                decoder.invoke(null,buffer);
            helper.assertTrue(recovered.equals(packet),
                "Visual serialization changed bounded location/movement");
            boolean rejected=false;
            try {
                new dev.nstut.virtuallogistics.forge.CvlNetwork.Visuals(
                    java.util.Collections.nCopies(65,proxy));
            } catch(IllegalArgumentException e) { rejected=true; }
            helper.assertTrue(rejected, "Server encoded more than 64 proxies");
            rejected=false;
            try {
                new dev.nstut.virtuallogistics.forge.CvlNetwork.Sample(
                    java.util.Collections.nCopies(97,node),0,0);
            } catch(IllegalArgumentException e) { rejected=true; }
            helper.assertTrue(rejected, "Server encoded oversized route");
            rejected=false;
            try {
                new dev.nstut.virtuallogistics.forge.CvlNetwork.Sample(
                    java.util.List.of(node),Double.NaN,0);
            } catch(IllegalArgumentException e) { rejected=true; }
            helper.assertTrue(rejected, "NaN visual movement was permitted");

            var excessive=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            excessive.writeVarInt(65);
            try {
                decoder.invoke(null,excessive);
                helper.fail("Client accepted oversized visual sample packet");
            } catch(java.lang.reflect.InvocationTargetException expected) {
                helper.assertTrue(expected.getCause() instanceof IllegalArgumentException,
                    "Unexpected visual sample rejection");
            }
            var longRoute=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            longRoute.writeVarInt(1);
            longRoute.writeVarInt(97);
            try {
                decoder.invoke(null,longRoute);
                helper.fail("Client accepted oversized visual route packet");
            } catch(java.lang.reflect.InvocationTargetException expected) {
                helper.assertTrue(expected.getCause() instanceof IllegalArgumentException,
                    "Unexpected visual route rejection");
            }
            helper.succeed();
        } catch(ReflectiveOperationException error) {
            helper.fail("Bounded visual serialization regression: " + error);
        }
    }

}
