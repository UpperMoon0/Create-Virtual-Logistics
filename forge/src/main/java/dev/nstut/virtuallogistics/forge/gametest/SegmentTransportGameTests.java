package dev.nstut.virtuallogistics.forge.gametest;

import dev.nstut.virtuallogistics.core.TransportGraph;
import dev.nstut.virtuallogistics.forge.VirtualLogisticsMod;
import dev.nstut.virtuallogistics.forge.transport.SegmentTransportEngine;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Isolated native regression for new per-segment transport model.
 * The adapter is NOT activated in the shipping addon yet.
 */
@GameTestHolder(VirtualLogisticsMod.ID)
@PrefixGameTestTemplate(false)
public final class SegmentTransportGameTests {
    private SegmentTransportGameTests() {}

    private static final TransportGraph.Node A=new TransportGraph.Node(0,64,0);
    private static final TransportGraph.Node B=new TransportGraph.Node(10,64,0);
    private static final TransportGraph.Node C=new TransportGraph.Node(20,64,0);
    private static final TransportGraph.Node D=new TransportGraph.Node(0,64,10);
    private static final SegmentTransportEngine.Path PATH=
        new SegmentTransportEngine.Path(List.of(A,B,C),List.of(10.0,10.0));
    private static final SegmentTransportEngine.Path ALTERNATE=
        new SegmentTransportEngine.Path(List.of(A,D),List.of(10.0));

    @GameTest(template="empty",timeoutTicks=40)
    public static void mixedSegmentSpeedsPreserveActualTravelAndOfflinePause(GameTestHelper helper) {
        var engine=new SegmentTransportEngine(0);
        UUID id=engine.submit(new byte[]{1,4,8},PATH);
        SegmentTransportEngine.SpeedSource speeds=(path,leg)-> leg==0?360:180;
        engine.advance(9,speeds,p->true);
        helper.assertTrue(engine.traveled(id)==9,"First 360RPM segment must traverse exactly nine blocks");
        engine.advance(10,speeds,p->true);
        helper.assertTrue(engine.traveled(id)==10,"Parcel must cross first 10-block segment at tick ten");
        engine.advance(15,speeds,p->true);
        helper.assertTrue(engine.traveled(id)==12.5,"Half-speed second segment must advance only 2.5 blocks");
        var restored=SegmentTransportEngine.restore(engine.snapshot(),10_000);
        helper.assertTrue(restored.traveled(id)==12.5 && restored.inFlight()==1,
            "Offline snapshot restart moved or lost logical cargo");
        restored.advance(10_014,speeds,p->true);
        helper.assertTrue(restored.deliver(10_014,1,20,s->true).delivered()==0,
            "Mixed-RPM package delivered before full physical travel");
        restored.advance(10_015,speeds,p->true);
        helper.assertTrue(restored.deliver(10_015,1,20,s -> {
            helper.assertTrue(s.id().equals(id),"Shipment identity lost on two-speed path");
            helper.assertTrue(java.util.Arrays.equals(s.bytes(),new byte[]{1,4,8}),
                "Exact cargo bytes lost on two-speed path");
            return true;
        }).delivered()==1,"Parcel should arrive after exactly 30 powered ticks");
        helper.assertTrue(restored.size()==0 && restored.activePaths().isEmpty(),
            "Delivered cargo was retained in route event indexes");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void reversedConveyorsActuallyReturnParcelTowardSource(GameTestHelper helper) {
        var engine=new SegmentTransportEngine(0);
        UUID id=engine.submit(new byte[]{1},PATH);
        engine.advance(15,(p,leg)->360,p->true);
        helper.assertTrue(engine.traveled(id)==15,"Expected halfway through second segment");
        engine.advance(20,(p,leg)->-360,p->true);
        helper.assertTrue(engine.traveled(id)==10,
            "Five reverse ticks must return parcel to first/second conveyor boundary");
        engine.advance(30,(p,leg)->-360,p->true);
        helper.assertTrue(engine.traveled(id)==0 && engine.inFlight()==1,
            "Reverse movement must stop at source without deleting cargo");
        engine.advance(31,(p,leg)->360,p->true);
        helper.assertTrue(engine.traveled(id)==1,
            "Forward power restoration must move source-held cargo again");
        engine.advance(50,(p,leg)->360,p->true);
        helper.assertTrue(engine.deliver(50,1,20,s->true).delivered()==1,
            "Reversed shipment failed to complete physical travel after restart");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void powerAndTopologyPauseOnlyAffectedRoute(GameTestHelper helper) {
        var engine=new SegmentTransportEngine(0);
        UUID blocked=engine.submit(new byte[]{8},PATH);
        UUID unaffected=engine.submit(new byte[]{7},ALTERNATE);
        engine.advance(5,(p,leg)->360,path -> !path.equals(PATH));
        helper.assertTrue(engine.traveled(blocked)==0,"Broken path advanced across disconnected chain");
        helper.assertTrue(engine.traveled(unaffected)==5,
            "Independent physical chain stopped because another route is broken");
        engine.advance(15,(p,leg)-> p.equals(PATH) ? 0 : 360, path->true);
        helper.assertTrue(engine.traveled(blocked)==0,"Stopped motor advanced cargo");
        helper.assertTrue(engine.deliver(15,2,10,s->true).delivered()==1,
            "Unrelated route did not finish at tick ten");
        engine.advance(35,(p,leg)->360,path->true);
        helper.assertTrue(engine.deliver(35,1,10,s->true).delivered()==1,
            "Reconnected formerly frozen route did not finish actual 20 travel ticks");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void fullReceiverRetriesWithoutDuplicationAndBudgetExceeded(GameTestHelper helper) {
        var engine=new SegmentTransportEngine(0);
        var first=engine.submit(new byte[]{1},ALTERNATE);
        engine.submit(new byte[]{2},ALTERNATE);
        engine.advance(10,(p,leg)->360,p->true);
        helper.assertTrue(engine.size()==2 && engine.waiting()==2,
            "Two packages must reach receiver independently");
        var firstAttempt=engine.deliver(10,1,10,s->false);
        helper.assertTrue(firstAttempt.attempted()==1 && firstAttempt.delivered()==0,
            "Blocked inventory must conserve package and count one attempt");
        helper.assertTrue(engine.deliver(10,1,10,s->true).delivered()==1,
            "Delivery budget should not be throttled by refused prior shipment");
        helper.assertTrue(engine.deliver(19,1,10,s->true).attempted()==0,
            "Refused insertion was retried before delay elapsed");
        helper.assertTrue(engine.deliver(20,1,10,s->true).delivered()==1,
            "Ready storage failed to drain waiting shipment");
        helper.assertTrue(engine.size()==0 && engine.waiting()==0 && engine.inFlight()==0,
            "Cargo leaked after receiver drained");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void highRpmCrossesMultipleShortSegmentsWithinOneTick(GameTestHelper helper) {
        var shortPath=new SegmentTransportEngine.Path(List.of(A,B,C),List.of(1.0,1.0));
        var engine=new SegmentTransportEngine(0);
        engine.submit(new byte[]{1},shortPath);
        engine.advance(1,(path,leg)->3_600,p->true);
        helper.assertTrue(engine.waiting()==1,"Crossing two short powered legs within one tick failed");
        helper.assertTrue(engine.deliver(1,1,20,s->true).delivered()==1,
            "Delivery should be bounded by accumulated physical distance, not node-count delay");
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=40)
    public static void rejectsMalformedSegmentAndSnapshotAndRespectsZeroSampleCap(GameTestHelper helper) {
        boolean rejected=false;
        try {new SegmentTransportEngine.Path(List.of(A,B),List.of(0.0));}
        catch(IllegalArgumentException ex){rejected=true;}
        helper.assertTrue(rejected,"Zero-length segment was accepted");
        var engine=new SegmentTransportEngine(0);
        engine.submit(new byte[]{5},ALTERNATE);
        helper.assertTrue(engine.sample(0).isEmpty(),"Zero visual cap still sampled cargo");
        helper.assertTrue(engine.sample(1).size()==1,"One sample failed");
        rejected=false;
        try{engine.advance(2,(p,leg)->Double.NaN,p->true);}
        catch(IllegalArgumentException ex){rejected=true;}
        helper.assertTrue(rejected,"Nonfinite speed did not fail closed");
        rejected=false;
        try{SegmentTransportEngine.restore(new SegmentTransportEngine.Snapshot(
            0,Map.of(ALTERNATE,List.of(Double.NaN)),List.of()),1);}
        catch(IllegalArgumentException ex){rejected=true;}
        helper.assertTrue(rejected,"Nonfinite persisted clock accepted");
        helper.succeed();
    }
}
