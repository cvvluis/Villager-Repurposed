package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Gaeste aus fremden Doerfern: einzelne Reisende oder kleine Karawanen (2-4 Personen) in der Tracht anderer Biome.
 * Sie laufen zur Dorfmitte, bringen Gastgeschenke in die Dorfkiste, bleiben einen halben Tag (man kann mit ihnen handeln)
 * und ziehen weiter. Gibt es ein freies Bett, laesst sich manchmal ein Gast dauerhaft im Dorf nieder.
 */
public final class Guests {
	public static final String TAG = "vp_guest";

	static final class State {
		final GlobalPos destination;
		final BlockPos spawn;
		final String home;
		final Network.GuestOrigin origin;
		boolean arrived;
		long leaveAt;

		State(GlobalPos destination, BlockPos spawn, String home, Network.GuestOrigin origin) {
			this.destination = destination;
			this.spawn = spawn;
			this.home = home;
			this.origin = origin;
		}
	}

	private static final Map<UUID, State> ACTIVE = new HashMap<>();
	private static final String[] TOWNS = {"Fernhausen", "Nebeltal", "Sonnenfurt", "Eisenbach", "Rabenstein", "Lindenau", "Wolkenheim", "Salzburg"};
	private static final List<ResourceKey<VillagerType>> TYPES = List.of(VillagerType.PLAINS, VillagerType.DESERT, VillagerType.SAVANNA,
		VillagerType.SNOW, VillagerType.TAIGA, VillagerType.JUNGLE, VillagerType.SWAMP);
	private static final List<ResourceKey<VillagerProfession>> PROFESSIONS = List.of(VillagerProfession.FARMER, VillagerProfession.LIBRARIAN,
		VillagerProfession.CLERIC, VillagerProfession.ARMORER, VillagerProfession.SHEPHERD, VillagerProfession.BUTCHER,
		VillagerProfession.CARTOGRAPHER, VillagerProfession.FLETCHER, VillagerProfession.TOOLSMITH);

	private Guests() {
	}

	public static void register() {
		ServerTickEvents.END_LEVEL_TICK.register(Guests::tick);
	}

	public static boolean isGuest(Villager v) {
		return v.entityTags().contains(TAG);
	}

	/** Beim Laden: Ein Gast ohne laufenden Besuch (z.B. nach einem Neustart) ist laengst weitergezogen. true = entfernt. */
	public static boolean onLoad(Villager v) {
		if (isGuest(v) && !ACTIVE.containsKey(v.getUUID())) {
			v.discard();
			return true;
		}
		return false;
	}

	// --- Ankunft --------------------------------------------------------------------------------

	public static void maybeSpawn(ServerLevel level, VillageRef ref, Villager who, long now) {
		VillageData.Extra ex = ref.data().extra;
		if (ex.nextGuests == 0L) {
			ex.nextGuests = now + 4000L;
			ref.dirty();
			return;
		}
		if (now < ex.nextGuests || ref.chestPos(level) == null) {
			return;
		}
		int pop = Villages.residents(level, ref, 48, v -> !isGuest(v)).size();
		if (pop < 4) {
			return;
		}
		GlobalPos destination = GlobalPos.of(level.dimension(), ref.bell());
		for (State s : ACTIVE.values()) {
			if (s.destination.equals(destination)) {
				return;
			}
		}
		ex.nextGuests = now + 18000L + who.getRandom().nextInt(12000);
		ref.dirty();

		boolean caravan = who.getRandom().nextInt(100) < 30;
		int count = caravan ? 2 + who.getRandom().nextInt(3) : 1;
		BlockPos at = null;
		for (int i = 0; i < 12 && at == null; i++) {
			BlockPos cand = Sites.candidate(level, ref.bell(), who.getRandom(), 34, 46);
			if (cand != null && level.getBlockState(cand).isAir()
				&& level.getBlockState(cand.below()).isFaceSturdy(level, cand.below(), net.minecraft.core.Direction.UP)) {
				at = cand;
			}
		}
		if (at == null) {
			return;
		}
		Network.GuestOrigin origin = Network.nextGuestOrigin(level, ref);
		String town = origin == null ? TOWNS[who.getRandom().nextInt(TOWNS.length)] : origin.name();
		ResourceKey<VillagerType> type = TYPES.get(who.getRandom().nextInt(TYPES.size()));
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			Villager guest = EntityTypes.VILLAGER.spawn(level, at.offset(i % 2, 0, i / 2), EntitySpawnReason.EVENT);
			if (guest == null) {
				continue;
			}
			VillagerData data = guest.getVillagerData().withType(level.registryAccess(), type)
				.withProfession(level.registryAccess(), PROFESSIONS.get(who.getRandom().nextInt(PROFESSIONS.size()))).withLevel(2 + who.getRandom().nextInt(2));
			guest.setVillagerData(data);
			guest.addTag(TAG);
			guest.setCustomName(Component.literal("Gast aus " + town));
			guest.setCustomNameVisible(false);
			ACTIVE.put(guest.getUUID(), new State(destination, at, town, origin));
			spawned++;
		}
		if (spawned > 0) {
			JobUtil.tellNearbyPlayers(level, who, 60, Component.translatable(caravan
				? "message.villagerplus.guests_group_approaching" : "message.villagerplus.guests_guest_approaching", town));
		}
	}

	// --- Besuch ---------------------------------------------------------------------------------

	/**
	 * Laedt Gaeste aus einem verbundenen Nachbardorf ein: Sie erscheinen am Dorfrand von {@code ref} und kommen wie normale Gaeste ins Dorf.
	 * Gibt die Zahl der erschienenen Gaeste zurueck.
	 */
	static int invite(ServerLevel level, VillageRef ref, Villager who, String fromName, Network.GuestOrigin origin, int count) {
		BlockPos at = null;
		for (int i = 0; i < 12 && at == null; i++) {
			BlockPos cand = Sites.candidate(level, ref.bell(), who.getRandom(), 34, 46);
			if (cand != null && level.getBlockState(cand).isAir()
				&& level.getBlockState(cand.below()).isFaceSturdy(level, cand.below(), net.minecraft.core.Direction.UP)) {
				at = cand;
			}
		}
		if (at == null) {
			return 0;
		}
		ResourceKey<VillagerType> type = TYPES.get(who.getRandom().nextInt(TYPES.size()));
		GlobalPos destination = GlobalPos.of(level.dimension(), ref.bell());
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			Villager guest = EntityTypes.VILLAGER.spawn(level, at.offset(i % 2, 0, i / 2), EntitySpawnReason.EVENT);
			if (guest == null) {
				continue;
			}
			guest.setVillagerData(guest.getVillagerData().withType(level.registryAccess(), type)
				.withProfession(level.registryAccess(), PROFESSIONS.get(who.getRandom().nextInt(PROFESSIONS.size()))).withLevel(2));
			guest.addTag(TAG);
			guest.setCustomName(Component.literal("Gast aus " + fromName));
			guest.setCustomNameVisible(false);
			ACTIVE.put(guest.getUUID(), new State(destination, at, fromName, origin));
			spawned++;
		}
		return spawned;
	}

	private static void tick(ServerLevel level) {
		if (level.getGameTime() % 40 != 0 || ACTIVE.isEmpty()) {
			return;
		}
		long now = level.getGameTime();
		List<UUID> remove = new ArrayList<>();
		for (Iterator<Map.Entry<UUID, State>> it = ACTIVE.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, State> e = it.next();
			State s = e.getValue();
			if (!s.destination.dimension().equals(level.dimension())) continue;
			BlockPos bell = s.destination.pos();
			Entity ent = level.getEntity(e.getKey());
			if (!(ent instanceof Villager guest) || !guest.isAlive()) {
				if (ent == null && !de.villagerplus.world.LoadedColumns.ready(level, bell)) {
					continue;
				}
				remove.add(e.getKey());
				continue;
			}
			var be = de.villagerplus.world.LoadedColumns.ready(level, bell) ? level.getBlockEntity(bell) : null;
			if (be == null) {
				continue;
			}
			VillageRef ref = new VillageRef(bell, be.getAttachedOrCreate(VillagerPlus.VILLAGE), be);
			BlockPos chest = ref.chestPos(level);
			BlockPos goal = chest != null ? chest : bell;
			if (!s.arrived) {
				walk(guest, goal);
				arrive(level, ref, guest, goal, s, now);
			} else if (now >= s.leaveAt) {
				if (settle(level, ref, guest, s)) {
					remove.add(e.getKey());
				} else {
					walk(guest, s.spawn);
					if (guest.blockPosition().distSqr(s.spawn) <= 36.0 || now > s.leaveAt + 6000L) {
						guest.discard();
						remove.add(e.getKey());
					}
				}
			}
		}
		remove.forEach(ACTIVE::remove);
	}

	static boolean arrive(ServerLevel level, VillageRef ref, Villager guest, BlockPos goal, State state, long now) {
		if (state.arrived || !guest.isAlive() || !isGuest(guest) || guest.blockPosition().distSqr(goal) > 64.0) return false;
		state.arrived = true;
		state.leaveAt = now + 8000L;
		Network.recordGuestContact(level, ref, state.origin);
		gift(level, ref, guest, state);
		return true;
	}

	private static void walk(Villager v, BlockPos goal) {
		v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(goal, 0.45F, 2));
	}

	/** Gastgeschenk in die Dorfkiste: Smaragde und Waren passend zum Beruf. */
	private static void gift(ServerLevel level, VillageRef ref, Villager guest, State s) {
		var prof = guest.getVillagerData().profession();
		ItemStack goods = prof.is(VillagerProfession.FARMER) ? new ItemStack(Items.BREAD, 6)
			: prof.is(VillagerProfession.LIBRARIAN) ? new ItemStack(Items.BOOK, 3)
			: prof.is(VillagerProfession.CLERIC) ? new ItemStack(Items.REDSTONE, 6)
			: prof.is(VillagerProfession.ARMORER) ? new ItemStack(Items.IRON_INGOT, 4)
			: prof.is(VillagerProfession.SHEPHERD) ? new ItemStack(Items.WOOL.white(), 4)
			: prof.is(VillagerProfession.BUTCHER) ? new ItemStack(Items.COOKED_BEEF, 4)
			: prof.is(VillagerProfession.CARTOGRAPHER) ? new ItemStack(Items.PAPER, 6)
			: prof.is(VillagerProfession.FLETCHER) ? new ItemStack(Items.ARROW, 12)
			: new ItemStack(Items.IRON_NUGGET, 9);
		if (guest.getRandom().nextInt(4) == 0) {
			dropGiftRemainder(level, ref, StockPolicy.insertVillage(level, ref, new ItemStack(Items.EMERALD, 1)));
		}
		dropGiftRemainder(level, ref, StockPolicy.insertVillage(level, ref, goods));
		JobUtil.tellNearbyPlayers(level, guest, 48, Component.translatable("message.villagerplus.guests_gifts", s.home));
	}

	private static void dropGiftRemainder(ServerLevel level, VillageRef ref, ItemStack rest) {
		if (rest.isEmpty()) return;
		BlockPos at = ref.chestPos(level) != null ? ref.chestPos(level) : ref.bell();
		level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, at.getX() + .5, at.getY() + 1, at.getZ() + .5, rest));
	}

	/** Bleibt der Gast? (freies Bett im Dorf, 35 % Chance) */
	private static boolean settle(ServerLevel level, VillageRef ref, Villager guest, State s) {
		long homes = level.getPoiManager().getCountInRange(h -> h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME), ref.bell(), 48,
			net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.HAS_SPACE);
		if (homes <= 0 || guest.getRandom().nextInt(100) >= 35) {
			return false;
		}
		guest.removeTag(TAG);
		guest.setCustomName(null);
		guest.getAttachedOrCreate(VillagerPlus.MEMORY).name = "";
		JobUtil.tellNearbyPlayers(level, guest, 48, Component.translatable("message.villagerplus.guests_settled", s.home));
		return true;
	}
}
