package de.villagerplus.build;

import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Sites;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStyle;
import de.villagerplus.village.ZonePolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Real construction-site searches on the development runner's remote plateau, restoring all touched cells. */
public final class ConstructionZoneChecks {
	private ConstructionZoneChecks() { }
	private record Cell(BlockState state, CompoundTag data) { }
	private static final int FLAGS = 2 | 16 | 32;

	public static int run(ServerLevel level, VillageRef ref) {
		int passed = 0;
		List<ZonePolicy.Zone> old = ref.bellEntity().getAttached(VillagerPlus.ZONES);
		int oldMargin = Sites.margin;
		Map<BlockPos, Cell> world = new LinkedHashMap<>();
		BlockPos housing = ref.bell().offset(-44, 0, -38), workplace = ref.bell().offset(42, 0, -38), pasture = ref.bell().offset(0, 0, -48), farm = ref.bell().offset(30, 0, 28);
		prepare(level, housing, world); prepare(level, workplace, world); prepare(level, pasture, world); prepare(level, farm, world);
		VillageStyle style = new VillageStyle(Blocks.OAK_PLANKS, Blocks.COBBLESTONE, Blocks.OAK_PLANKS,
			Blocks.OAK_STAIRS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
		Palette palette = new Palette(Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.COBBLESTONE,
			Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.GLASS_PANE, Blocks.OAK_PLANKS, Blocks.OAK_DOOR, Blocks.OAK_FENCE);
		Architect.Design design = new Architect.Design(Architect.Kind.GRANARY, 3, 3, 3, true, 1, true, true,
			false, true, true, 2, 0, 0, false, 0, 0);
		try {
			Sites.margin = 3;
			zones(ref, List.of());
			BlockPos legacy = Sites.find(level, ref.bell(), RandomSource.create(381), 10, 40, 2, 6, 80);
			BlockPos unchanged = ZoneAwareSites.find(level, ref, Blueprints.HOUSE, RandomSource.create(381), 10, 40, 2, 6, 80, p -> true);
			check(legacy != null && legacy.equals(unchanged), "absent zones preserve the original search and random sequence"); passed++;
			BlockPos systematic = Sites.findSystematic(level, workplace.offset(12, 0, 0), RandomSource.create(81), 10, 10,
				-3, 3, -3, 3, 6, p -> true);
			check(systematic != null && systematic.distManhattan(workplace) <= 22,
				"systematic fallback searches a complete bounded ring for a clear civic building plot"); passed++;
			zones(ref, List.of(new ZonePolicy.Zone("unrelated", "farm", housing.getX() - 8, housing.getZ() - 8, housing.getX() + 8, housing.getZ() + 8)));
			check(legacy.equals(ZoneAwareSites.find(level, ref, Blueprints.HOUSE, RandomSource.create(381), 10, 40, 2, 6, 80, p -> true)),
				"farm zones do not change the construction search"); passed++;

			List<Step> housePlan = house(style, housing, ref);
			ZonePolicy.Zone houseZone = zoneFor("housing", housePlan);
			List<Step> workPlan = Architect.build(design, palette, workplace, Sites.facingTowards(workplace, ref.bell()));
			ZonePolicy.Zone workFootprint = zoneFor("work", workPlan);
			ZonePolicy.Zone searchEnvelope = square("work", workplace, design.half() + ZoneAwareSites.CLEARANCE);
			ZonePolicy.Zone workZone = new ZonePolicy.Zone("construction-work", "work",
				Math.min(workFootprint.minX(), searchEnvelope.minX()), Math.min(workFootprint.minZ(), searchEnvelope.minZ()),
				Math.max(workFootprint.maxX(), searchEnvelope.maxX()), Math.max(workFootprint.maxZ(), searchEnvelope.maxZ()));
			zones(ref, List.of(houseZone, workZone));
			BlockPos houseSite = ZoneAwareSites.find(level, ref, Blueprints.HOUSE, RandomSource.create(71), 10, 40, 2, 6, 4096,
				p -> ZoneAwareSites.allowsPlan(ref, Blueprints.HOUSE, house(style, p, ref)));
			check(houseSite != null && ZoneAwareSites.allowsPlan(ref, Blueprints.HOUSE, house(style, houseSite, ref)),
				"real house-plan search selects the housing parcel with its complete roof footprint"); passed++;
			BlockPos workSite = ZoneAwareSites.find(level, ref, Blueprints.GRANARY, RandomSource.create(72), 6, 40, design.half(), design.headroom(), 4096,
				p -> ZoneAwareSites.allowsPlan(ref, Blueprints.GRANARY, Architect.build(design, palette, p, Sites.facingTowards(p, ref.bell()))));
			check(workSite != null && ZoneAwareSites.allowsPlan(ref, Blueprints.GRANARY,
				Architect.build(design, palette, workSite, Sites.facingTowards(workSite, ref.bell()))),
				"real Architect search selects work parcel including porch, chimney, roof and decoration"); passed++;
			List<Step> penPlan = Blueprints.create(Blueprints.PEN, style, pasture, Sites.facingTowards(pasture, ref.bell()), 0);
			ZonePolicy.Zone pastureZone = zoneFor("pasture", penPlan);
			zones(ref, List.of(pastureZone));
			BlockPos penSite = ZoneAwareSites.find(level, ref, Blueprints.PEN, RandomSource.create(76), 6, 40, Blueprints.half(Blueprints.PEN), Blueprints.headroom(Blueprints.PEN), 4096,
				p -> ZoneAwareSites.allowsPlan(ref, Blueprints.PEN, Blueprints.create(Blueprints.PEN, style, p, Sites.facingTowards(p, ref.bell()), 0)));
			check(penSite != null && ZoneAwareSites.allowsPlan(ref, Blueprints.PEN,
				Blueprints.create(Blueprints.PEN, style, penSite, Sites.facingTowards(penSite, ref.bell()), 0)),
				"real pen-plan search selects a pasture parcel with fence and work clearance"); passed++;
			int farmVariant = 30;
			List<Step> farmPlan = Blueprints.create(Blueprints.FARM, style, farm, Sites.facingTowards(farm, ref.bell()), farmVariant);
			ZonePolicy.Zone farmZone = zoneFor("farm", farmPlan);
			zones(ref, List.of(farmZone));
			BlockPos farmSite = ZoneAwareSites.find(level, ref, Blueprints.FARM, RandomSource.create(77), 6, 40,
				farmVariant / 10, Blueprints.headroom(Blueprints.FARM), 4096,
				p -> ZoneAwareSites.allowsPlan(ref, Blueprints.FARM,
					Blueprints.create(Blueprints.FARM, style, p, Sites.facingTowards(p, ref.bell()), farmVariant)));
			check(farm.equals(farmSite) && ZoneAwareSites.allowsPlan(ref, Blueprints.FARM,
				Blueprints.create(Blueprints.FARM, style, farmSite, Sites.facingTowards(farmSite, ref.bell()), farmVariant)),
				"real Builder farm expansion selects the saved farm parcel with its complete field footprint"); passed++;
			zones(ref, List.of(square("farm", farm, farmVariant / 10)));
			check(ZoneAwareSites.find(level, ref, Blueprints.FARM, RandomSource.create(78), 6, 40,
				farmVariant / 10, Blueprints.headroom(Blueprints.FARM), 4096, p -> ZoneAwareSites.allowsPlan(ref, Blueprints.FARM,
					Blueprints.create(Blueprints.FARM, style, p, Sites.facingTowards(p, ref.bell()), farmVariant))) == null,
				"an undersized farm parcel cannot send Builder expansion into unzoned land"); passed++;
			zones(ref, List.of(houseZone, workZone));
			check(!ZoneAwareSites.allowsPlan(ref, Blueprints.HOUSE, house(style, workplace, ref))
				&& !ZoneAwareSites.allowsPlan(ref, Blueprints.GRANARY, Architect.build(design, palette, housing, Direction.EAST)),
				"work and housing parcel types cannot substitute for one another"); passed++;
			check(!ZoneAwareSites.allowsPlan(ref, Blueprints.HOUSE, house(style, housing.east(3), ref)),
				"final plan guard rejects a footprint crossing the parcel border"); passed++;

			zones(ref, List.of(square("housing", housing, 4)));
			level.setBlock(housing, Blocks.OBSIDIAN.defaultBlockState(), FLAGS);
			check(ZoneAwareSites.find(level, ref, Blueprints.HOUSE, RandomSource.create(73), 10, 40, 2, 6, 80, p -> true) == null,
				"a blocked housing parcel cannot fall back to clear unzoned land"); passed++;
			level.setBlock(housing, Blocks.AIR.defaultBlockState(), FLAGS);
			zones(ref, List.of(square("work", workplace, 3)));
			check(ZoneAwareSites.find(level, ref, Blueprints.STORAGE, RandomSource.create(74), 6, 40, 2, 6, 80, p -> true) == null,
				"an undersized work parcel cannot spill across its edge"); passed++;

			zones(ref, List.of(new ZonePolicy.Zone("asymmetric", "housing", housing.getX() - 3, housing.getZ() - 3, housing.getX() + 4, housing.getZ() + 3)));
			level.setBlock(housing.offset(-1, 0, -1), Blocks.GRASS_BLOCK.defaultBlockState(), FLAGS);
			BlockPos tolerant = ZoneAwareSites.findTolerant(level, ref, Blueprints.HOUSE, RandomSource.create(75), 10, 40,
				-1, 2, -1, 1, 6, 80, p -> true);
			check(tolerant != null && tolerant.equals(housing.above()), "zoned tolerant search accepts safe uneven terrain at the adjusted base height"); passed++;
			check(ZoneAwareSites.tolerantAnchor(level, ref, Blueprints.HOUSE, housing.east(), -1, 2, -1, 1, 6) == null,
				"asymmetric template bounds are checked before tolerant anchor selection"); passed++;
			zones(ref, List.of(square("housing", housing, 4)));
			Map<BlockPos, BlockState> gardenBefore = new LinkedHashMap<>();
			for (BlockPos p : BlockPos.betweenClosed(housing.offset(7, 0, -5), housing.offset(9, 1, 5))) gardenBefore.put(p.immutable(), level.getBlockState(p));
			Builder.Paths.garden(level, housing, Direction.EAST, ref);
			check(gardenBefore.entrySet().stream().allMatch(e -> e.getValue().equals(level.getBlockState(e.getKey()))),
				"completion gardens do not plant outside a configured housing parcel"); passed++;
			check("farm".equals(ZoneAwareSites.kindFor(Blueprints.FARM)) && "work".equals(ZoneAwareSites.kindFor(Blueprints.TOWNHALL))
				&& "work".equals(ZoneAwareSites.kindFor(Blueprints.STATION))
				&& "pasture".equals(ZoneAwareSites.kindFor(Blueprints.PEN)) && ZoneAwareSites.kindFor(Blueprints.WELL) == null,
				"classification covers work buildings and routes pens to pasture zones while leaving public outdoor infrastructure alone"); passed++;
			return passed;
		} finally {
			restore(level, world); Sites.margin = oldMargin;
			if (old == null) ref.bellEntity().removeAttached(VillagerPlus.ZONES); else ref.bellEntity().setAttached(VillagerPlus.ZONES, old);
			ref.dirty();
		}
	}

	private static List<Step> house(VillageStyle style, BlockPos p, VillageRef ref) {
		return Blueprints.create(Blueprints.HOUSE, style, p, Sites.facingTowards(p, ref.bell()), 0);
	}
	private static ZonePolicy.Zone zoneFor(String kind, List<Step> plan) {
		return new ZonePolicy.Zone("construction-" + kind, kind,
			plan.stream().mapToInt(s -> s.pos().getX()).min().orElseThrow() - ZoneAwareSites.CLEARANCE,
			plan.stream().mapToInt(s -> s.pos().getZ()).min().orElseThrow() - ZoneAwareSites.CLEARANCE,
			plan.stream().mapToInt(s -> s.pos().getX()).max().orElseThrow() + ZoneAwareSites.CLEARANCE,
			plan.stream().mapToInt(s -> s.pos().getZ()).max().orElseThrow() + ZoneAwareSites.CLEARANCE);
	}
	private static ZonePolicy.Zone square(String kind, BlockPos p, int radius) {
		return new ZonePolicy.Zone("construction-" + kind, kind, p.getX() - radius, p.getZ() - radius, p.getX() + radius, p.getZ() + radius);
	}
	private static void zones(VillageRef ref, List<ZonePolicy.Zone> zones) { ref.bellEntity().setAttached(VillagerPlus.ZONES, zones); }
	private static void prepare(ServerLevel level, BlockPos center, Map<BlockPos, Cell> before) {
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-13, -1, -13), center.offset(13, 18, 13))) {
			BlockPos pos = p.immutable(); level.getChunkAt(pos);
			BlockEntity be = level.getBlockEntity(pos);
			before.putIfAbsent(pos, new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
			level.removeBlockEntity(pos); level.setBlock(pos, pos.getY() == center.getY() - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), FLAGS);
		}
	}
	private static void restore(ServerLevel level, Map<BlockPos, Cell> before) {
		for (BlockPos pos : before.keySet()) level.removeBlockEntity(pos);
		for (BlockPos pos : before.keySet()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
		before.forEach((pos, cell) -> level.setBlock(pos, cell.state(), FLAGS));
		before.forEach((pos, cell) -> { if (cell.data() != null) {
			BlockEntity be = BlockEntity.loadStatic(pos, cell.state(), cell.data().copy(), level.registryAccess());
			if (be != null) level.setBlockEntity(be);
		} });
	}
	private static void check(boolean condition, String label) {
		if (!condition) throw new IllegalStateException("CONSTRUCTION ZONE CHECK FAILED: " + label);
		System.out.println("CONSTRUCTION ZONE PASS " + label);
	}
}
