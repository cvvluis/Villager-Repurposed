package de.villagerplus.village;

import com.mojang.datafixers.util.Either;
import de.villagerplus.mixin.SinglePoolElementTemplateAccessor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.regex.Pattern;

/** Focused G01 production predicates and a small callable regression-check hook. */
public final class G01VillageSpacingChecks {
	public static final int MINIMUM_HORIZONTAL_CLEAR_BLOCKS = 12;

	private static final Pattern VANILLA_HOUSE = Pattern.compile(
		"^village/(plains|desert|savanna|snowy|taiga)(?:/zombie)?/houses/\\1_(?:small|medium|big)_house_[1-9][0-9]*$"
	);
	private static final Pattern CTOV_HOUSE = Pattern.compile(
		"^village/[^/]+/house/(?:small_[1-9][0-9]*|med_[1-9][0-9]*|big(?:_[1-9][0-9]*)?|house_[1-9][0-9]*|hut_[1-9][0-9]*|longhouse_[1-9][0-9]*|tipi_[1-9][0-9]*)$"
	);

	private G01VillageSpacingChecks() { }

	public static boolean isResidentialTemplate(Identifier templateId) {
		if (templateId == null) return false;
		String path = templateId.getPath();
		return switch (templateId.getNamespace()) {
			case "minecraft" -> VANILLA_HOUSE.matcher(path).matches();
			case "ctov" -> CTOV_HOUSE.matcher(path).matches();
			default -> false;
		};
	}

	public static boolean blocksResidentialCandidate(
		StructurePoolElement candidateElement,
		BoundingBox candidateBounds,
		Iterable<?> composedPieces
	) {
		if (candidateBounds == null || composedPieces == null
			|| !isResidentialTemplate(templateId(candidateElement))) return false;

		for (Object candidate : composedPieces) {
			if (!(candidate instanceof PoolElementStructurePiece placedPiece)) continue;
			if (!isResidentialTemplate(templateId(placedPiece.getElement()))) continue;
			if (withinMinimumGap(candidateBounds, placedPiece.getBoundingBox())) return true;
		}
		return false;
	}

	private static Identifier templateId(StructurePoolElement element) {
		if (!(element instanceof SinglePoolElement single)) return null;
		Either<Identifier, StructureTemplate> template =
			((SinglePoolElementTemplateAccessor) (Object) single).villagerplus$getTemplate();
		return template.left().orElse(null);
	}

	private static boolean withinMinimumGap(BoundingBox first, BoundingBox second) {
		long gapX = horizontalGap(first.minX(), first.maxX(), second.minX(), second.maxX());
		long gapZ = horizontalGap(first.minZ(), first.maxZ(), second.minZ(), second.maxZ());
		long minimum = MINIMUM_HORIZONTAL_CLEAR_BLOCKS;
		return gapX * gapX + gapZ * gapZ < minimum * minimum;
	}

	private static long horizontalGap(int firstMin, int firstMax, int secondMin, int secondMax) {
		if (firstMax < secondMin) return (long) secondMin - firstMax - 1;
		if (secondMax < firstMin) return (long) firstMin - secondMax - 1;
		return 0;
	}

	/** Pure checks for the exact identity allowlist and the bounding-box distance rule. */
	public static int run() {
		int checks = 0;
		require(isResidentialTemplate(id("minecraft", "village/plains/houses/plains_small_house_1")),
			"vanilla residential template is recognized"); checks++;
		require(isResidentialTemplate(id("minecraft", "village/plains/zombie/houses/plains_medium_house_2")),
			"vanilla zombie house variant is recognized"); checks++;
		require(isResidentialTemplate(id("ctov", "village/jungle_tree/house/house_1")),
			"CTOV residential template is recognized"); checks++;
		require(!isResidentialTemplate(id("minecraft", "village/plains/houses/plains_butcher_shop_1")),
			"vanilla job site is excluded"); checks++;
		require(!isResidentialTemplate(id("ctov", "village/plains/jobsite/butcher")),
			"CTOV job site is excluded"); checks++;
		require(!isResidentialTemplate(id("ctov", "village/plains/market/market_1")),
			"market template is excluded"); checks++;
		require(!isResidentialTemplate(id("minecraft", "village/plains/streets/straight")),
			"street template is excluded"); checks++;
		require(!isResidentialTemplate(id("villagerplus", "village/plains/house/small_1")),
			"unverified namespace is excluded"); checks++;

		BoundingBox first = new BoundingBox(0, 0, 0, 4, 6, 4);
		require(withinMinimumGap(first, new BoundingBox(16, 0, 0, 20, 6, 4)),
			"eleven clear blocks are rejected"); checks++;
		require(!withinMinimumGap(first, new BoundingBox(17, 0, 0, 21, 6, 4)),
			"twelve clear blocks are accepted"); checks++;
		require(withinMinimumGap(first, new BoundingBox(13, 80, 13, 17, 86, 17)),
			"vertical separation does not waive a horizontal house gap"); checks++;
		require(withinMinimumGap(first, new BoundingBox(13, 0, 13, 17, 6, 17)),
			"diagonal gaps inside the twelve-block radius are rejected"); checks++;
		require(!withinMinimumGap(first, new BoundingBox(14, 0, 14, 18, 6, 18)),
			"diagonal gaps outside the twelve-block radius are accepted"); checks++;
		return checks;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("G01 village spacing: " + message);
	}

	private static Identifier id(String namespace, String path) {
		return Identifier.fromNamespaceAndPath(namespace, path);
	}
}
