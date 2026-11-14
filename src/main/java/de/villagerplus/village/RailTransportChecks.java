package de.villagerplus.village;

import com.mojang.serialization.JsonOps;
import de.villagerplus.job.JobState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Inventory conservation and persisted freight-state regression checks; no world mutation. */
public final class RailTransportChecks {
    private RailTransportChecks() { }

    public static int run() {
        int checks = 0;
        SimpleContainer source = new SimpleContainer(1), target = new SimpleContainer(1);
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 48));
        target.setItem(0, new ItemStack(Items.IRON_INGOT, 60));
        require(RailTransport.transfer(source, 0, target, 32) == 4, "partial destination capacity limits freight transfer"); checks++;
        require(source.getItem(0).getCount() == 44 && target.getItem(0).getCount() == 64, "partial transfer conserves exact item count"); checks++;
        require(RailTransport.transfer(source, 0, target, 32) == 0 && source.getItem(0).getCount() == 44, "full depot retains freight at its source"); checks++;
        target.setItem(0, new ItemStack(Items.GOLD_INGOT, 12));
        require(RailTransport.transfer(source, 0, target, 32) == 0 && target.getItem(0).is(Items.GOLD_INGOT), "unrelated cargo cannot be overwritten"); checks++;
        target.setItem(0, ItemStack.EMPTY);
        require(RailTransport.transfer(source, 0, target, 7) == 7 && source.getItem(0).getCount() == 37 && target.getItem(0).getCount() == 7, "per-step freight budget is enforced"); checks++;
        require(RailTransport.transfer(source, 0, source, 64) == 0 && source.getItem(0).getCount() == 37, "self transfer is rejected without mutation"); checks++;
        require(RailTransport.transfer(source, 0, target, 0) == 0, "zero transfer budget is a no-op"); checks++;
        target.setItem(0, ItemStack.EMPTY);
        require(RailTransport.transfer(source, 0, target, 64) == 37 && source.isEmpty() && target.getItem(0).getCount() == 37, "complete unloading empties source exactly once"); checks++;
        require(RailTransport.transfer(source, 0, target, 64) == 0 && target.getItem(0).getCount() == 37, "repeated unloading cannot duplicate cargo"); checks++;
        SimpleContainer cargo = new SimpleContainer(8);
        for (int slot = 0; slot < 5; slot++) cargo.setItem(slot, new ItemStack(Items.WHEAT, 64));
        cargo.setItem(5, new ItemStack(Items.IRON_INGOT, 60));
        require(RailTransport.cargoRoom(cargo) == 4, "manually loaded cargo counts towards the service capacity"); checks++;
        require(RailTransport.transfer(target, 0, cargo, RailTransport.cargoRoom(cargo)) == 4
            && RailTransport.cargoRoom(cargo) == 0 && target.getItem(0).getCount() == 33,
            "remaining capacity limits transfers without losing or duplicating inventory"); checks++;
        cargo.setItem(6, new ItemStack(Items.GOLD_INGOT, 64));
        require(RailTransport.cargoRoom(cargo) == 0, "existing over-capacity cargo remains intact and blocks additional loading"); checks++;
        var trip = new RailTransport.Trip(new BlockPos(1, 64, 1), new BlockPos(90, 65, 1),
            new BlockPos(3, 64, 1), new BlockPos(88, 65, 1), true, true, false, 0, 120);
        var encoded = RailTransport.Trip.CODEC.encodeStart(JsonOps.INSTANCE, trip).getOrThrow();
        require(trip.equals(RailTransport.Trip.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow()), "trip endpoints, direction, cargo phase and budget survive codec roundtrip"); checks++;
        var arrived = trip.arrive(1000);
        require(!arrived.toOther() && !arrived.travelling() && arrived.unloading() && arrived.loaded() == 0, "physical arrival prepares reciprocal direction and unloading"); checks++;
        require(arrived.destinationRail().equals(trip.homeRail()), "reciprocal trip uses the original home track"); checks++;
        var rider = new RailTransport.Rider(java.util.UUID.randomUUID(), trip.home(), false, 2400, true);
        var passengerJson = RailTransport.Rider.CODEC.encodeStart(JsonOps.INSTANCE, rider).getOrThrow();
        require(rider.equals(RailTransport.Rider.CODEC.parse(JsonOps.INSTANCE, passengerJson).getOrThrow()), "passenger vehicle identity, original AI and visit phase survive reload"); checks++;
        var returning = arrived.depart().arrive(3000);
        require(returning.toOther() && returning.unloading() && returning.home().equals(trip.home()), "round-trip arrival restores the original home endpoint"); checks++;
        require(!RailTransport.passengerIsOffDuty(null, 5000), "an untracked villager is never taken from its assigned work"); checks++;
        JobState working = new JobState();
        require(!RailTransport.passengerIsOffDuty(working, 5000), "a currently working villager is never recruited as a train passenger"); checks++;
        JobState recentIdle = new JobState(); recentIdle.idleSince = 1000;
        require(!RailTransport.passengerIsOffDuty(recentIdle, 1000 + RailTransport.MIN_PASSENGER_IDLE_TICKS - 1), "a brief failed work attempt does not qualify a villager for travel"); checks++;
        require(RailTransport.passengerIsOffDuty(recentIdle, 1000 + RailTransport.MIN_PASSENGER_IDLE_TICKS), "a sustained idle resident can be invited to visit a partner village"); checks++;
        require(!RailTransport.passengerIsOffDuty(recentIdle, 999), "a stale future idle timestamp cannot qualify a passenger"); checks++;
        return checks;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Rail transport: " + message);
    }
}
