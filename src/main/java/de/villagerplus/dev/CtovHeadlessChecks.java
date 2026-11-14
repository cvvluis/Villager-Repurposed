package de.villagerplus.dev;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.TradeNetwork;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.Villages;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.phys.AABB;

/** Locates a generated CTOV/vanilla village and runs smoke checks without synthetic fixtures. */
public final class CtovHeadlessChecks {
	private CtovHeadlessChecks() { }

	public static Path marker(net.minecraft.server.MinecraftServer server) {
		Path directory = server.getServerDirectory();
		if (directory == null) directory = Path.of(".");
		return directory.resolve("vp-ctov-headless.marker");
	}

	public static VillageRef bootstrap(ServerLevel level) {
		BlockPos origin = level.getRespawnData().pos();
		BlockPos located = nearestCtovVillage(level, origin);
		if (located == null) throw new IllegalStateException("CTOV headless: no ctov structure within search radius of spawn");
		int chunkX = located.getX() >> 4;
		int chunkZ = located.getZ() >> 4;
		for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
			level.setChunkForced(chunkX + dx, chunkZ + dz, true);
			level.getChunk(chunkX + dx, chunkZ + dz);
		}
		for (int i = 0; i < 40; i++) level.tick(() -> false);
		BellBlockEntity bell = findVillageBell(level, located, 8);
		if (bell == null) throw new IllegalStateException("CTOV headless: no bell with eligible residents near village structure at "
			+ located.toShortString() + "; inspect nearby chunks/meeting points before calling this a builder failure");
		BlockPos bellPos = bell.getBlockPos();
		Villages.noteChunk(level.getChunkAt(bellPos));
		if (bell.getAttached(VillagerPlus.NETWORK) == null) bell.setAttached(VillagerPlus.NETWORK, TradeNetwork.create(bellPos));
		if (bell.getAttached(VillagerPlus.VILLAGE) == null) bell.setAttached(VillagerPlus.VILLAGE, new de.villagerplus.village.VillageData());
		return new VillageRef(bellPos, bell.getAttachedOrCreate(VillagerPlus.VILLAGE), bell);
	}

	public static int run(ServerLevel level, VillageRef ref) {
		int checks = 0;
		boolean ctovMod = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("ctov");
		var villageStart = level.structureManager().getStructureWithPieceAt(ref.bell(), StructureTags.VILLAGE);
		var structureKey = villageStart != null && villageStart.isValid()
			? level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getResourceKey(villageStart.getStructure())
			: java.util.Optional.<net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure>>empty();
		boolean ctovStructure = structureKey.map(key -> key.identifier().getNamespace().equals("ctov")).orElse(false);
		check(ctovMod && ctovStructure, "bell stands inside a generated CTOV village structure"
			+ structureKey.map(key -> " (" + key.identifier() + ")").orElse("")); checks++;
		int villagers = level.getEntitiesOfClass(Villager.class, new AABB(ref.bell()).inflate(48), Villager::isAlive).size();
		check(villagers >= 2, "natural village has at least two villagers nearby (found " + villagers + ")"); checks++;
		boolean delightPot = level.registryAccess().lookupOrThrow(Registries.BLOCK)
			.get(net.minecraft.resources.Identifier.parse("farmersdelight:cooking_pot")).isPresent();
		check(delightPot, "Farmer's Delight cooking pot block is registered in this dev pack"); checks++;
		try {
			Path marker = marker(level.getServer());
			if (marker.getParent() != null) Files.createDirectories(marker.getParent());
			BlockPos bell = ref.bell();
			StringBuilder body = new StringBuilder();
			body.append(bell.getX()).append(',').append(bell.getY()).append(',').append(bell.getZ()).append('\n');
			for (String type : new String[] {
				de.villagerplus.build.Blueprints.GRANARY,
				de.villagerplus.build.Blueprints.TOOLSHED,
				de.villagerplus.build.Blueprints.SCHOOL,
				de.villagerplus.build.Blueprints.KITCHEN
			}) {
				BlockPos at = CtovMilestoneChecks.milestonePos(ref, type);
				if (at == null) throw new IllegalStateException("CTOV headless: missing " + type + " before save");
				body.append(type).append(',').append(at.getX()).append(',').append(at.getY()).append(',').append(at.getZ()).append('\n');
			}
			Files.writeString(marker, body.toString());
		} catch (java.io.IOException io) {
			throw new IllegalStateException("CTOV headless: could not write marker", io);
		}
		var data = ref.bellEntity().getAttachedOrCreate(VillagerPlus.VILLAGE);
		data.planType = "ctov-headless";
		ref.bellEntity().setAttached(VillagerPlus.VILLAGE, data);
		ref.bellEntity().setChanged();
		level.getChunkAt(ref.bell()).markUnsaved();
		System.out.println("CTOV HEADLESS PASS natural bell " + ref.bell().toShortString() + " villagers=" + villagers);
		level.getServer().saveEverything(true, true, true);
		return checks;
	}

	public static int verify(ServerLevel level) {
		Path marker = marker(level.getServer());
		if (!Files.isRegularFile(marker)) throw new IllegalStateException("CTOV verify: missing marker from prior ctov run");
		try {
			String[] lines = Files.readString(marker).trim().split("\\R");
			String[] parts = lines[0].split(",");
			BlockPos bell = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
			level.getChunkAt(bell);
			if (!(level.getBlockEntity(bell) instanceof BellBlockEntity be)) throw new IllegalStateException("CTOV verify: bell missing after restart");
			check(be.getAttached(VillagerPlus.VILLAGE) instanceof de.villagerplus.village.VillageData saved
				&& "ctov-headless".equals(saved.planType), "village attachment survived restart");
			if (lines.length < 5) throw new IllegalStateException("CTOV verify: milestone marker incomplete");
			var ref = new VillageRef(bell, be.getAttachedOrCreate(VillagerPlus.VILLAGE), be);
			int persisted = 1;
			for (int i = 1; i < lines.length; i++) {
				String[] built = lines[i].split(",");
				if (built.length < 4) throw new IllegalStateException("CTOV verify: bad milestone line " + lines[i]);
				String type = built[0];
				BlockPos at = new BlockPos(Integer.parseInt(built[1]), Integer.parseInt(built[2]), Integer.parseInt(built[3]));
				level.getChunkAt(at);
				BlockPos registered = CtovMilestoneChecks.milestonePos(ref, type);
				check(at.equals(registered), type + " registration survived restart");
				int placed = 0;
				for (BlockPos pos : BlockPos.betweenClosed(at.offset(-8, -2, -8), at.offset(8, 8, 8))) {
					if (!level.getBlockState(pos).isAir()) placed++;
				}
				check(placed >= 40, type + " blocks survived restart (" + placed + ")");
				persisted += 2;
			}
			System.out.println("CTOV HEADLESS PASS persistence after restart at " + bell.toShortString());
			return persisted;
		} catch (java.io.IOException | NumberFormatException parse) {
			throw new IllegalStateException("CTOV verify: unreadable marker", parse);
		}
	}

	private static BlockPos nearestCtovVillage(ServerLevel level, BlockPos origin) {
		var lookup = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.Structure>> ctov = new java.util.ArrayList<>();
		lookup.listElements().forEach(holder -> {
			if (holder.key().identifier().getNamespace().equals("ctov") && holder.is(StructureTags.VILLAGE)) ctov.add(holder);
		});
		if (ctov.isEmpty()) return null;
		var found = level.getChunkSource().getGenerator().findNearestMapStructure(level,
			net.minecraft.core.HolderSet.direct(ctov), origin, 64, false);
		return found == null ? null : found.getFirst();
	}

	private static BellBlockEntity findVillageBell(ServerLevel level, BlockPos center, int radiusChunks) {
		BellBlockEntity best = null;
		int bestVillagers = 0;
		int bellsSeen = 0;
		java.util.List<String> nearest = new java.util.ArrayList<>();
		int cx = center.getX() >> 4;
		int cz = center.getZ() >> 4;
		for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) {
			var chunk = level.getChunk(cx + dx, cz + dz);
			for (var blockEntity : chunk.getBlockEntities().values()) {
				if (!(blockEntity instanceof BellBlockEntity bell)) continue;
				bellsSeen++;
				// Nearby residents may belong to another CTOV bell. Observe the same
				// settlement identity the real job loop uses instead of borrowing them.
				int count = level.getEntitiesOfClass(Villager.class, new AABB(bell.getBlockPos()).inflate(48), v -> {
					if (!v.isAlive()) return false;
					var meeting = v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT);
					return meeting.isEmpty() || (meeting.get().dimension() == level.dimension()
						&& meeting.get().pos().equals(bell.getBlockPos()));
				}).size();
				if (nearest.size() < 6) nearest.add(bell.getBlockPos().toShortString() + " residents=" + count);
				if (count > bestVillagers) {
					bestVillagers = count;
					best = bell;
				}
			}
		}
		System.out.println("CTOV HEADLESS bell census center=" + center.toShortString() + " chunks=" + radiusChunks
			+ " bells=" + bellsSeen + " candidates=" + nearest);
		return best;
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("CTOV headless: " + message);
	}
}
