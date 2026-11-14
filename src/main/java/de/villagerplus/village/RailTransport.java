package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobState;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Real, finite-capacity freight. Cargo is saved ONLY by the vanilla chest minecart inventory.
 * No timer delivers goods, no unload/restart creates a replacement cart, and no teleport moves it.
 * A missing cart remains a reserved route: its inventory may still exist in an unloaded chunk.
 * Passenger visits use the same villager entity and preserve home/job memories and trade state.
 */
public final class RailTransport {
    private RailTransport() { }
    public static final int MAX_ROUTE_NODES = 4096;
    public static final int MAX_LOAD = 6 * 64;
    /** One charcoal item pays for 1,600 physical travel ticks (the vanilla charcoal burn time). */
    static final int CHARCOAL_FUEL_TICKS = 1600;
    /** Reserve ten ticks per connected rail edge, including slope and corner movement overhead. */
    private static final int FUEL_TICKS_PER_RAIL_EDGE = 10;
    /** Largest persisted fuel buffer covers any route accepted by connectedPath. */
    static final int MAX_FUEL_TICKS = MAX_ROUTE_NODES * FUEL_TICKS_PER_RAIL_EDGE;
    private static final int DOCK_TICKS = 200;
    private static final double SPEED = 0.16;
    /** Keep passenger service from interrupting a worker who has only just missed a target. */
    static final long MIN_PASSENGER_IDLE_TICKS = 1200L;

    /** Stored on the owning (lower packed coordinate) bell. Reservation survives a destroyed cart. */
    public static final AttachmentType<Map<String, UUID>> ROUTES = AttachmentRegistry.createPersistent(
        Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "rail_routes"), Codec.unboundedMap(Codec.STRING, UUIDUtil.CODEC));
    /** Stored on the entity; vanilla owns and persists the actual items independently. */
    public static final AttachmentType<Trip> TRIP = AttachmentRegistry.createPersistent(
        Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "rail_trip"), Trip.CODEC);
    public static final AttachmentType<UUID> RIDER_ID = AttachmentRegistry.createPersistent(
        Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "rail_rider_id"), UUIDUtil.CODEC);
    public static final AttachmentType<Rider> RIDER = AttachmentRegistry.createPersistent(
        Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "rail_rider"), Rider.CODEC);

    public record Rider(UUID cart, BlockPos home, boolean originalNoAi, long visitUntil, boolean visiting) {
        public static final Codec<Rider> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("cart").forGetter(Rider::cart),
            BlockPos.CODEC.fieldOf("home").forGetter(Rider::home),
            Codec.BOOL.fieldOf("original_no_ai").forGetter(Rider::originalNoAi),
            Codec.LONG.fieldOf("visit_until").forGetter(Rider::visitUntil),
            Codec.BOOL.fieldOf("visiting").forGetter(Rider::visiting)
        ).apply(i, Rider::new));
    }

    /** JobManager must skip active passengers before running housing, mining or nightly teleports. */
    public static boolean isPassenger(Villager villager) { return villager.getAttached(RIDER) != null; }

    /** Only invite a resident after the job scheduler has observed a sustained idle period. */
    static boolean passengerIsOffDuty(JobState state, long now) {
        return state != null && state.idleSince > 0 && now >= state.idleSince
            && now - state.idleSince >= MIN_PASSENGER_IDLE_TICKS;
    }

    public record Trip(BlockPos home, BlockPos other, BlockPos homeRail, BlockPos otherRail,
                       boolean toOther, boolean travelling, boolean unloading, long next, int loaded, int fuelTicks) {
        public static final Codec<Trip> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("home").forGetter(Trip::home),
            BlockPos.CODEC.fieldOf("other").forGetter(Trip::other),
            BlockPos.CODEC.fieldOf("home_rail").forGetter(Trip::homeRail),
            BlockPos.CODEC.fieldOf("other_rail").forGetter(Trip::otherRail),
            Codec.BOOL.fieldOf("to_other").forGetter(Trip::toOther),
            Codec.BOOL.fieldOf("travelling").forGetter(Trip::travelling),
            Codec.BOOL.optionalFieldOf("unloading", false).forGetter(Trip::unloading),
            Codec.LONG.fieldOf("next").forGetter(Trip::next),
            Codec.intRange(0, MAX_LOAD).optionalFieldOf("loaded", 0).forGetter(Trip::loaded),
            Codec.intRange(0, MAX_FUEL_TICKS).optionalFieldOf("fuel_ticks", 0).forGetter(Trip::fuelTicks)
        ).apply(i, Trip::new));
        /** Old call sites and old save shapes start without paid fuel; the next physical tick must pay first. */
        public Trip(BlockPos home, BlockPos other, BlockPos homeRail, BlockPos otherRail,
                    boolean toOther, boolean travelling, boolean unloading, long next, int loaded) {
            this(home, other, homeRail, otherRail, toOther, travelling, unloading, next, loaded, 0);
        }
        BlockPos destinationRail() { return toOther ? otherRail : homeRail; }
        Trip arrive(long now) { return new Trip(home, other, homeRail, otherRail, !toOther, false, true, now + DOCK_TICKS, 0, fuelTicks); }
        Trip depart() { return new Trip(home, other, homeRail, otherRail, toOther, true, false, 0, loaded, fuelTicks); }
        Trip loaded(int count) { return new Trip(home, other, homeRail, otherRail, toOther, travelling, unloading, next, Math.min(MAX_LOAD, loaded + count), fuelTicks); }
        Trip withFuelTicks(int ticks) { return new Trip(home, other, homeRail, otherRail, toOther, travelling, unloading, next, loaded, Math.max(0, Math.min(MAX_FUEL_TICKS, ticks))); }
    }

    private static final Map<ServerLevel, Set<BlockPos>> BELLS = new IdentityHashMap<>();
    private static final Map<ServerLevel, Set<AbstractMinecart>> CARTS = new IdentityHashMap<>();
    private static final Map<ServerLevel, Set<Villager>> RIDERS = new IdentityHashMap<>();
    private static final Map<UUID, Long> RIDER_LOADED = new HashMap<>();
    private static final Map<UUID, Navigation> NAV = new HashMap<>();
    private record Navigation(List<BlockPos> path, BlockPos destination, long until) { }
    private static boolean registered;

    /** Call once during mod initialization, before worlds/chunks load. */
    public static void register() {
        if (registered) return;
        registered = true;
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            for (var be : chunk.getBlockEntities().values()) if (be instanceof BellBlockEntity)
                BELLS.computeIfAbsent(level, k -> new HashSet<>()).add(be.getBlockPos());
        });
        // A village bell can also be placed into an already-loaded chunk; chunk-load alone would miss that scheduler.
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> {
            if (be instanceof BellBlockEntity) BELLS.computeIfAbsent(level, k -> new HashSet<>()).add(be.getBlockPos());
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> {
            if (be instanceof BellBlockEntity) {
                Set<BlockPos> bells = BELLS.get(level);
                if (bells != null) bells.remove(be.getBlockPos());
            }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AbstractMinecart cart && cart.getAttached(TRIP) != null)
                CARTS.computeIfAbsent(level, k -> new HashSet<>()).add(cart);
            if (entity instanceof Villager villager && isPassenger(villager)) {
                RIDERS.computeIfAbsent(level, k -> new HashSet<>()).add(villager);
                RIDER_LOADED.put(villager.getUUID(), level.getGameTime());
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof AbstractMinecart cart) {
                Set<AbstractMinecart> carts = CARTS.get(level);
                if (carts != null) carts.remove(cart);
                NAV.remove(cart.getUUID());
            }
            if (entity instanceof Villager villager) {
                Set<Villager> riders = RIDERS.get(level);
                if (riders != null) riders.remove(villager);
                RIDER_LOADED.remove(villager.getUUID());
            }
        });
        ServerTickEvents.END_LEVEL_TICK.register(level -> {
            long now = level.getGameTime();
            if (now % 200 == 0) for (BlockPos bell : List.copyOf(BELLS.getOrDefault(level, Set.of()))) {
                VillageRef ref = resolve(level, bell);
                if (ref != null) dispatch(level, ref, now);
            }
            for (AbstractMinecart cart : List.copyOf(CARTS.getOrDefault(level, Set.of()))) {
                if (!cart.isRemoved()) tick(level, cart, now);
            }
            if (now % 20 == 0) for (Villager rider : List.copyOf(RIDERS.getOrDefault(level, Set.of()))) {
                Rider state = rider.getAttached(RIDER);
                if (state == null || !rider.isAlive()) { RIDERS.get(level).remove(rider); continue; }
                // A destroyed/missing vehicle must never leave an unmounted villager permanently frozen.
                // Give nested passenger entities/chunks time to finish loading before considering recovery.
                if (!rider.isPassenger() && level.getEntity(state.cart()) == null
                    && now >= Math.max(state.visitUntil(), RIDER_LOADED.getOrDefault(rider.getUUID(), now)) + 200)
                    release(rider);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { BELLS.clear(); CARTS.clear(); NAV.clear(); RIDERS.clear(); RIDER_LOADED.clear(); });
    }

    private static VillageRef resolve(ServerLevel level, BlockPos bell) {
        if (!level.hasChunkAt(bell) || !(level.getBlockEntity(bell) instanceof BellBlockEntity be)) return null;
        return new VillageRef(bell, be.getAttachedOrCreate(VillagerPlus.VILLAGE), be);
    }

    /** Operating permission does not re-consume construction inputs. */
    static boolean permitted(ServerLevel level, VillageRef a, VillageRef b) {
        if (a == null || b == null || !Network.projectApproved(a, b)) return false;
        for (VillageRef[] pair : List.of(new VillageRef[]{a, b}, new VillageRef[]{b, a})) {
            boolean link = Network.of(pair[0].bellEntity()).links().stream().anyMatch(l ->
                l.dimension().equals(Network.dim(level)) && l.bell().equals(pair[1].bell()) && l.relation() >= Roads.METROPOLIS);
            if (!link || !Specials.has(pair[0], Blueprints.STATION)
                || !level.hasChunkAt(Specials.get(pair[0], Blueprints.STATION))) return false;
        }
        return true;
    }

    private static void dispatch(ServerLevel level, VillageRef home, long now) {
        Map<String, UUID> routes = home.bellEntity().getAttachedOrElse(ROUTES, Map.of());
        for (TradeNetwork.Link link : Network.of(home.bellEntity()).links()) {
            if (!link.dimension().equals(Network.dim(level)) || home.bell().asLong() >= link.bell().asLong()) continue;
            for (boolean passenger : new boolean[]{false, true}) {
            String key = Long.toString(link.bell().asLong());
            if (routes.containsKey(key)) continue;
            VillageRef other = Network.resolve(level, link);
            if (!permitted(level, home, other) || !Roads.railReady(level, home) || !Roads.railReady(level, other)) continue;
            List<BlockPos> path = stationPath(level, home, other);
            if (path.size() < 2) continue;
            BlockPos start = path.getFirst();
            if (!level.getEntitiesOfClass(AbstractMinecart.class, new AABB(start).inflate(2)).isEmpty()) continue;
            // The player/village must supply a real vehicle in the station, just as rails need materials.
            Container funding = null; int slot = -1;
            for (Container box : Specials.containers(level, home, Blueprints.STATION)) {
                for (int i = 0; i < box.getContainerSize(); i++) if (box.getItem(i).is(passenger ? Items.MINECART : Items.CHEST_MINECART)) {
                    funding = box; slot = i; break;
                }
                if (funding != null) break;
            }
            if (funding == null) continue;
            AbstractMinecart cart = passenger ? new Minecart(EntityTypes.MINECART, level) : new MinecartChest(EntityTypes.CHEST_MINECART, level);
            cart.setInitialPos(start.getX() + .5, start.getY() + .0625, start.getZ() + .5);
            cart.setCustomName(Component.literal((passenger ? "Personenzug: " : "Güterzug: ") + Network.villageName(home) + " ↔ " + Network.villageName(other)));
            cart.setAttached(TRIP, new Trip(home.bell(), other.bell(), start, path.getLast(), true, false, false, now + DOCK_TICKS, 0));
            // Failed insertion must neither charge stock nor reserve a route.
            if (!level.addFreshEntity(cart)) continue;
            funding.removeItem(slot, 1); funding.setChanged();
            Map<String, UUID> changed = new HashMap<>(routes); changed.put(key, cart.getUUID());
            routes = Map.copyOf(changed);
            home.bellEntity().setAttached(ROUTES, routes); home.dirty();
            CARTS.computeIfAbsent(level, k -> new HashSet<>()).add(cart);
            }
        }
    }

    static void tick(ServerLevel level, AbstractMinecart cart, long now) {
        Trip trip = cart.getAttached(TRIP);
        if (trip == null) return;
        VillageRef home = resolve(level, trip.home()), other = resolve(level, trip.other());
        if (!permitted(level, home, other)
            || !cart.getUUID().equals(home.bellEntity().getAttachedOrElse(ROUTES, Map.of()).get(Long.toString(trip.other().asLong())))) {
            // A confirmed revocation releases a passenger locally when there is a safe platform.
            // Merely unloaded endpoint chunks keep the journey paused, with the same mounted entity.
            if (home != null && other != null && cart instanceof Minecart) {
                UUID riderId = cart.getAttached(RIDER_ID);
                if (riderId != null && level.getEntity(riderId) instanceof Villager rider) {
                    BlockPos safe = rider.getVehicle() == cart ? safeDismount(level, cart, rider) : rider.blockPosition();
                    if (safe != null) {
                        if (rider.getVehicle() == cart) { rider.stopRiding(); rider.teleportTo(safe.getX() + .5, safe.getY(), safe.getZ() + .5); }
                        release(rider); cart.removeAttached(RIDER_ID);
                    }
                }
            }
            cart.setDeltaMovement(Vec3.ZERO); NAV.remove(cart.getUUID()); return;
        }
        BlockPos rail = cart.getCurrentBlockPosOrRailBelow();
        if (!safeRail(level, rail)) { cart.setDeltaMovement(Vec3.ZERO); return; }
        if (trip.travelling()) {
            if (rail.equals(trip.destinationRail()) && horizontalDistance(cart, rail) < .25) {
                cart.setDeltaMovement(Vec3.ZERO); cart.setAttached(TRIP, trip.arrive(now)); NAV.remove(cart.getUUID());
                return;
            }
            Navigation nav = NAV.get(cart.getUUID());
            if (nav == null || now >= nav.until() || !nav.destination().equals(trip.destinationRail()) || !nav.path().contains(rail)) {
                List<BlockPos> path = connectedPath(level, rail, Set.of(trip.destinationRail()));
                nav = new Navigation(path, trip.destinationRail(), now + 40); NAV.put(cart.getUUID(), nav);
            }
            int index = nav.path().indexOf(rail);
            if (index < 0 || nav.path().isEmpty()) { cart.setDeltaMovement(Vec3.ZERO); return; }
            BlockPos next = index + 1 < nav.path().size() ? nav.path().get(index + 1) : rail;
            if (!next.equals(rail) && !connected(level, rail, next)) { cart.setDeltaMovement(Vec3.ZERO); NAV.remove(cart.getUUID()); return; }
            Vec3 direction = new Vec3(next.getX() + .5 - cart.getX(), 0, next.getZ() + .5 - cart.getZ());
            double length = direction.horizontalDistance();
            if (length <= .001) { cart.setDeltaMovement(Vec3.ZERO); return; }
            if (trip.fuelTicks() <= 0 && now % 20 != 0) { cart.setDeltaMovement(Vec3.ZERO); return; }
            VillageRef fuelSource = trip.toOther() ? home : other;
            Trip fueled = burnFuelTick(level, fuelSource, trip);
            if (fueled == null) {
                // A dry locomotive waits on its current safe rail. Adding public charcoal lets this same trip resume.
                cart.setDeltaMovement(Vec3.ZERO);
                NAV.remove(cart.getUUID());
                return;
            }
            trip = fueled;
            cart.setAttached(TRIP, trip);
            // Vanilla performs all collision/rail movement. Only the throttle and direction are controlled.
            cart.setDeltaMovement(direction.scale(Math.min(SPEED, length) / length));
            return;
        }
        cart.setDeltaMovement(Vec3.ZERO);
        // No remote loading: a manually displaced or derailed cart retains its inventory untouched.
        BlockPos dock = trip.toOther() ? trip.homeRail() : trip.otherRail();
        if (!rail.equals(dock) || horizontalDistance(cart, dock) >= .3 || now % 20 != 0) return;
        VillageRef local = trip.toOther() ? home : other, target = trip.toOther() ? other : home;
        if (cart instanceof Minecart passengerCart) { passengerDock(level, passengerCart, trip, local, now); return; }
        if (!(cart instanceof MinecartChest freight)) return;
        if (trip.unloading()) {
            if (!freight.isEmpty()) { unloadOne(level, local, freight); return; }
            if (trip.toOther() && exchangeAtHome(level, freight, trip, local, now)) return;
            cart.setAttached(TRIP, new Trip(trip.home(), trip.other(), trip.homeRail(), trip.otherRail(), trip.toOther(), false, false, now + DOCK_TICKS, 0, trip.fuelTicks()));
            return;
        }
        if (now < trip.next()) {
            int room = Math.min(MAX_LOAD - trip.loaded(), cargoRoom(freight));
            if (room > 0) {
                int moved = loadOne(level, local, target, freight, room);
                if (moved > 0) cart.setAttached(TRIP, trip.loaded(moved));
            }
            return;
        }
        // Even empty return legs physically travel; a damaged/unloaded route pauses at the station.
        List<BlockPos> path = connectedPath(level, rail, Set.of(trip.destinationRail()));
        if (path.size() >= 2) {
            Trip fueled = fundLeg(level, local, trip, path);
            if (fueled != null) { cart.setAttached(TRIP, fueled.depart()); NAV.remove(cart.getUUID()); }
        }
    }

    /** Buys enough real charcoal at the loaded departure depot to cover this whole physical leg. */
    static Trip fundLeg(ServerLevel level, VillageRef source, Trip trip, List<BlockPos> path) {
        if (source == null || trip == null || path == null || path.size() < 2) return null;
        int required = requiredFuelTicks(path);
        int shortfall = Math.max(0, required - trip.fuelTicks());
        if (shortfall == 0) return trip;
        int items = (shortfall + CHARCOAL_FUEL_TICKS - 1) / CHARCOAL_FUEL_TICKS;
        if (!payCharcoal(Balance.storage(level, source), items)) return null;
        return trip.withFuelTicks(trip.fuelTicks() + items * CHARCOAL_FUEL_TICKS);
    }

    static int requiredFuelTicks(List<BlockPos> path) {
        if (path == null || path.size() < 2) return 0;
        int edges = Math.min(MAX_ROUTE_NODES, path.size() - 1);
        return edges * FUEL_TICKS_PER_RAIL_EDGE;
    }

    /** Checks the complete payment first, then removes charcoal from public stores on the server thread. */
    private static boolean payCharcoal(List<Container> stores, int items) {
        if (items <= 0) return true;
        int available = 0;
        for (Container box : stores) {
            for (int slot = 0; slot < box.getContainerSize(); slot++) {
                ItemStack stack = box.getItem(slot);
                if (stack.is(Items.CHARCOAL)) available += stack.getCount();
                if (available >= items) break;
            }
            if (available >= items) break;
        }
        if (available < items) return false;
        int remaining = items;
        for (Container box : stores) for (int slot = 0; slot < box.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = box.getItem(slot);
            if (!stack.is(Items.CHARCOAL)) continue;
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            box.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            box.setChanged();
            remaining -= take;
        }
        return remaining == 0;
    }

    /** Compatibility fallback for saved trips that run dry before receiving a full-leg reserve. */
    static Trip burnFuelTick(ServerLevel level, VillageRef source, Trip trip) {
        int fuel = trip.fuelTicks();
        if (fuel <= 0) {
            Container paymentBox = null;
            int paymentSlot = -1;
            for (Container box : Balance.storage(level, source)) {
                for (int slot = 0; slot < box.getContainerSize(); slot++) {
                    if (box.getItem(slot).is(Items.CHARCOAL)) { paymentBox = box; paymentSlot = slot; break; }
                }
                if (paymentBox != null) break;
            }
            if (paymentBox == null) return null;
            ItemStack paid = paymentBox.getItem(paymentSlot);
            paid.shrink(1);
            paymentBox.setItem(paymentSlot, paid.isEmpty() ? ItemStack.EMPTY : paid);
            paymentBox.setChanged();
            fuel = CHARCOAL_FUEL_TICKS;
        }
        return trip.withFuelTicks(fuel - 1);
    }

    private static double horizontalDistance(AbstractMinecart cart, BlockPos pos) {
        return Math.hypot(cart.getX() - pos.getX() - .5, cart.getZ() - pos.getZ() - .5);
    }

    /** One physical vehicle occupies a bilateral service. At home, empty freight and passenger
     * vehicles can be exchanged using actual station items, avoiding opposing carts on one track. */
    private static boolean exchangeAtHome(ServerLevel level, AbstractMinecart old, Trip trip, VillageRef home, long now) {
        if (!old.getPassengers().isEmpty() || (old instanceof MinecartChest box && !box.isEmpty())) return false;
        boolean passenger = old instanceof MinecartChest;
        Item nextItem = passenger ? Items.MINECART : Items.CHEST_MINECART;
        Item oldItem = passenger ? Items.CHEST_MINECART : Items.MINECART;
        for (Container station : Specials.containers(level, home, Blueprints.STATION)) {
            int funding = -1, empty = -1;
            for (int slot = 0; slot < station.getContainerSize(); slot++) {
                if (station.getItem(slot).is(nextItem)) funding = slot;
                if (station.getItem(slot).isEmpty()) empty = slot;
            }
            if (funding < 0 || (empty < 0 && station.getItem(funding).getCount() != 1)) continue;
            AbstractMinecart next = passenger ? new Minecart(EntityTypes.MINECART, level) : new MinecartChest(EntityTypes.CHEST_MINECART, level);
            next.setInitialPos(old.getX(), old.getY(), old.getZ());
            next.setCustomName(Component.literal(passenger ? "Personenzug – Dorfausflug" : "Güterzug"));
            next.setAttached(TRIP, new Trip(trip.home(), trip.other(), trip.homeRail(), trip.otherRail(), true, false, false, now + DOCK_TICKS, 0, trip.fuelTicks()));
            if (!level.addFreshEntity(next)) return false;
            station.removeItem(funding, 1);
            station.setItem(empty >= 0 ? empty : funding, new ItemStack(oldItem)); station.setChanged();
            Map<String, UUID> routes = new HashMap<>(home.bellEntity().getAttachedOrElse(ROUTES, Map.of()));
            routes.put(Long.toString(trip.other().asLong()), next.getUUID());
            home.bellEntity().setAttached(ROUTES, Map.copyOf(routes)); home.dirty();
            old.discard(); NAV.remove(old.getUUID());
            CARTS.computeIfAbsent(level, k -> new HashSet<>()).add(next);
            return true;
        }
        return false;
    }

    private static void passengerDock(ServerLevel level, Minecart cart, Trip trip, VillageRef local, long now) {
        UUID id = cart.getAttached(RIDER_ID);
        Villager rider = id != null && level.getEntity(id) instanceof Villager v ? v : null;
        if (trip.unloading()) {
            if (id != null && rider == null) return; // An unloaded passenger is never replaced by a new entity.
            if (rider != null) {
                Rider state = rider.getAttached(RIDER);
                if (state == null || !state.cart().equals(cart.getUUID())) return;
                if (rider.getVehicle() == cart) {
                    BlockPos safe = safeDismount(level, cart, rider);
                    if (safe == null) return; // Keep the rider aboard when the platform is unsafe.
                    rider.stopRiding();
                    rider.teleportTo(safe.getX() + .5, safe.getY(), safe.getZ() + .5);
                    if (trip.toOther()) {
                        release(rider); cart.removeAttached(RIDER_ID);
                        if (exchangeAtHome(level, cart, trip, local, now)) return;
                        cart.setAttached(TRIP, new Trip(trip.home(), trip.other(), trip.homeRail(), trip.otherRail(), true, false, false, now + DOCK_TICKS, 0, trip.fuelTicks()));
                        return;
                    }
                    rider.setNoAi(state.originalNoAi());
                    rider.setAttached(RIDER, new Rider(cart.getUUID(), state.home(), state.originalNoAi(), now + DOCK_TICKS, true));
                    return;
                }
                if (!trip.toOther()) {
                    if (!state.visiting() || now < state.visitUntil()) return;
                    if (rider.distanceToSqr(cart) > 4) {
                        rider.getNavigation().moveTo(cart, .65);
                        rider.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                            new net.minecraft.world.entity.ai.memory.WalkTarget(cart, .65F, 1));
                        return;
                    }
                    List<BlockPos> route = connectedPath(level, cart.getCurrentBlockPosOrRailBelow(), Set.of(trip.destinationRail()));
                    Trip fueled = fundLeg(level, local, trip, route);
                    if (fueled == null) return;
                    trip = fueled;
                    cart.setAttached(TRIP, trip);
                    rider.setNoAi(true); rider.getNavigation().stop();
                    if (!rider.startRiding(cart)) { rider.setNoAi(state.originalNoAi()); return; }
                    rider.setAttached(RIDER, new Rider(cart.getUUID(), state.home(), state.originalNoAi(), 0, false));
                }
            }
            cart.setAttached(TRIP, new Trip(trip.home(), trip.other(), trip.homeRail(), trip.otherRail(), trip.toOther(), false, false, now + 20, 0, trip.fuelTicks()));
            return;
        }
        if (id == null && trip.toOther()) {
            // Invite a nearby resident to physically walk to the train. No summoning/teleport.
            // Sleeping, trading, sick, juvenile or previously disabled residents never get recruited.
            if (!cart.getPassengers().isEmpty()) return;
            for (Villager candidate : Villages.residents(level, local, de.villagerplus.Config.VILLAGE_RADIUS,
                v -> !v.isBaby() && !v.isSleeping() && !v.isPassenger() && !v.isNoAi()
                    && v.getTradingPlayer() == null && v.getHealth() >= v.getMaxHealth() * .75F
                    && !isPassenger(v) && !Expedition.isAway(v) && !Guests.isGuest(v)
                    && passengerIsOffDuty(JobManager.stateIfKnown(v), now) && v.distanceToSqr(cart) <= 24 * 24)) {
                List<BlockPos> route = connectedPath(level, cart.getCurrentBlockPosOrRailBelow(), Set.of(trip.destinationRail()));
                Trip fueled = fundLeg(level, local, trip, route);
                if (fueled == null) return;
                trip = fueled;
                cart.setAttached(TRIP, trip);
                Rider state = new Rider(cart.getUUID(), local.bell(), candidate.isNoAi(), now + 600, false);
                candidate.setAttached(RIDER, state);
                cart.setAttached(RIDER_ID, candidate.getUUID());
                RIDERS.computeIfAbsent(level, k -> new HashSet<>()).add(candidate);
                RIDER_LOADED.put(candidate.getUUID(), now);
                rider = candidate; break;
            }
            if (rider == null) {
                if (now > trip.next() + 600) exchangeAtHome(level, cart, trip, local, now);
                return;
            }
        }
        if (now < trip.next()) return;
        if (id != null && rider == null) return;
        if (rider != null && rider.getVehicle() != cart) {
            Rider state = rider.getAttached(RIDER);
            if (state == null || !state.cart().equals(cart.getUUID())) return;
            if (trip.toOther() && state.visitUntil() > 0 && now > state.visitUntil()) {
                release(rider); cart.removeAttached(RIDER_ID); return;
            }
            if (rider.distanceToSqr(cart) > 4) {
                rider.getNavigation().moveTo(cart, .65);
                rider.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(cart, .65F, 1));
                return;
            }
            rider.setNoAi(true); rider.getNavigation().stop();
            if (!rider.startRiding(cart)) { rider.setNoAi(state.originalNoAi()); return; }
            rider.setAttached(RIDER, new Rider(cart.getUUID(), state.home(), state.originalNoAi(), 0, false));
        }
        List<BlockPos> path = connectedPath(level, cart.getCurrentBlockPosOrRailBelow(), Set.of(trip.destinationRail()));
        if (path.size() >= 2) {
            Trip fueled = fundLeg(level, local, trip, path);
            if (fueled != null) { cart.setAttached(TRIP, fueled.depart()); NAV.remove(cart.getUUID()); }
        }
    }

    private static BlockPos safeDismount(ServerLevel level, AbstractMinecart cart, Villager rider) {
        BlockPos center = cart.blockPosition();
        for (int radius = 1; radius <= 3; radius++) for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST))
            for (int dy = 1; dy >= -1; dy--) {
                BlockPos feet = center.relative(direction, radius).above(dy);
                if (!level.hasChunkAt(feet) || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                    || !level.getBlockState(feet).getFluidState().isEmpty() || !level.getBlockState(feet.above()).getFluidState().isEmpty()
                    || level.getBlockState(feet).getBlock() instanceof BaseRailBlock) continue;
                AABB bounds = rider.getBoundingBox().move(feet.getX() + .5 - rider.getX(), feet.getY() - rider.getY(), feet.getZ() + .5 - rider.getZ());
                if (level.noCollision(rider, bounds)) return feet;
            }
        return null;
    }

    /** Restores the saved AI flag on the original entity; no profession, offers, inventory, home or job writes. */
    private static void release(Villager rider) {
        Rider state = rider.getAttached(RIDER);
        if (state == null) return;
        rider.setNoAi(state.originalNoAi()); rider.removeAttached(RIDER);
        rider.getNavigation().stop();
        rider.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
        RIDER_LOADED.remove(rider.getUUID());
    }

    /** Direct container-to-container transaction: no copies are retained as a second cargo ledger. */
    static int transfer(Container from, int slot, Container to, int limit) {
        ItemStack source = from.getItem(slot);
        if (source.isEmpty() || limit <= 0 || from == to) return 0;
        int amount = Math.min(source.getCount(), limit);
        ItemStack rest = HopperBlockEntity.addItem(null, to, source.copyWithCount(amount), null);
        int moved = amount - rest.getCount();
        if (moved > 0) { source.shrink(moved); from.setItem(slot, source.isEmpty() ? ItemStack.EMPTY : source); from.setChanged(); to.setChanged(); }
        return moved;
    }

    /** Include manually loaded items in the service's cap; vanilla still owns every item. */
    static int cargoRoom(Container cargo) {
        int remaining = MAX_LOAD;
        for (int slot = 0; slot < cargo.getContainerSize() && remaining > 0; slot++)
            remaining -= Math.min(remaining, cargo.getItem(slot).getCount());
        return remaining;
    }

    private static int desired(VillageRef ref, Item item) {
        int policy = StockPolicy.target(ref, item);
        int demand = policy == StockPolicy.UNLIMITED ? 32 : policy;
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        for (String wanted : ref.data().wanted) {
            int split = wanted.lastIndexOf('#');
            if (split > 0 && wanted.substring(0, split).equals(id)) try {
                demand = Math.max(demand, Math.min(4096, Integer.parseInt(wanted.substring(split + 1))));
            } catch (NumberFormatException ignored) { }
        }
        return demand;
    }

    static int loadOne(ServerLevel level, VillageRef source, VillageRef target, MinecartChest cart, int limit) {
        if (level == null || source == null || target == null || cart == null) return 0;
        limit = Math.min(limit, cargoRoom(cart));
        if (limit <= 0) return 0;
        List<Container> stores = Balance.storage(level, source);
        Set<String> namedTargets = namedCargoTargets(target);
        // Serve explicit depot/project requests before ordinary default stock targets. A busy source depot can
        // contain many eligible surplus items; storage-slot order must not starve the named destination request.
        for (boolean namedPass : new boolean[]{true, false}) {
            for (Container box : stores) for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack stack = box.getItem(i);
                if (stack.isEmpty() || stack.is(Items.CHEST_MINECART) || stack.is(Items.MINECART)) continue;
                Item item = stack.getItem();
                boolean explicitlyRequested = StockPolicy.target(target, item) != StockPolicy.UNLIMITED
                    || namedTargets.contains(BuiltInRegistries.ITEM.getKey(item).toString());
                if (explicitlyRequested != namedPass) continue;
                int reserve = Math.max(32, desired(source, item));
                int surplus = StockPolicy.communalCount(level, source, item) - reserve;
                int carried = 0;
                for (int slot = 0; slot < cart.getContainerSize(); slot++) if (cart.getItem(slot).is(item)) carried += cart.getItem(slot).getCount();
                int demand = Math.min(desired(target, item) - StockPolicy.communalCount(level, target, item) - carried,
                    StockPolicy.deliveryRoom(level, target, item) - carried);
                int moved = transfer(box, i, cart, Math.min(limit, Math.min(surplus, demand)));
                if (moved > 0) { balance(source, target, item, moved); return moved; }
            }
        }
        return 0;
    }

    private static Set<String> namedCargoTargets(VillageRef target) {
        Set<String> itemIds = new HashSet<>();
        if (target == null || target.data() == null) return itemIds;
        for (String row : target.data().wanted) {
            if (row == null) continue;
            int separator = row.lastIndexOf('#');
            if (separator <= 0) continue;
            try {
                if (Integer.parseInt(row.substring(separator + 1)) > 0) itemIds.add(row.substring(0, separator));
            } catch (NumberFormatException ignored) { }
        }
        return itemIds;
    }

    static int unloadOne(ServerLevel level, VillageRef ref, MinecartChest cart) {
        List<Container> boxes = new ArrayList<>(Specials.containers(level, ref, Blueprints.STATION));
        boxes.addAll(Balance.storage(level, ref));
        for (int slot = 0; slot < cart.getContainerSize(); slot++) {
            ItemStack stack = cart.getItem(slot);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            int room = StockPolicy.deliveryRoom(level, ref, item);
            for (Container box : boxes) {
                int moved = transfer(cart, slot, box, Math.min(64, room));
                if (moved > 0) {
                    Trip trip = cart.getAttached(TRIP);
                    if (trip != null) {
                        VillageRef sender = resolve(level, ref.bell().equals(trip.home()) ? trip.other() : trip.home());
                        if (sender != null) {
                            String key = "rail/" + Network.pairKey(sender, ref, "freight") + "/" + BuiltInRegistries.ITEM.getKey(item);
                            Network.save(ref.bellEntity(), Network.of(ref.bellEntity()).withTradeDelta(key, moved));
                        }
                    }
                    return moved;
                }
            }
            // Full storage or a lower policy leaves cargo aboard and prevents loading/return.
        }
        return 0;
    }

    private static void balance(VillageRef source, VillageRef target, Item item, int count) {
        String key = "rail/" + Network.pairKey(source, target, "freight") + "/" + BuiltInRegistries.ITEM.getKey(item);
        Network.save(source.bellEntity(), Network.of(source.bellEntity()).withTradeDelta(key, -count));
        // Received balance is recorded at unloading, not optimistically on dispatch.
    }

    private static List<BlockPos> stationPath(ServerLevel level, VillageRef a, VillageRef b) {
        List<BlockPos> starts = stationRails(level, a), ends = stationRails(level, b);
        Set<BlockPos> goals = new HashSet<>(ends);
        for (BlockPos start : starts) {
            List<BlockPos> path = connectedPath(level, start, goals);
            if (path.size() >= 2) return path;
        }
        return List.of();
    }

    private static List<BlockPos> stationRails(ServerLevel level, VillageRef ref) {
        // Existing inter-village tracks run near the bell; station buildings can stand off that corridor.
        List<BlockPos> rails = new ArrayList<>();
        BlockPos station = Specials.get(ref, Blueprints.STATION);
        if (station == null) return rails;
        for (BlockPos pos : BlockPos.betweenClosed(station.offset(-24, -6, -24), station.offset(24, 6, 24))) {
            if (!safeRail(level, pos)) continue;
            var state = level.getBlockState(pos);
            // A slope cannot serve as a stationary loading berth under vanilla gravity.
            if (!state.getValue(((BaseRailBlock)state.getBlock()).getShapeProperty()).isSlope()) rails.add(pos.immutable());
        }
        rails.sort(Comparator.comparingDouble(p -> p.distSqr(station)));
        // Try only a bounded set of nearby boarding points; avoid repeated whole-graph walks per block.
        return rails.size() > 8 ? rails.subList(0, 8) : rails;
    }

    static boolean safeRail(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above(2))) return false;
        var state = level.getBlockState(pos);
        return state.getBlock() instanceof BaseRailBlock && state.getFluidState().isEmpty()
            && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
            && level.getBlockState(pos.above()).getFluidState().isEmpty();
    }

    /** Rail endpoints must meet in direction AND height. Diagonal near misses never count as track. */
    static boolean connected(ServerLevel level, BlockPos a, BlockPos b) {
        if (!safeRail(level, a) || !safeRail(level, b)) return false;
        int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        if (Math.abs(dx) + Math.abs(dz) != 1 || Math.abs(a.getY() - b.getY()) > 1) return false;
        Direction direction = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
        int exitA = exitHeight(level, a, direction), exitB = exitHeight(level, b, direction.getOpposite());
        return exitA != Integer.MIN_VALUE && exitA == exitB;
    }

    private static int exitHeight(ServerLevel level, BlockPos pos, Direction direction) {
        var state = level.getBlockState(pos);
        RailShape shape = state.getValue(((BaseRailBlock)state.getBlock()).getShapeProperty());
        var exits = AbstractMinecart.exits(shape);
        boolean points = false;
        for (var offset : List.of(exits.getFirst(), exits.getSecond()))
            if (offset.getX() == direction.getStepX() && offset.getZ() == direction.getStepZ()) points = true;
        if (!points) return Integer.MIN_VALUE;
        boolean high = switch (shape) {
            case ASCENDING_EAST -> direction == Direction.EAST;
            case ASCENDING_WEST -> direction == Direction.WEST;
            case ASCENDING_NORTH -> direction == Direction.NORTH;
            case ASCENDING_SOUTH -> direction == Direction.SOUTH;
            default -> false;
        };
        return pos.getY() + (high ? 1 : 0);
    }

    static List<BlockPos> connectedPath(ServerLevel level, BlockPos start, Set<BlockPos> goals) {
        if (!safeRail(level, start) || goals.isEmpty()) return List.of();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        previous.put(start, start); queue.add(start);
        while (!queue.isEmpty() && previous.size() <= MAX_ROUTE_NODES) {
            BlockPos pos = queue.removeFirst();
            if (goals.contains(pos)) {
                List<BlockPos> path = new ArrayList<>();
                for (BlockPos cursor = pos; ; cursor = previous.get(cursor)) {
                    path.add(cursor); if (cursor.equals(start)) break;
                }
                Collections.reverse(path); return List.copyOf(path);
            }
            for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST))
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos next = pos.relative(direction).above(dy);
                    if (!previous.containsKey(next) && connected(level, pos, next)) { previous.put(next, pos); queue.add(next); }
                }
        }
        return List.of();
    }

}

