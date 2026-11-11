package de.villagerplus.build;

import java.util.ArrayList;

import java.util.Comparator;

import java.util.HashSet;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.Set;

import net.minecraft.core.BlockPos;

import net.minecraft.core.Direction;

import net.minecraft.tags.ItemTags;

import net.minecraft.util.RandomSource;

import net.minecraft.world.level.block.BedBlock;

import net.minecraft.world.level.block.Block;

import net.minecraft.world.level.block.Blocks;

import net.minecraft.world.level.block.CampfireBlock;

import net.minecraft.world.level.block.DoorBlock;

import net.minecraft.world.level.block.TrapDoorBlock;

import net.minecraft.world.level.block.state.properties.Half;

import net.minecraft.world.level.block.LanternBlock;

import net.minecraft.world.level.block.SlabBlock;

import net.minecraft.world.level.block.StairBlock;

import net.minecraft.world.level.block.RailBlock;

import net.minecraft.world.level.block.PoweredRailBlock;

import net.minecraft.world.level.block.state.properties.BedPart;

import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import net.minecraft.world.level.block.state.properties.RailShape;

/**

 * Gebaeude-Generator: Baut Haeuser, Schulen und Lagerhaeuser im Stil des Dorfes ({@link Palette}), aber nie als Kopie. Jedes Gebaeude

 * wird aus einem Zufallswert ("Seed") frisch entworfen: Breite und Tiefe, Wandhoehe, Dachrichtung (Giebel quer oder laengs),

 * Dachueberstand, Sockel, Fachwerk (Balken und Pfosten), Fensterraster, Tuerposition, Vorbau mit Laternenpfosten, Schornstein.

 * Der gleiche Seed ergibt immer dasselbe Gebaeude (wichtig, damit ein Bau nach einem Neustart weitergeht).

 */

public final class Architect {

	public enum Kind { HOUSE, SCHOOL, STORAGE, GRANARY, TOOLSHED, KITCHEN, STATION }

	/** Codes are persisted inside the existing design seed; LEGACY keeps old saved plans unchanged. */
	public enum SizeTier { LEGACY, COMPACT, STANDARD, MULTI_STOREY }

	private static final int SIZE_TIER_SHIFT = 28;
	private static final int MULTI_STOREY_DECO = 4;
	private static final int ROLE_MARKER_DECO = 8;
	private static final int ROLE_MARKER_VARIANT = 1;

	/** Breite (inkl. Aussenwand) eines seitlichen Anbaus. */

	public static final int WING_W = 3;

	/** Entwurf eines Gebaeudes. {@code hw}/{@code hd}: halbe Breite (quer zur Tuer) und halbe Tiefe (Richtung Tuer). */

	public record Design(Kind kind, int hw, int hd, int wallH, boolean ridgeAlongPerp, int overhang, boolean useBase, boolean frameBeams,

		boolean halfTimber, boolean porch, boolean chimney, int windowSpacing, int doorOffset, int beds, boolean secondWallUpper,

		int wing, int deco) {

		/** Halbe Kantenlaenge des benoetigten Bauplatzes (inkl. Dachueberstand und Vorbau). */

		public int half() {

			// The site search already leaves one outer block for eaves, so only the roof's excess over
			// that margin belongs in the requested half-size. Wide eaves and chimneys used to leak past
			// this bound on function huts and legacy homes.
			int roofClearance = Math.max(hw, hd) + Math.max(0, overhang - 1);
			int chimneyClearance = chimney ? hw + 1 : 0;
			int wingClearance = wing == 0 ? 0 : hw + WING_W + 1;
			int yardClearance = kind == Kind.HOUSE ? hw + 3 : 0;
			// Special marker buildings use only their short threshold; ordinary homes/huts have
			// the decorated three-block approach and an optional outer lantern post.
			int entranceReach = schoolBlueprint(this) || storageBlueprint(this)
				? hd + (porch ? 2 : 1) : hd + (porch ? 4 : 3);
			return Math.max(Math.max(roofClearance, chimneyClearance),
				Math.max(Math.max(wingClearance, yardClearance), entranceReach));

		}

		/** Freie Hoehe ueber dem Boden, die der Bau braucht. */

		public int headroom() {

			// A multi-storey roof rises higher than the old wall-only clearance estimate.
			// The clearance height is exclusive: include the optional campfire above the chimney's last masonry block.
			if (schoolBlueprint(this)) return wallH + hd + overhang + 1;
			int clearance = multiStorey(this) ? wallH + Math.max(hw, hd) + overhang + 2 : wallH + 3;
			if (roleMarkerKitchen(this) && chimney) {
				clearance = Math.max(clearance, wallH + Math.max(hw, hd) + overhang + 2);
			}
			if (roleMarkerKind(kind) && (deco & ROLE_MARKER_DECO) != 0) {
				int roofTop = wallH + (ridgeAlongPerp ? hd : hw) + overhang;
				clearance = Math.max(clearance, roofTop + 1);
			}
			return clearance;

		}

	}

	private Architect() {

	}

	/** Stores a size tier in the two seed bits unused by the existing 22-bit design seed and 6-bit palette profile. */
	public static long withSizeTier(long seed, SizeTier tier) {
		long mask = 3L << SIZE_TIER_SHIFT;
		return (seed & ~mask) | ((long) tier.ordinal() << SIZE_TIER_SHIFT);
	}

	/** Old saved seeds have code zero and retain their original randomized design. */
	public static SizeTier sizeTier(long seed) {
		return SizeTier.values()[(int) ((seed >>> SIZE_TIER_SHIFT) & 3L)];
	}

	/** Bit zero is reserved on generated plan variants for role facades; house-template mirroring is unaffected. */
	public static int withRoleMarkerVersion(Kind kind, int planVariant) {
		return roleMarkerKind(kind) ? planVariant | ROLE_MARKER_VARIANT : planVariant;
	}

	public static boolean hasRoleMarkerVersion(Kind kind, int planVariant) {
		return roleMarkerKind(kind) && (planVariant & ROLE_MARKER_VARIANT) != 0;
	}

	/** Reconstructs a generated plan from its saved variant, retaining its historical feature version. */
	public static Design designForPlan(Kind kind, int planVariant) {
		long seed = planVariant >>> 2;
		if (!hasRoleMarkerVersion(kind, planVariant)) return design(kind, seed, false);
		return HouseDesignCatalog.jobHutDesign(kind, seed, design(kind, seed));
	}

	private static boolean roleMarkerKind(Kind kind) {
		return kind == Kind.SCHOOL || kind == Kind.STORAGE || kind == Kind.GRANARY || kind == Kind.TOOLSHED || kind == Kind.KITCHEN || kind == Kind.STATION;
	}

	private static boolean roleMarkerKitchen(Design design) {
		return design.kind() == Kind.KITCHEN && (design.deco() & ROLE_MARKER_DECO) != 0;
	}

	private static boolean storageBlueprint(Design design) {
		return design.kind() == Kind.STORAGE && (design.deco() & ROLE_MARKER_DECO) != 0;
	}

	public static boolean multiStorey(Design design) {
		return (design.deco() & MULTI_STOREY_DECO) != 0;
	}

	/** Das Rathaus: grosses zweigeschossiges Fachwerkhaus (9x7) mit Vordach und Schornstein, ohne Betten. */

	public static Design hallDesign() {

		return new Design(Kind.HOUSE, 4, 3, 5, true, 1, true, true, true, true, true, 2, 0, 0, true, 0, 0);

	}

	/** Entwurf aus Art und Seed (deterministisch). */

	public static Design design(Kind kind, long seed) {

		RandomSource r = RandomSource.create(seed);

		int hw;

		int hd;

		switch (kind) {

			case SCHOOL -> {

				hw = 3 + r.nextInt(2);

				hd = 3 + r.nextInt(2);

			}

			case STORAGE, GRANARY, TOOLSHED, KITCHEN, STATION -> {

				hw = 3 + r.nextInt(2);

				hd = 2 + r.nextInt(3);

			}

			default -> {

				// Mindestens 7x7 aussen (5x5 innen): Platz fuer Bett(en), Kiste, Arbeitsplatz und Tisch.

				hw = 3 + r.nextInt(2);

				hd = 3 + r.nextInt(2);

			}

		}

		int wallH = 3 + (kind == Kind.HOUSE ? (r.nextInt(3) == 0 ? 1 : 0) + (hw >= 3 && hd >= 3 && r.nextInt(4) == 0 ? 1 : 0) : r.nextInt(2));

		boolean ridgeAlongPerp = r.nextBoolean();

		// Dachueberstand immer: Das Dach steht ueber die Waende hinaus (schoener, schuetzt die Fassade); manchmal zwei Bloecke.

		int overhang = 1 + (r.nextInt(4) == 0 ? 1 : 0);

		boolean useBase = r.nextInt(3) != 0;

		boolean frameBeams = r.nextInt(3) != 0;

		boolean halfTimber = r.nextInt(2) == 0;

		boolean porch = r.nextInt(3) != 0;

		int wingRoll = r.nextInt(3);

		int wingSide = r.nextBoolean() ? 1 : -1;

		int wing = kind == Kind.HOUSE && ridgeAlongPerp && hw >= 3 && hd >= 3 && wingRoll == 0 ? wingSide : 0;

		boolean chimney = kind != Kind.STORAGE && wing == 0 && r.nextInt(3) != 0;

		int windowSpacing = 2 + r.nextInt(2);

		int doorOffset = r.nextInt(3) - 1;

		int beds = kind == Kind.HOUSE ? 1 + (hw >= 3 && r.nextInt(3) == 0 ? 1 : 0) : 0;

		boolean secondWallUpper = r.nextBoolean();

		int deco = r.nextInt(4);

		int profile = (int) (seed & 63);

		if ((profile & 32) != 0 && kind != Kind.STORAGE) {

			// Stil der Nachbarhaeuser: gleiche Geschosshoehe, Fachwerk ja/nein, Materialfolge (Sockel, unten, oben).

			wallH = 3 + (profile & 3) + (kind == Kind.SCHOOL ? 0 : (hw >= 3 && hd >= 3 && r.nextInt(3) == 0 ? 1 : 0));

			halfTimber = (profile & 4) != 0;

			frameBeams = halfTimber || frameBeams;

			secondWallUpper = (profile & 8) != 0;

			useBase = (profile & 16) != 0;

		}

		SizeTier sizeTier = sizeTier(seed);
		switch (sizeTier) {
			case COMPACT -> {
				if (kind == Kind.HOUSE) {
					hw = 2;
					hd = 2;
					beds = 1;
					// Compact cottages keep some of the village's covered entries; the saved seed bit makes the choice stable.
					porch = (seed & (1L << 6)) == 0;
				} else if (kind == Kind.SCHOOL) {
					hw = 3;
					hd = 3;
				} else {
					hw = 3;
					hd = 2;
				}
				wallH = 3;
				overhang = 1;
				if (kind != Kind.HOUSE) porch = (seed & (1L << 6)) != 0;
				chimney = false;
				wing = 0;
			}
			case STANDARD -> {
				hw = Math.max(3, hw);
				hd = Math.max(3, hd);
				wallH = Math.max(3, Math.min(5, wallH));
			}
			case MULTI_STOREY -> {
				hw = 3 + r.nextInt(2);
				hd = 3 + r.nextInt(2);
				wallH = 6;
				overhang = 1;
				porch = kind != Kind.HOUSE && (seed & (1L << 7)) != 0;
				wing = 0;
				beds = kind == Kind.HOUSE ? 1 : 0;
				secondWallUpper = true;
				deco |= MULTI_STOREY_DECO;
			}
			case LEGACY -> { }
		}

		return new Design(kind, hw, hd, wallH, ridgeAlongPerp, overhang, useBase, frameBeams, halfTimber, porch, chimney, windowSpacing,

			doorOffset, beds, secondWallUpper, wing, deco);

	}

	/** New role-specific variants use a curated hut profile and paid facade cues; legacy plans keep their saved geometry. */
	public static Design design(Kind kind, long seed, boolean roleMarkers) {
		Design design = design(kind, seed);
		if (!roleMarkers || !roleMarkerKind(kind)) return design;
		return HouseDesignCatalog.jobHutDesign(kind, seed, design);
	}

	private static BlockPos at(BlockPos c, Direction dir, int i, int j, int y) {

		return c.relative(dir.getClockWise(), i).relative(dir, j).above(y);

	}

	/**

	 * Baut den Entwurf. {@code c} ist die erste freie Luftschicht ueber dem Boden in der Mitte des Grundrisses, {@code dir} die Seite

	 * der Tuer.

	 */

	public static List<Step> build(Design d, Palette p, BlockPos c, Direction dir) {

		// Old construction sites restore their saved palette verbatim. Keep shelves usable as
		// classroom furniture, but never let a stale palette turn the school shell into shelves.
		final Palette materialPalette = d.kind() == Kind.SCHOOL ? withoutBookshelves(p) : p;
		p = materialPalette;

		Map<BlockPos, Step> m = new LinkedHashMap<>();
		java.util.Set<BlockPos> facadePositions = new java.util.HashSet<>();

		Direction perp = dir.getClockWise();

		int hw = d.hw();

		int hd = d.hd();

		int h = d.wallH();

		Block frame = p.frame();

		int doorI = Math.max(-hw + 1, Math.min(hw - 1, d.doorOffset()));

		// Boden: Sockelring aussen, Boden innen.

		for (int i = -hw; i <= hw; i++) {

			for (int j = -hd; j <= hd; j++) {

				boolean ring = Math.abs(i) == hw || Math.abs(j) == hd;

				put(m, Step.block(at(c, dir, i, j, -1), ring && d.useBase() ? p.base() : p.floor()));

				// Fundament: unter dem Gebaeude wird bis zum Boden aufgefuellt (am Hang schwebt nichts).

				for (int fy = -2; fy >= (ring ? -4 : -2); fy--) {

					put(m, Step.free(at(c, dir, i, j, fy), p.base().defaultBlockState()));

				}

			}

		}

		// Waende.

		for (int dy = 0; dy < h; dy++) {

			for (int i = -hw; i <= hw; i++) {

				for (int j = -hd; j <= hd; j++) {

					if (Math.abs(i) != hw && Math.abs(j) != hd) {

						continue;

					}

					boolean corner = Math.abs(i) == hw && Math.abs(j) == hd;

					Block b = d.useBase() && dy == 0 ? p.base() : (d.secondWallUpper() && dy >= (h + 1) / 2 ? p.wall2() : p.wall());

					Direction.Axis axis = Direction.Axis.Y;

					if (frame != null) {

						boolean post = d.halfTimber() && ((Math.abs(i) == hw && Math.floorMod(Math.abs(j), 3) == 0) || (Math.abs(j) == hd && Math.floorMod(Math.abs(i), 3) == 0));

						boolean beam = d.frameBeams() && dy == h - 1;

						if (corner || post || beam) {

							b = frame;

							if (beam && !corner) {

								// Balken liegen waagerecht in Wandrichtung.

								axis = Math.abs(j) == hd ? perp.getAxis() : dir.getAxis();

							}

						}

					}

					facadePositions.add(at(c, dir, i, j, dy));
					put(m, Step.block(at(c, dir, i, j, dy), b, wood(axis)));

				}

			}

		}

		// Tuer in der Frontwand (Richtung dir).

		putEntrance(m, d, p, c, dir, doorI);

		// Fenster im Raster (nicht an Ecken und nicht an der Tuer).

		for (int dy = 1; dy < h - 1; dy += 2) {

			for (int i = -hw + 1; i <= hw - 1; i++) {

				if (windowCol(d, i) && !(Math.abs(i - doorI) <= 1 && dy < 2)) {

					put(m, Step.block(at(c, dir, i, hd, dy), p.window()));

					put(m, Step.block(at(c, dir, i, -hd, dy), p.window()));

				}

			}

			for (int j = -hd + 1; j <= hd - 1; j++) {

				if (windowCol(d, j)) {

					put(m, Step.block(at(c, dir, hw, j, dy), p.window()));

					put(m, Step.block(at(c, dir, -hw, j, dy), p.window()));

				}

			}

		}

		// Die Tuerzelle selbst bleibt Tuer.

		putEntrance(m, d, p, c, dir, doorI);

		boolean stoneDetails = terracotta(p.wall()) || terracotta(p.wall2()) || p.wall() != p.wall2();

		Block stone = masonry(p.base());

		if (stoneDetails) {

			for (int i = -hw; i <= hw; i++) for (int j = -hd; j <= hd; j++) {

				if (Math.abs(i) != hw && Math.abs(j) != hd) continue;

				boolean corner = Math.abs(i) == hw && Math.abs(j) == hd;

				if (corner && p.frame() == null) for (int y = 0; y < h; y++) put(m, Step.block(at(c, dir, i, j, y), stone));

				BlockPos belt = at(c, dir, i, j, h / 2);

				Step existing = m.get(belt);

				if (h >= 4 && existing != null && existing.item() != p.door().asItem() && existing.linkedTo() == null && existing.item() != p.window().asItem()) put(m, Step.block(belt, stone));

				for (int y = 1; y < h - 1; y += 2) {

					Step win = m.get(at(c, dir, i, j, y));

					if (win != null && win.item() == p.window().asItem()) put(m, Step.block(at(c, dir, i, j, y - 1), stone));

				}

			}

		}

		if (schoolBlueprint(d)) schoolFacade(m, d, p, c, dir);

		roof(m, d, p, c, dir, perp, facadePositions);

		// Vorbau: Vordach ueber der Tuer auf zwei Zaunpfosten, darunter haengen Laternen.

		if (d.porch() && p.fence() != null) {

			Block roofTrim = p.roofSlab() instanceof SlabBlock ? p.roofSlab() : null;

			for (int side = -1; side <= 1; side += 2) {

				int i = doorI + side;

				BlockPos post = at(c, dir, i, hd + 2, 0);

				put(m, Step.block(post, p.fence()));

				put(m, Step.block(post.above(), p.fence()));

				put(m, Step.free(at(c, dir, i, hd + 1, 1), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true)));

			}

			for (int i = doorI - 1; i <= doorI + 1; i++) {

				for (int j = hd + 1; j <= hd + 2; j++) {

					BlockPos at = at(c, dir, i, j, 2);

					if (roofTrim != null) {

						put(m, Step.block(at, roofTrim));

					} else if (p.roofStair() instanceof StairBlock st) {

						Direction toWall = dir.getOpposite();

						put(m, Step.block(at, st, b -> b.defaultBlockState().setValue(StairBlock.FACING, toWall)));

					} else {

						put(m, Step.block(at, p.roofFull()));

					}

				}

			}

		}

		boolean roleSchool = schoolBlueprint(d);
		if (roleSchool) schoolEntry(m, d, p, c, dir, doorI);
		else if (storageBlueprint(d)) storageEntry(m, d, c, dir, doorI);
		else decorate(m, d, p, c, dir, perp, doorI);

		roleMarkers(m, d, p, c, dir, doorI);

		wing(m, d, p, c, dir, perp, facadePositions);

		// Schornstein: aussen an der Rueckseite der rechten Wand, aus Sockelmaterial, ueber den Dachfirst hinaus.

		if (d.chimney()) {

			Block chimney = p.base();

			int top = h + (d.ridgeAlongPerp() ? hd : hw) + d.overhang() + 1;

			for (int dy = 0; dy < top; dy++) {

				put(m, Step.block(at(c, dir, hw + 1, -hd + 1, dy), chimney));

			}

			// Kamin: Rauch ueber dem Schornstein.

			put(m, Step.free(at(c, dir, hw + 1, -hd + 1, top), Blocks.CAMPFIRE.defaultBlockState()

				.setValue(CampfireBlock.FACING, dir).setValue(CampfireBlock.LIT, true)

				.setValue(CampfireBlock.SIGNAL_FIRE, false)));

		}

		// Betten (nur Wohnhaeuser) an der Rueckwand, Fackel daneben.

		for (int k = 0; k < d.beds(); k++) {

			int bi = k == 0 ? -hw + 1 : hw - 1;

			BlockPos foot = at(c, dir, bi, -hd + 2, 0);

			BlockPos head = at(c, dir, bi, -hd + 1, 0);

			Direction headDir = dir.getOpposite();

			put(m, new Step(foot, null, ItemTags.BEDS, 1, false, null,

				b -> b.defaultBlockState().setValue(BedBlock.FACING, headDir).setValue(BedBlock.PART, BedPart.FOOT)));

			put(m, Step.linked(head, foot,

				b -> b.defaultBlockState().setValue(BedBlock.FACING, headDir).setValue(BedBlock.PART, BedPart.HEAD)));

		}

		if (roleSchool) schoolClassroom(m, d, p, c, dir);
		else put(m, Step.free(at(c, dir, hw - 1, -hd + 1, 0), Blocks.TORCH.defaultBlockState()));

		upperStorey(m, d, p, c, dir);

		// Count actual facade/gable material after doors, windows and wings have overwritten cells.

		List<Step> facade = m.values().stream().filter(st -> facadePositions.contains(st.pos()) && !st.free()

			&& (st.item() == materialPalette.wall().asItem() || st.item() == materialPalette.wall2().asItem() || st.item() == stone.asItem()

				|| (materialPalette.frame() != null && st.item() == materialPalette.frame().asItem())))

			.sorted(Comparator.comparingInt(st -> st.pos().getY())).toList();

		long terra = facade.stream().filter(st -> terracotta(Block.byItem(st.item()))).count();

		for (Step st : facade) {

			if (terra * 2 <= facade.size()) break;

			if (terracotta(Block.byItem(st.item()))) { put(m, Step.block(st.pos(), stone)); terra--; }

		}

		List<Step> steps = new ArrayList<>(m.values());

		steps.sort(Comparator.comparingInt(s -> s.pos().getY()));
		// Append the new tool-shed silhouette after the historic plan sequence. Existing marker-version
		// cursors therefore resume at the same step and can finish this paid canopy afterward.
		appendToolShedCanopy(steps, d, p, c, dir, doorI);

		return steps;

	}

	/** New storage plans use a paid two-wide opening; unmarked plans retain their original single door. */
	private static void putEntrance(Map<BlockPos, Step> m, Design d, Palette p, BlockPos c, Direction dir, int doorI) {
		Direction facing = dir.getOpposite();
		if (!storageBlueprint(d)) {
			BlockPos lower = at(c, dir, doorI, d.hd(), 0);
			BlockPos upper = lower.above();
			put(m, new Step(lower, p.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
				b -> b.defaultBlockState().setValue(DoorBlock.FACING, facing)));
			put(m, Step.linked(upper, lower,
				b -> b.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
			return;
		}

		for (int index = 0; index < 2; index++) {
			int i = doorI - 1 + index;
			BlockPos lower = at(c, dir, i, d.hd(), 0);
			BlockPos upper = lower.above();
			net.minecraft.world.level.block.state.properties.DoorHingeSide hinge = index == 0
				? net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT
				: net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT;
			put(m, new Step(lower, p.door().asItem(), ItemTags.WOODEN_DOORS, 1, false, null,
				b -> doorState(b, facing, hinge, DoubleBlockHalf.LOWER)));
			put(m, Step.linked(upper, lower, b -> doorState(b, facing, hinge, DoubleBlockHalf.UPPER)));
		}
	}

	private static net.minecraft.world.level.block.state.BlockState doorState(Block block, Direction facing,
		net.minecraft.world.level.block.state.properties.DoorHingeSide hinge, DoubleBlockHalf half) {
		var state = block.defaultBlockState().setValue(DoorBlock.FACING, facing);
		return state.setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.HALF, half);
	}

	/** A short, free path reaches the wider storehouse entrance without projecting beyond the existing lot. */
	private static void storageEntry(Map<BlockPos, Step> m, Design d, BlockPos c, Direction dir, int doorI) {
		for (int i = -1; i <= 1; i++) {
			put(m, Step.free(at(c, dir, doorI + i, d.hd() + 1, -1), Blocks.DIRT_PATH.defaultBlockState()));
		}
	}

	/** A wide, open work canopy makes the compact toolshed readable from the lane without narrowing its entry. */
	private static void appendToolShedCanopy(List<Step> steps, Design d, Palette p, BlockPos c, Direction dir, int doorI) {
		if (d.kind() != Kind.TOOLSHED || (d.deco() & ROLE_MARKER_DECO) == 0) return;
		int front = d.hd() + 2;
		if (front > d.half() + 1 || Math.abs(doorI - 2) > d.hw() || Math.abs(doorI + 2) > d.hw()) return;
		Block support = p.fence() != null ? p.fence() : p.frame() != null ? p.frame() : p.wall();
		Block canopyRoof = p.roofSlab() instanceof SlabBlock ? p.roofSlab() : p.roofFull();
		if (support == null || canopyRoof == null) return;

		List<Step> canopy = new ArrayList<>();
		for (int side : new int[] {-2, 2}) {
			for (int y = 0; y <= 1; y++) {
				canopy.add(Step.block(at(c, dir, doorI + side, front, y), support, wood(Direction.Axis.Y)));
			}
		}
		for (int i = doorI - 2; i <= doorI + 2; i++) {
			canopy.add(Step.block(at(c, dir, i, front, 2), canopyRoof));
		}

		Set<BlockPos> occupied = new HashSet<>();
		for (Step step : steps) occupied.add(step.pos());
		if (canopy.stream().anyMatch(step -> occupied.contains(step.pos()))) return;
		steps.addAll(canopy);
	}

	/** A real second floor with a three-step interior stair; its footprint stays inside the existing walls. */
	private static void upperStorey(Map<BlockPos, Step> m, Design d, Palette p, BlockPos c, Direction dir) {
		if (!multiStorey(d)) return;
		int floorY = 3;
		int stairI = d.hw() - 1;
		int stairJ = d.hd() - 2;
		for (int i = -d.hw() + 1; i < d.hw(); i++) {
			for (int j = -d.hd() + 1; j < d.hd(); j++) {
				// Keep the whole rising flight open through the upper floor. The intermediate
				// steps need two blocks of headroom too, not just the top landing.
				if (i == stairI && j >= stairJ - 2 && j <= stairJ) continue;
				put(m, Step.block(at(c, dir, i, j, floorY), p.floor()));
			}
		}
		Block stair = p.roofStair() instanceof StairBlock ? p.roofStair() : Blocks.OAK_STAIRS;
		for (int step = 0; step < 3; step++) {
			BlockPos pos = at(c, dir, stairI, stairJ - step, step);
			put(m, Step.block(pos, stair, b -> b.defaultBlockState().setValue(StairBlock.FACING, dir.getOpposite())));
		}
		// The compact houses already get a ground-floor torch; give the upper room its
		// own light on a clear floor cell away from the stairwell and sleeping space.
		put(m, Step.free(at(c, dir, -d.hw() + 1, -d.hd() + 1, floorY + 1), Blocks.LANTERN.defaultBlockState()));
	}

	private static Block masonry(Block base) {
		String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(base).getPath();
		return id.contains("stone") || id.contains("brick") || id.contains("deepslate") || id.contains("tuff") ? base : Blocks.STONE_BRICKS;
	}

	private static boolean terracotta(Block block) {

		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath().contains("terracotta");

	}

	private static boolean schoolBlueprint(Design design) {
		return design.kind() == Kind.SCHOOL && (design.deco() & ROLE_MARKER_DECO) != 0;
	}

	private static Palette withoutBookshelves(Palette palette) {
		Block wall = withoutBookshelves(palette.wall(), Blocks.COBBLESTONE);
		return new Palette(
			wall,
			withoutBookshelves(palette.wall2(), wall),
			withoutBookshelves(palette.frame(), Blocks.OAK_LOG),
			withoutBookshelves(palette.base(), Blocks.COBBLESTONE),
			withoutBookshelves(palette.roofFull(), Blocks.OAK_PLANKS),
			withoutBookshelves(palette.roofStair(), Blocks.OAK_STAIRS),
			withoutBookshelves(palette.roofSlab(), Blocks.OAK_SLAB),
			withoutBookshelves(palette.window(), Blocks.GLASS_PANE),
			withoutBookshelves(palette.floor(), Blocks.OAK_PLANKS),
			withoutBookshelves(palette.door(), Blocks.OAK_DOOR),
			withoutBookshelves(palette.fence(), Blocks.OAK_FENCE));
	}

	private static Block withoutBookshelves(Block material, Block fallback) {
		return material == Blocks.BOOKSHELF || material == Blocks.CHISELED_BOOKSHELF
			? fallback : material;
	}

	/** Small paved threshold and palette-framed doorway; avoids a long yard entering the furnishing bounds. */
	private static void schoolEntry(Map<BlockPos, Step> m, Design d, Palette p, BlockPos c, Direction dir, int doorI) {
		Block frame = p.frame() != null ? p.frame() : p.wall();
		for (int side : new int[]{-1, 1}) {
			for (int y = 0; y <= 1; y++) {
				put(m, Step.block(at(c, dir, doorI + side, d.hd(), y), frame, wood(Direction.Axis.Y)));
			}
		}
		for (int i = -1; i <= 1; i++) put(m, Step.free(at(c, dir, i, d.hd() + 1, -1), Blocks.DIRT_PATH.defaultBlockState()));
	}

	/** Balanced stone window heads tie the paired classroom windows to the timber-framed, stone-based facade. */
	private static void schoolFacade(Map<BlockPos, Step> m, Design d, Palette p, BlockPos c, Direction dir) {
		Block stone = schoolMasonry(p.base());
		for (int side : new int[] {-1, 1}) {
			int windowI = side * (d.hw() - 1);
			put(m, Step.block(at(c, dir, windowI, d.hd(), 2), stone));
		}
	}

	private static Block schoolMasonry(Block base) {
		Block stone = masonry(base);
		return Supply.obtainable(stone) ? stone : Blocks.COBBLESTONE;
	}

	/** Stable, paid stone identity for the school lintel across palette choices. */
	private static Block schoolMarkerStone() {
		return Supply.obtainable(Blocks.STONE_BRICKS) ? Blocks.STONE_BRICKS : Blocks.COBBLESTONE;
	}

	/** Paid classroom core. Shelves, lectern and two reachable seats stay inside the shared 7x7 school room. */
	private static void schoolClassroom(Map<BlockPos, Step> m, Design d, Palette p, BlockPos c, Direction dir) {
		int back = -d.hd() + 1;
		put(m, Step.block(at(c, dir, 0, back, 0), Blocks.LECTERN,
			b -> b.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, dir)));
		for (int side : new int[]{-1, 1}) {
			put(m, Step.block(at(c, dir, side * 2, back, 0), Blocks.BOOKSHELF));
		}
		Block seats = p.roofStair() instanceof StairBlock ? p.roofStair() : Blocks.OAK_STAIRS;
		for (int side : new int[]{-1, 1}) {
			put(m, Step.block(at(c, dir, side, 0, 0), seats,
				b -> b.defaultBlockState().setValue(StairBlock.FACING, dir.getOpposite())));
		}
	}

	/** Zustand des bezahlten Blocks; Baumstaemme bekommen die gewuenschte Achse (waagerechte Balken). */

	private static java.util.function.Function<Block, net.minecraft.world.level.block.state.BlockState> wood(Direction.Axis axis) {

		return b -> {

			net.minecraft.world.level.block.state.BlockState st = b.defaultBlockState();

			return st.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)

				? st.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, axis) : st;

		};

	}

	/**

	 * Fensterspalte (symmetrisch zur Wandmitte): mit Fachwerk zwischen den Pfosten (|i| mod 3 = 1), sonst im Takt der Fensterweite

	 * (2: jede zweite Spalte, 3: Mitte und alle dritte).

	 */

	private static boolean windowCol(Design d, int i) {
		// A wide wall-window rhythm stays clear of the centered school door and the gable frame.
		if (schoolBlueprint(d)) return Math.abs(i) == d.hw() - 1;

		int a = Math.abs(i);

		if (d.halfTimber()) {

			return a % 3 == 1;

		}

		return d.windowSpacing() == 2 ? a % 2 == 1 : a % 3 == 0;

	}

	private static void put(Map<BlockPos, Step> m, Step s) {

		m.put(s.pos().immutable(), s);

	}

	/**

	 * Satteldach: Treppen steigen nach innen an (Hochseite zum First), der First besteht aus Vollbloecken, die Giebelflaechen aus

	 * Wandmaterial mit Fachwerk-Schraege und Giebelfenster, ein Dachueberstand wird an der Traufe unterfuettert. Ohne Treppen im

	 * Dorfstil: flaches Dach.

	 */

	private static void roof(Map<BlockPos, Step> m, Design d, Palette p, BlockPos c, Direction dir, Direction perp, java.util.Set<BlockPos> facadePositions) {

		int h = d.wallH();

		boolean pitched = p.roofStair() instanceof StairBlock;

		int wallS = d.ridgeAlongPerp() ? d.hd() : d.hw();

		int wallR = d.ridgeAlongPerp() ? d.hw() : d.hd();

		int eave = wallS + d.overhang();

		int reach = wallR + d.overhang();

		if (!pitched) {

			Block flat = p.roofSlab() instanceof SlabBlock ? p.roofSlab() : p.roofFull();

			for (int i = -d.hw() - d.overhang(); i <= d.hw() + d.overhang(); i++) {

				for (int j = -d.hd() - d.overhang(); j <= d.hd() + d.overhang(); j++) {

					put(m, Step.block(at(c, dir, i, j, h), flat));

				}

			}

			return;

		}

		StairBlock stair = (StairBlock) p.roofStair();

		Block trim = p.frame() != null ? p.frame() : p.base();

		Block gable = d.secondWallUpper() ? p.wall2() : p.wall();

		for (int k = 0; k <= eave; k++) {

			int edge = eave - k;

			int y = h + k;

			for (int r = -reach; r <= reach; r++) {

				if (edge == 0) {

					put(m, Step.block(slopePos(c, dir, perp, d, 0, r, y), p.roofFull()));

					continue;

				}

				for (int sign = -1; sign <= 1; sign += 2) {

					// Hochseite der Stufe zeigt zum First (nach innen).

					Direction up = slopeDir(d, dir, perp, -sign);

					put(m, Step.block(slopePos(c, dir, perp, d, sign * edge, r, y), stair, b -> b.defaultBlockState().setValue(StairBlock.FACING, up)));

				}

			}

			// Giebelflaechen an den Stirnwaenden, die schraege Kante als Balken.

			for (int s = -(edge - 1); s <= edge - 1; s++) {

				for (int sign = -1; sign <= 1; sign += 2) {

					facadePositions.add(slopePos(c, dir, perp, d, s, sign * wallR, y));
					boolean slant = Math.abs(s) == edge - 1 && edge >= 2;

					if (slant) {

						put(m, Step.block(slopePos(c, dir, perp, d, s, sign * wallR, y), trim, wood(d.ridgeAlongPerp() ? dir.getAxis() : perp.getAxis())));

					} else {

						put(m, Step.block(slopePos(c, dir, perp, d, s, sign * wallR, y), gable));

					}

				}

			}

			// Unterfuetterung der Traufe (Dachueberstand): Balken zwischen Wandkrone und Dachstufe.

			if (d.overhang() > 0 && k >= 1 && k <= d.overhang()) {

				for (int r = -wallR; r <= wallR; r++) {

					for (int sign = -1; sign <= 1; sign += 2) {

						for (int yy = h; yy < y; yy++) {

							put(m, Step.block(slopePos(c, dir, perp, d, sign * wallS, r, yy), trim, wood(d.ridgeAlongPerp() ? perp.getAxis() : dir.getAxis())));

						}

					}

				}

			}

		}

		// Giebelfenster mittig unter dem First.

		if (eave >= 3) {

			for (int sign = -1; sign <= 1; sign += 2) {

				put(m, Step.block(slopePos(c, dir, perp, d, 0, sign * wallR, h + eave - 2), p.window()));

			}

		}

	}

	/** Position im Dach: {@code s} entlang der Neigung, {@code r} entlang des Firsts. */

	private static BlockPos slopePos(BlockPos c, Direction dir, Direction perp, Design d, int s, int r, int y) {

		return d.ridgeAlongPerp() ? at(c, dir, r, s, y) : at(c, dir, s, r, y);

	}

	/** Richtung "nach aussen/unten" einer Dachkante. */

	private static Direction slopeDir(Design d, Direction dir, Direction perp, int sign) {

		Direction axis = d.ridgeAlongPerp() ? dir : perp;

		return sign > 0 ? axis : axis.getOpposite();

	}

	/** Seitlicher Anbau (niedriger, Flachdach aus Halbstufen) mit Durchgang zum Haupthaus und eigenen Fenstern. */

	private static void wing(Map<BlockPos, Step> m, Design d, Palette p, BlockPos c, Direction dir, Direction perp, java.util.Set<BlockPos> facadePositions) {

		int sd = d.wing();

		if (sd == 0) {

			return;

		}

		int hw = d.hw();

		int wd = d.hd() - 1;

		int ww = WING_W;

		int h2 = d.wallH() - 1;

		Block frame = p.frame();

		Block roofBlock = p.roofSlab() instanceof SlabBlock ? p.roofSlab() : p.roofFull();

		for (int i = hw + 1; i <= hw + ww; i++) {

			for (int j = -wd; j <= wd; j++) {

				boolean ring = i == hw + ww || Math.abs(j) == wd;

				put(m, Step.block(at(c, dir, sd * i, j, -1), ring && d.useBase() ? p.base() : p.floor()));

				if (ring) {

					boolean post = frame != null && Math.abs(j) == wd && (i == hw + ww || i == hw + 1);

					for (int dy = 0; dy < h2; dy++) {

						Block b = d.useBase() && dy == 0 ? p.base() : p.wall();

						Direction.Axis axis = Direction.Axis.Y;

						if (frame != null && (post || (d.frameBeams() && dy == h2 - 1 && h2 >= 3))) {

							b = frame;

							if (!post) {

								axis = Math.abs(j) == wd ? perp.getAxis() : dir.getAxis();

							}

						}

						facadePositions.add(at(c, dir, sd * i, j, dy));
						put(m, Step.block(at(c, dir, sd * i, j, dy), b, wood(axis)));

					}

				}

			}

		}

		// Fenster: Aussenwand und beide Laengsseiten.

		put(m, Step.block(at(c, dir, sd * (hw + ww), 0, 1), p.window()));

		put(m, Step.block(at(c, dir, sd * (hw + 2), wd, 1), p.window()));

		put(m, Step.block(at(c, dir, sd * (hw + 2), -wd, 1), p.window()));

		// Durchgang zum Haupthaus.

		put(m, Step.free(at(c, dir, sd * hw, 0, 0), Blocks.AIR.defaultBlockState()));

		put(m, Step.free(at(c, dir, sd * hw, 0, 1), Blocks.AIR.defaultBlockState()));

		// Flachdach mit kleinem Ueberstand.

		for (int i = hw + 1; i <= hw + ww + 1; i++) {

			for (int j = -wd - 1; j <= wd + 1; j++) {

				put(m, Step.block(at(c, dir, sd * i, j, h2), roofBlock));

			}

		}

		// Innen: Truhe-Ersatz ist unnoetig; eine Laterne auf dem Boden und ein Teppichstreifen.

		put(m, Step.free(at(c, dir, sd * (hw + ww - 1), wd - 1, 0), Blocks.LANTERN.defaultBlockState()));

	}

	/** Zierde: Fensterlaeden, Blumentoepfe, Weg, Teppich und Tisch. Alles ohne Materialkosten ("free"), damit der Bau nicht stockt. */

	private static void decorate(Map<BlockPos, Step> m, Design d, Palette p, BlockPos c, Direction dir, Direction perp, int doorI) {

		int hw = d.hw();

		int hd = d.hd();

		int h = d.wallH();

		RandomSource r = RandomSource.create(c.asLong() * 31 + hw * 7 + hd);

		// Fensterlaeden links und rechts jedes Fensters (offene Falltueren), nur an Fassade und Seiten.

		Block shutter = shutterBlock(p);

		boolean shuttersOn = shutter != null && r.nextInt(4) != 0;

		if (shuttersOn) {

			for (int dy = 1; dy < h - 1; dy += 2) {

				for (int i = -hw + 1; i <= hw - 1; i++) {

					if (!windowCol(d, i) || (Math.abs(i - doorI) <= 1 && dy < 2)) {

						continue;

					}

					for (int side = -1; side <= 1; side += 2) {

						int ni = i + side;

						if (Math.abs(ni) >= hw || windowCol(d, ni) || (Math.abs(ni - doorI) <= 1 && dy < 2)) {

							continue;

						}

						shutter(m, shutter, at(c, dir, ni, hd + 1, dy), dir);

						shutter(m, shutter, at(c, dir, ni, -hd - 1, dy), dir.getOpposite());

					}

				}

			}

		}

		// Weg und Blumentoepfe vor der Tuer.

		for (int j = hd + 1; j <= hd + 3; j++) {

			put(m, Step.free(at(c, dir, doorI, j, -1), Blocks.DIRT_PATH.defaultBlockState()));

		}

		Block[] pots = {Blocks.POTTED_RED_TULIP, Blocks.POTTED_AZURE_BLUET, Blocks.POTTED_CORNFLOWER, Blocks.POTTED_POPPY,

			Blocks.POTTED_OXEYE_DAISY, Blocks.POTTED_ALLIUM, Blocks.POTTED_DANDELION};

		for (int side = -1; side <= 1; side += 2) {

			int pi = doorI + side;

			if (Math.abs(pi) < hw) {

				put(m, Step.free(at(c, dir, pi, hd + 1, 0), pots[r.nextInt(pots.length)].defaultBlockState()));

			}

		}

		// Hof: Wegelaterne am Ende des Weges und je nach Entwurf Blumenbeet, Fass + Heu, Sitzbank oder Holzstapel.

		if (d.porch() && p.fence() != null) {

			BlockPos lamp = at(c, dir, doorI + (r.nextBoolean() ? 1 : -1), hd + 4, 0);

			put(m, Step.block(lamp, p.fence()));

			put(m, Step.block(lamp.above(), p.fence()));

			put(m, Step.free(lamp.above(2), Blocks.LANTERN.defaultBlockState()));

		}

		int free = d.wing() != 0 ? -d.wing() : (doorI >= 0 ? -1 : 1);

		Block[] flowers = {Blocks.POPPY, Blocks.CORNFLOWER, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.ALLIUM};

		int deco = d.deco() & 3;

		if (deco == 0 || r.nextInt(3) == 0) {

			for (int j = hd - 1; j <= hd + 1; j++) {

				for (int k = 1; k <= 2; k++) {

					if (r.nextInt(4) != 0) {

						put(m, Step.free(at(c, dir, free * (hw + k), j, 0), flowers[r.nextInt(flowers.length)].defaultBlockState()));

					}

				}

			}

		}

		switch (deco) {

			case 1 -> {

				put(m, Step.free(at(c, dir, -free * (hw + 1), hd - 1, 0), Blocks.BARREL.defaultBlockState()));

				put(m, Step.free(at(c, dir, -free * (hw + 1), hd, 0), Blocks.HAY_BLOCK.defaultBlockState()));

			}

			case 2 -> {

				// Sitzbank an der Fassade (Lehne zur Wand) mit Topfblume.

				if (p.roofStair() instanceof StairBlock bench) {

					Direction back = dir.getOpposite();

					for (int k = 2; k <= 3; k++) {

						int bi = doorI + free * -k;

						if (Math.abs(bi) <= hw) {

							put(m, Step.block(at(c, dir, bi, hd + 1, 0), bench, b -> b.defaultBlockState().setValue(StairBlock.FACING, back)));

						}

					}

				}

			}

			case 3 -> {

				// Holzstapel an der Seitenwand.

				Block log = Blocks.SPRUCE_LOG;

				for (int j = -hd + 1; j <= -hd + 3; j++) {

					for (int dy = 0; dy < 2; dy++) {

						put(m, Step.free(at(c, dir, -free * (hw + 1), j, dy), log.defaultBlockState()

							.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, dir.getAxis())));

					}

				}

			}

			default -> {

			}

		}

		if (d.kind() == Kind.STORAGE || d.kind() == Kind.GRANARY || d.kind() == Kind.TOOLSHED || d.kind() == Kind.KITCHEN || d.kind() == Kind.STATION) {

			return;

		}

		// Teppich quer durch den Raum und ein Tisch gegenueber der Tuer.

		String[] carpets = {"red", "brown", "green", "blue", "gray"};

		Block carpet = net.minecraft.core.registries.BuiltInRegistries.BLOCK

			.getOptional(net.minecraft.resources.Identifier.parse("minecraft:" + carpets[r.nextInt(carpets.length)] + "_carpet")).orElse(Blocks.AIR);

		for (int i = -hw + 2; i <= hw - 2 && carpet != Blocks.AIR; i++) {

			put(m, Step.free(at(c, dir, i, 0, 0), carpet.defaultBlockState()));

		}

		if (d.kind() == Kind.HOUSE && hd >= 2) {

			int ti = doorI >= 0 ? -hw + 1 : hw - 1;

			Block fence = p.fence() != null ? p.fence() : Blocks.OAK_FENCE;

			put(m, Step.free(at(c, dir, ti, hd - 1, 0), fence.defaultBlockState()));

			put(m, Step.free(at(c, dir, ti, hd - 1, 1), Blocks.SPRUCE_PRESSURE_PLATE.defaultBlockState()));

		}

	}

	/** Small, palette-framed trade signs for newly versioned civic work buildings. */
	private static void roleMarkers(Map<BlockPos, Step> m, Design d, Palette p, BlockPos c, Direction dir, int doorI) {
		if ((d.deco() & ROLE_MARKER_DECO) == 0 || !roleMarkerKind(d.kind())) return;
		Block trim = p.frame() != null ? p.frame() : p.wall();
		int hd = d.hd();
		if (d.kind() == Kind.TOOLSHED || d.kind() == Kind.KITCHEN || d.kind() == Kind.STATION) {
			// Tie the work cue to the entrance with a paid palette lintel; this replaces the wall block above the door.
			int lintelY = Math.min(2, d.wallH() - 1);
			put(m, markerTrim(at(c, dir, doorI, hd, lintelY), trim, dir.getClockWise().getAxis()));
		}

		switch (d.kind()) {
			case STORAGE -> {
				// A pair of vanilla barrels makes the loading facade read as storage from the road.
				// The 26.2 vanilla recipe uses six planks and two wooden slabs per barrel; both
				// ingredient families are obtainable through the existing wood supply rules.
				// Keep the paid mark above the opening and point each barrel toward the road.
				int y = Math.min(2, d.wallH() - 1);
				for (int i : new int[]{doorI - 1, doorI}) {
					put(m, Step.block(at(c, dir, i, hd, y), Blocks.BARREL,
						b -> b.defaultBlockState().setValue(net.minecraft.world.level.block.DirectionalBlock.FACING, dir)));
				}
				// Reinforced palette posts frame the paired loading doors and their barrel crest.
				for (int i : new int[]{doorI - 2, doorI + 1}) {
					for (int postY = 0; postY <= 1; postY++) {
						put(m, markerTrim(at(c, dir, i, hd, postY), trim, dir.getClockWise().getAxis()));
					}
				}
			}
			case SCHOOL -> {
				// Keep the school sign compact: a stone lintel between palette-timber brackets.
				// Bookshelves stay in the classroom instead of turning the street facade into a book tower.
				int y = Math.min(2, d.wallH() - 1);
				put(m, markerTrim(at(c, dir, doorI - 1, hd, y), trim, dir.getClockWise().getAxis()));
				put(m, Step.block(at(c, dir, doorI, hd, y), schoolMarkerStone()));
				put(m, markerTrim(at(c, dir, doorI + 1, hd, y), trim, dir.getClockWise().getAxis()));
			}
			case GRANARY -> {
				// A hay-bale crest above the entrance, held by the same trim as the village's half-timbering.
				int y = Math.min(2, d.wallH() - 1);
				put(m, markerTrim(at(c, dir, doorI - 1, hd, y), trim, dir.getClockWise().getAxis()));
				put(m, Step.block(at(c, dir, doorI, hd, y), Blocks.HAY_BLOCK));
				put(m, markerTrim(at(c, dir, doorI + 1, hd, y), trim, dir.getClockWise().getAxis()));
			}
			case TOOLSHED -> {
				// Anvil and workbench flank the doorway on clear, non-window wall columns.
				List<Integer> columns = markerColumns(d, doorI, 2);
				if (columns.size() < 2) return;
				int y = Math.min(1, d.wallH() - 1);
				for (int i : columns) put(m, markerTrim(at(c, dir, i, hd, y + 1), trim, dir.getClockWise().getAxis()));
				put(m, Step.block(at(c, dir, columns.get(0), hd, y), Blocks.ANVIL,
					b -> b.defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, dir)));
				put(m, Step.block(at(c, dir, columns.get(1), hd, y), Blocks.CRAFTING_TABLE));
			}
			case KITCHEN -> {
				// The versioned kitchen already has a palette-base chimney; place the smoker on solid wall beside the clear doorway.
				List<Integer> columns = markerColumns(d, doorI, 1);
				if (columns.isEmpty()) return;
				int i = columns.getFirst();
				int y = Math.min(1, d.wallH() - 1);
				put(m, markerTrim(at(c, dir, i, hd, y + 1), trim, dir.getClockWise().getAxis()));
				put(m, Step.block(at(c, dir, i, hd, y), Blocks.SMOKER,
					b -> b.defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, dir)));
			}
			case STATION -> {
				// A regular/powered rail pair reads as a freight mark. It follows the facade axis and avoids windows and the entrance.
				List<Integer> columns = markerColumns(d, doorI, 2);
				if (columns.size() < 2) return;
				int y = Math.min(1, d.wallH() - 1);
				RailShape facadeShape = dir.getClockWise().getAxis() == Direction.Axis.X
					? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
				for (int i : columns) put(m, markerTrim(at(c, dir, i, hd, y + 1), trim, dir.getClockWise().getAxis()));
				put(m, Step.block(at(c, dir, columns.get(0), hd, y), Blocks.RAIL,
					b -> b.defaultBlockState().setValue(RailBlock.SHAPE, facadeShape)));
				put(m, Step.block(at(c, dir, columns.get(1), hd, y), Blocks.POWERED_RAIL,
					b -> b.defaultBlockState().setValue(PoweredRailBlock.SHAPE, facadeShape)));
			}
			default -> { }
		}
	}

	private static Step markerTrim(BlockPos pos, Block trim, Direction.Axis axis) {
		if (trim instanceof net.minecraft.world.level.block.RotatedPillarBlock) {
			return Step.block(pos, trim, block -> block.defaultBlockState()
				.setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, axis));
		}
		return Step.block(pos, trim);
	}

	/** Solid facade columns that stay clear of both the doorway and windows, ordered farthest-first. */
	private static List<Integer> markerColumns(Design design, int doorI, int count) {
		List<Integer> columns = new ArrayList<>();
		for (int i = -design.hw() + 1; i <= design.hw() - 1; i++) {
			if (Math.abs(i - doorI) > 1 && !windowCol(design, i)) columns.add(i);
		}
		columns.sort(Comparator.comparingInt((Integer i) -> Math.abs(i - doorI)).reversed().thenComparingInt(Integer::intValue));
		return columns.size() <= count ? columns : List.copyOf(columns.subList(0, count));
	}

	private static void shutter(Map<BlockPos, Step> m, Block trapdoor, BlockPos pos, Direction out) {

		put(m, Step.free(pos, trapdoor.defaultBlockState().setValue(TrapDoorBlock.FACING, out).setValue(TrapDoorBlock.OPEN, true)

			.setValue(TrapDoorBlock.HALF, Half.BOTTOM)));

	}

	/** Falltuer aus dem Holz der Tuer ("..._door" -> "..._trapdoor"), sonst Fichte. */

	private static Block shutterBlock(Palette p) {

		String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.door()).getPath();

		if (name.endsWith("_door")) {

			Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK

				.getOptional(net.minecraft.resources.Identifier.parse(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.door()).getNamespace()

					+ ":" + name.substring(0, name.length() - 5) + "_trapdoor")).orElse(null);

			if (b instanceof TrapDoorBlock) {

				return b;

			}

		}

		return Blocks.SPRUCE_TRAPDOOR;

	}

}

