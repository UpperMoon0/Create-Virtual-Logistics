package dev.nstut.virtuallogistics.forge.mixin;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import dev.nstut.virtuallogistics.forge.VirtualLogisticsMod;
import dev.nstut.virtuallogistics.forge.VirtualTransportService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChainConveyorBlockEntity.class, remap = false)
public abstract class ChainConveyorMixin {

    /** A physical conveyor must never block admission to the virtual shipment ledger. */
    @Inject(method="canAcceptMorePackages", at=@At("HEAD"), cancellable=true, remap=false)
    private void cvl$unlimitedAdmission(CallbackInfoReturnable<Boolean> result) {
        if (VirtualLogisticsMod.ENABLED.get()) result.setReturnValue(true);
    }

    @Inject(method="canAcceptMorePackagesFromOtherConveyor", at=@At("HEAD"), cancellable=true, remap=false)
    private void cvl$unlimitedInterconnect(CallbackInfoReturnable<Boolean> result) {
        if (VirtualLogisticsMod.ENABLED.get()) result.setReturnValue(true);
    }

    @Inject(method="canAcceptPackagesFor", at=@At("HEAD"), cancellable=true, remap=false)
    private void cvl$unlimitedPortAdmission(BlockPos connection, CallbackInfoReturnable<Boolean> result) {
        ChainConveyorBlockEntity self = (ChainConveyorBlockEntity)(Object)this;
        if (VirtualLogisticsMod.ENABLED.get()
            && (connection == null || self.connections.contains(connection))) result.setReturnValue(true);
    }

    @Inject(method="addLoopingPackage", at=@At("HEAD"), cancellable=true, remap=false)
    private void cvl$virtualLoop(ChainConveyorPackage box, CallbackInfoReturnable<Boolean> result) {
        ChainConveyorBlockEntity self = (ChainConveyorBlockEntity)(Object)this;
        if (VirtualLogisticsMod.ENABLED.get()
            && self.getLevel() instanceof ServerLevel server
            && VirtualTransportService.capture(server, self, box)) {
            result.setReturnValue(true);
        }
    }

    @Inject(method="addTravellingPackage", at=@At("HEAD"), cancellable=true, remap=false)
    private void cvl$virtualTravel(ChainConveyorPackage box, BlockPos connection,
                                    CallbackInfoReturnable<Boolean> result) {
        ChainConveyorBlockEntity self = (ChainConveyorBlockEntity)(Object)this;
        if (!self.connections.contains(connection)) return;
        if (VirtualLogisticsMod.ENABLED.get()
            && self.getLevel() instanceof ServerLevel server
            && VirtualTransportService.capture(server, self, box)) {
            result.setReturnValue(true);
        }
    }
}
