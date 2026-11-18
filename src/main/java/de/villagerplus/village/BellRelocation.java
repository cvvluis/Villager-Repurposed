package de.villagerplus.village;

import com.mojang.serialization.Codec;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Built;
import de.villagerplus.build.ConstructionSite;
import de.villagerplus.village.VillageData.BuildProject;
import de.villagerplus.village.VillageData.Extra;
import de.villagerplus.village.VillageData.Mine;
import de.villagerplus.village.VillageData.PlanBounds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BellBlockEntity;

/** Serialisiert alle Dorf-Glocken-Attachments und verschiebt weltbezogene Koordinaten beim Umzug. */
public final class BellRelocation {
	public static final int FORMAT_VERSION = 1;
	public static final String RELOCATION_TAG = "villagerplus_village";
	private static final String ATTACHMENTS_KEY = "attachments";
	private static final String OLD_BELL_KEY = "old_bell";
	private static final String DIMENSION_KEY = "dimension";
	public static final String TOKEN_KEY = "token";
	private static final String VERSION_KEY = "version";

	private BellRelocation() { }

	private record AttachmentSpec<T>(AttachmentTypeRef type, Identifier id, Codec<T> codec, boolean shift) {
		AttachmentSpec(AttachmentTypeRef type, Identifier id, Codec<T> codec) {
			this(type, id, codec, true);
		}
	}

	private interface AttachmentTypeRef {
		<T> T getAttached(BellBlockEntity bell);
		<T> void setAttached(BellBlockEntity bell, T value);
	}

	private static <T> AttachmentTypeRef ref(net.fabricmc.fabric.api.attachment.v1.AttachmentType<T> type) {
		return new AttachmentTypeRef() {
			@SuppressWarnings("unchecked")
			@Override
			public <U> U getAttached(BellBlockEntity bell) {
				return (U) bell.getAttached(type);
			}

			@SuppressWarnings("unchecked")
			@Override
			public <U> void setAttached(BellBlockEntity bell, U value) {
				bell.setAttached(type, (T) value);
			}
		};
	}

	/** Alle persistenten Mod-Attachments, die an der Dorfglocke vorkommen (ohne Villager-/Tafel-spezifische Typen). */
	private static final List<AttachmentSpec<?>> BELL_SPECS = List.of(
		new AttachmentSpec<>(ref(VillagerPlus.VILLAGE), id("village"), VillageData.CODEC),
		new AttachmentSpec<>(ref(VillagerPlus.BUILDINGS), id("buildings"), Built.CODEC.listOf()),
		new AttachmentSpec<>(ref(VillagerPlus.VILLAGE_EXTENT), id("village_extent"), VillageExtent.CODEC),
		new AttachmentSpec<>(ref(VillagerPlus.VILLAGE_ADMIN), id("village_admin"), VillageAdmin.Admin.CODEC, false),
		new AttachmentSpec<>(ref(VillagerPlus.CONSTRUCTION), id("construction"), ConstructionSite.CODEC),
		new AttachmentSpec<>(ref(VillagerPlus.TEMPLATE_PLANS), id("template_plans"), Codec.LONG.listOf()),
		new AttachmentSpec<>(ref(VillagerPlus.NETWORK), id("network"), TradeNetwork.CODEC, false),
		new AttachmentSpec<>(ref(VillagerPlus.MARKET), id("market"), MarketData.CODEC),
		new AttachmentSpec<>(ref(VillagerPlus.TAVERN_REVENUE), id("tavern_revenue"), Codec.LONG, false),
		new AttachmentSpec<>(ref(VillagerPlus.LAST_SHOP), id("last_shop"), Codec.LONG, false),
		new AttachmentSpec<>(ref(VillagerPlus.JOURNAL), id("journal"), Journal.Data.CODEC, false),
		new AttachmentSpec<>(ref(VillagerPlus.SPECIALS), id("specials"), Codec.unboundedMap(Codec.STRING, BlockPos.CODEC)),
		new AttachmentSpec<>(ref(VillagerPlus.QUOTAS), id("quotas"), Codec.unboundedMap(Codec.STRING, Codec.INT), false),
		new AttachmentSpec<>(ref(VillagerPlus.STOCK_POLICIES), id("stock_policies"), Codec.unboundedMap(Codec.STRING, Codec.INT), false),
		new AttachmentSpec<>(ref(VillagerPlus.ZONES), id("zones"), ZonePolicy.Zone.CODEC.listOf()),
		new AttachmentSpec<>(ref(VillagerPlus.SITE_SEARCH), id("site_search"), SiteSearchDiagnostics.CODEC, false),
		new AttachmentSpec<>(ref(VillagerPlus.DEMOLITION), id("demolition"), Demolition.Job.CODEC.listOf()),
		new AttachmentSpec<>(ref(VillagerPlus.VILLAGE_NAME), id("village_name"), Codec.STRING, false),
		new AttachmentSpec<>(ref(VillagerPlus.ROADS), id("roads"), Codec.unboundedMap(Codec.STRING, Codec.LONG)),
		new AttachmentSpec<>(ref(VillagerPlus.LAST_MORNING_BELL), id("last_morning_bell"), Codec.LONG, false),
		new AttachmentSpec<>(ref(VillagerPlus.CARAVAN_CAMPS), id("caravan_camps"), CaravanCampData.CODEC)
	);

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, path);
	}

	public static CompoundTag export(BellBlockEntity bell, BlockPos bellPos, ResourceKey<Level> dimension, UUID token) {
		CompoundTag root = new CompoundTag();
		root.putInt(VERSION_KEY, FORMAT_VERSION);
		root.putString(TOKEN_KEY, token.toString());
		root.putString(DIMENSION_KEY, dimension.identifier().toString());
		root.put(OLD_BELL_KEY, BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, bellPos).getOrThrow());
		CompoundTag bag = new CompoundTag();
		for (AttachmentSpec<?> spec : BELL_SPECS) {
			exportOne(bell, bag, spec);
		}
		root.put(ATTACHMENTS_KEY, bag);
		return root;
	}

	private static <T> void exportOne(BellBlockEntity bell, CompoundTag bag, AttachmentSpec<T> spec) {
		T value = spec.type().getAttached(bell);
		if (value == null) return;
		String key = spec.id().toString();
		spec.codec().encodeStart(NbtOps.INSTANCE, value).ifSuccess(tag -> bag.put(key, tag));
	}

	public static ImportResult importData(BellBlockEntity bell, CompoundTag root, BlockPos newBell, ResourceKey<Level> dimension) {
		if (root.getIntOr(VERSION_KEY, 0) != FORMAT_VERSION) {
			return ImportResult.rejected("message.villagerplus.bell_relocate_version");
		}
		String dim = root.getStringOr(DIMENSION_KEY, "");
		if (!dim.isEmpty() && !dim.equals(dimension.identifier().toString())) {
			return ImportResult.rejected("message.villagerplus.bell_relocate_dimension");
		}
		Optional<BlockPos> oldBell = decodePos(root.get(OLD_BELL_KEY));
		BlockPos delta = oldBell.map(old -> newBell.subtract(old)).orElse(BlockPos.ZERO);
		CompoundTag bag = root.getCompoundOrEmpty(ATTACHMENTS_KEY);
		for (AttachmentSpec<?> spec : BELL_SPECS) {
			importOne(bell, bag, spec, delta);
		}
		List<String> notes = new ArrayList<>();
		if (!delta.equals(BlockPos.ZERO)) {
			notes.add("message.villagerplus.bell_relocate_shifted");
		}
		notes.add("message.villagerplus.bell_relocate_external");
		return ImportResult.ok(oldBell.orElse(newBell), delta, notes);
	}

	public record ImportResult(boolean success, String rejectionKey, BlockPos oldBell, BlockPos delta, List<String> noteKeys) {
		static ImportResult rejected(String key) {
			return new ImportResult(false, key, BlockPos.ZERO, BlockPos.ZERO, List.of());
		}

		static ImportResult ok(BlockPos oldBell, BlockPos delta, List<String> notes) {
			return new ImportResult(true, null, oldBell, delta, notes);
		}
	}

	private static Optional<BlockPos> decodePos(Tag tag) {
		if (tag == null) return Optional.empty();
		return BlockPos.CODEC.parse(NbtOps.INSTANCE, tag).result();
	}

	@SuppressWarnings("unchecked")
	private static <T> void importOne(BellBlockEntity bell, CompoundTag bag, AttachmentSpec<T> spec, BlockPos delta) {
		String key = spec.id().toString();
		if (!bag.contains(key)) return;
		Optional<T> decoded = spec.codec().parse(NbtOps.INSTANCE, bag.get(key)).result();
		decoded.ifPresent(value -> {
			T shifted = spec.shift() && !delta.equals(BlockPos.ZERO) ? shiftValue(value, delta, spec) : value;
			spec.type().setAttached(bell, shifted);
		});
	}

	@SuppressWarnings("unchecked")
	private static <T> T shiftValue(T value, BlockPos delta, AttachmentSpec<T> spec) {
		Identifier id = spec.id();
		if (id.equals(id("village"))) return (T) shiftVillageData((VillageData) value, delta);
		if (id.equals(id("buildings"))) return (T) shiftBuildings((List<Built>) value, delta);
		if (id.equals(id("village_extent"))) return (T) shiftExtent((VillageExtent) value, delta);
		if (id.equals(id("construction"))) return (T) shiftConstruction((ConstructionSite) value, delta);
		if (id.equals(id("template_plans"))) return (T) shiftLongPositions((List<Long>) value, delta);
		if (id.equals(id("market"))) return (T) shiftMarket((MarketData) value, delta);
		if (id.equals(id("specials"))) return (T) shiftSpecials((Map<String, BlockPos>) value, delta);
		if (id.equals(id("zones"))) return (T) shiftZones((List<ZonePolicy.Zone>) value, delta);
		if (id.equals(id("demolition"))) return (T) shiftDemolition((List<Demolition.Job>) value, delta);
		if (id.equals(id("roads"))) return (T) shiftRoads((Map<String, Long>) value, delta);
		if (id.equals(id("caravan_camps"))) return (T) shiftCaravanCamps((List<CaravanCampData.CampBlock>) value, delta);
		return value;
	}

	public static VillageData shiftVillageData(VillageData data, BlockPos delta) {
		if (data == null || delta.equals(BlockPos.ZERO)) return data;
		data.chest = data.chest.map(p -> p.offset(delta));
		data.planOrigin = data.planOrigin.map(p -> p.offset(delta));
		data.planSource = data.planSource.map(p -> p.offset(delta));
		data.mine = data.mine.map(m -> new Mine(m.entrance.offset(delta), m.dir, m.index, m.targetY, m.done));
		List<BuildProject> projects = new ArrayList<>();
		for (BuildProject project : data.buildProjects) {
			projects.add(shiftBuildProject(project, delta));
		}
		data.buildProjects.clear();
		for (BuildProject project : projects) {
			data.putBuildProject(project);
		}
		shiftExtra(data.extra, delta);
		data.syncPrimaryProjectView();
		return data;
	}

	private static void shiftExtra(Extra extra, BlockPos delta) {
		if (extra == null) return;
		extra.pen = extra.pen.map(p -> p.offset(delta));
		extra.board = extra.board.map(p -> p.offset(delta));
		extra.lager = extra.lager.map(p -> p.offset(delta));
		extra.school = extra.school.map(p -> p.offset(delta));
		shiftList(extra.lagerMore, delta);
		shiftList(extra.lagerChests, delta);
		List<Mine> mines = new ArrayList<>();
		for (Mine mine : extra.oldMines) {
			mines.add(new Mine(mine.entrance.offset(delta), mine.dir, mine.index, mine.targetY, mine.done));
		}
		extra.oldMines.clear();
		extra.oldMines.addAll(mines);
	}

	private static void shiftList(List<BlockPos> list, BlockPos delta) {
		for (int i = 0; i < list.size(); i++) {
			list.set(i, list.get(i).offset(delta));
		}
	}

	private static BuildProject shiftBuildProject(BuildProject project, BlockPos delta) {
		BlockPos origin = project.origin().offset(delta);
		Optional<BlockPos> source = project.source().map(p -> p.offset(delta));
		Optional<ConstructionSite> construction = project.construction().map(site -> shiftConstruction(site, delta));
		Optional<PlanBounds> bounds = project.bounds().map(b -> shiftPlanBounds(b, delta));
		return new BuildProject(project.type(), origin, project.dir(), project.step(), project.variant(), source,
			project.progress(), project.owner(), project.ownerUnavailableSince(), project.wanted(), project.haul(),
			project.materialWait(), construction, bounds, project.repair(), project.cleanupOnly());
	}

	private static PlanBounds shiftPlanBounds(PlanBounds bounds, BlockPos delta) {
		return new PlanBounds(bounds.minX() + delta.getX(), bounds.maxX() + delta.getX(),
			bounds.minZ() + delta.getZ(), bounds.maxZ() + delta.getZ());
	}

	private static List<Built> shiftBuildings(List<Built> buildings, BlockPos delta) {
		List<Built> out = new ArrayList<>(buildings.size());
		for (Built built : buildings) {
			out.add(new Built(built.type(), built.origin().offset(delta), built.dir(), built.variant(), built.palette()));
		}
		return out;
	}

	private static VillageExtent shiftExtent(VillageExtent extent, BlockPos delta) {
		return new VillageExtent(extent.minX() + delta.getX(), extent.minY() + delta.getY(), extent.minZ() + delta.getZ(),
			extent.maxX() + delta.getX(), extent.maxY() + delta.getY(), extent.maxZ() + delta.getZ());
	}

	private static ConstructionSite shiftConstruction(ConstructionSite site, BlockPos delta) {
		List<ConstructionSite.TemporaryBlock> temps = site.temporary().stream()
			.map(t -> new ConstructionSite.TemporaryBlock(t.pos().offset(delta), t.workbench())).toList();
		return new ConstructionSite(site.origin().offset(delta), temps, site.phaseEnds(), site.prepared(), site.paletteIds());
	}

	private static List<Long> shiftLongPositions(List<Long> encoded, BlockPos delta) {
		List<Long> out = new ArrayList<>(encoded.size());
		for (Long value : encoded) {
			out.add(BlockPos.of(value).offset(delta).asLong());
		}
		return out;
	}

	private static MarketData shiftMarket(MarketData market, BlockPos delta) {
		Optional<BlockPos> plaza = market.plaza().map(p -> p.offset(delta));
		List<MarketData.Shop> shops = market.shops().stream()
			.map(shop -> new MarketData.Shop(shop.owner(), shop.origin().offset(delta), shop.facing(), shop.kind(),
				shop.progress(), shop.opened(), shop.lastSeen(), shop.palette(), shop.deceased(), shop.retryAt(), shop.sitePrepared()))
			.toList();
		return new MarketData(plaza, shops, market.nextPlan(), market.sales());
	}

	private static Map<String, BlockPos> shiftSpecials(Map<String, BlockPos> specials, BlockPos delta) {
		Map<String, BlockPos> out = new HashMap<>();
		specials.forEach((k, v) -> out.put(k, v.offset(delta)));
		return out;
	}

	private static List<ZonePolicy.Zone> shiftZones(List<ZonePolicy.Zone> zones, BlockPos delta) {
		List<ZonePolicy.Zone> out = new ArrayList<>(zones.size());
		for (ZonePolicy.Zone zone : zones) {
			out.add(new ZonePolicy.Zone(zone.id(), zone.kind(),
				zone.minX() + delta.getX(), zone.minZ() + delta.getZ(),
				zone.maxX() + delta.getX(), zone.maxZ() + delta.getZ()));
		}
		return out;
	}

	private static List<Demolition.Job> shiftDemolition(List<Demolition.Job> jobs, BlockPos delta) {
		List<Demolition.Job> out = new ArrayList<>(jobs.size());
		for (Demolition.Job job : jobs) {
			List<BlockPos> extra = job.additionalBlocks().stream().map(p -> p.offset(delta)).toList();
			out.add(new Demolition.Job(job.origin().offset(delta), job.index(), extra, job.additionalBlocksCaptured()));
		}
		return out;
	}

	private static Map<String, Long> shiftRoads(Map<String, Long> roads, BlockPos delta) {
		Map<String, Long> out = new HashMap<>();
		roads.forEach((k, v) -> out.put(k, BlockPos.of(v).offset(delta).asLong()));
		return out;
	}

	private static List<CaravanCampData.CampBlock> shiftCaravanCamps(List<CaravanCampData.CampBlock> camps, BlockPos delta) {
		List<CaravanCampData.CampBlock> out = new ArrayList<>(camps.size());
		for (CaravanCampData.CampBlock camp : camps) {
			out.add(new CaravanCampData.CampBlock(camp.pos().offset(delta), camp.expected()));
		}
		return out;
	}
}