package de.villagerplus.village;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Direct geometry checks for the plaza's central square medallion and four clear road arms. */
public final class PlazaGeometryChecks {
	private PlazaGeometryChecks() { }
	private static int checks;

	public static int run() {
		checks = 0;
		BlockPos center = new BlockPos(120, 64, -80);
		int borderCells = 0;
		boolean squareSymmetry = true;
		for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
			boolean border = Plaza.focalSquareBorderOffset(dx, dz);
			if (border) {
				borderCells++;
				require(Plaza.basePavingOffset(dx, dz)
					&& Plaza.surfaceUpgradeOffsetSafe(dx, dz)
					&& Plaza.inside(center, center.offset(dx, 0, dz), 0),
					"all square medallion blocks use palette paving inside the saved plaza surface");
			}
			squareSymmetry &= border == Plaza.focalSquareBorderOffset(-dz, dx);
		}
		require(borderCells == 48 && squareSymmetry,
			"the 13x13 central medallion has a complete, rotationally symmetric square border");

		int outerFrameCells = 0;
		boolean outerFrameSymmetry = true;
		for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) {
			boolean border = Plaza.outerSquareBorderOffset(dx, dz);
			if (!border) continue;
			outerFrameCells++;
			require(Plaza.basePavingOffset(dx, dz)
				&& Plaza.surfaceUpgradeOffsetSafe(dx, dz)
				&& Plaza.inside(center, center.offset(dx, 0, dz), 0),
				"the outer square frame is paved inside the expanded plaza surface");
			outerFrameSymmetry &= border == Plaza.outerSquareBorderOffset(-dz, dx);
		}
		require(outerFrameCells == 80 && outerFrameSymmetry,
			"the radius-10 square frame forms a complete, rotationally symmetric outer ring");

		int innerMedallionCells = 0, outerMedallionCells = 0;
		boolean medallionSymmetry = true, approachesStayOpen = true;
		for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
			boolean inner = Plaza.innerMedallionRingOffset(dx, dz);
			boolean outer = Plaza.outerMedallionRingOffset(dx, dz);
			if (inner || outer) {
				require(inner != outer && Plaza.plazaInlayOffset(dx, dz)
					&& Plaza.basePavingOffset(dx, dz) && Plaza.surfaceUpgradeOffsetSafe(dx, dz)
					&& Plaza.inside(center, center.offset(dx, 0, dz), 0),
					"both octagonal fountain-court bands use paid palette paving inside the plaza footprint");
				approachesStayOpen &= Math.abs(dx) > 2 && Math.abs(dz) > 2;
			}
			innerMedallionCells += inner ? 1 : 0;
			outerMedallionCells += outer ? 1 : 0;
			medallionSymmetry &= inner == Plaza.innerMedallionRingOffset(-dz, dx)
				&& outer == Plaza.outerMedallionRingOffset(-dz, dx);
		}
		require(innerMedallionCells == 12 && outerMedallionCells == 20 && medallionSymmetry,
			"two clipped square bands form a balanced 11x11 octagonal medallion around the fountain");
		require(approachesStayOpen,
			"the larger central motif stays outside the five-wide cardinal walking approaches");

		int armCells = 0;
		boolean armsSymmetric = true;
		for (int dx = -18; dx <= 18; dx++) for (int dz = -18; dz <= 18; dz++) {
			boolean arm = Plaza.roadArmOffset(dx, dz);
			if (!arm) continue;
			armCells++;
			require(!Plaza.decorationOffsetSafe(dx, dz)
				&& Plaza.inside(center, center.offset(dx, 0, dz), 5),
				"road-arm cells stay free of plaza furniture and inside the saved site reserve");
			armsSymmetric &= arm == Plaza.roadArmOffset(-dz, dx);
		}
		require(armCells == 100 && armsSymmetric,
			"four five-wide cardinal arms each have a bounded five-block outer run");

		boolean connectedApproaches = true;
		for (int n = 0; n <= 18; n++) for (int side = -2; side <= 2; side++) {
			boolean paved = n <= 13
				? Plaza.basePavingOffset(n, side)
				: Plaza.roadArmOffset(n, side);
			connectedApproaches &= paved;
		}
		require(connectedApproaches,
			"each five-wide approach remains continuous from the central pavilion to the outer road arm");
		require(!Plaza.focalSquareBorderOffset(7, 0)
			&& !Plaza.outerSquareBorderOffset(11, 0)
			&& !Plaza.roadArmOffset(13, 0) && !Plaza.roadArmOffset(19, 0)
			&& !Plaza.roadArmOffset(14, 3),
			"both square frames and the road arms stop at their intended bounded edges");

		Set<String> details = new HashSet<>();
		int benches = 0, planters = 0, flowers = 0, lamps = 0;
		int outerBenches = 0, outerPlanters = 0, outerFlowers = 0, outerLamps = 0;
		boolean detailSymmetry = true;
		for (int dx = -13; dx <= 13; dx++) for (int dz = -13; dz <= 13; dz++) {
			boolean bench = Plaza.civicBenchOffset(dx, dz) || Plaza.outerCivicBenchOffset(dx, dz);
			boolean planter = Plaza.civicPlanterOffset(dx, dz) || Plaza.outerCivicPlanterOffset(dx, dz);
			boolean flower = Plaza.civicFlowerOffset(dx, dz) || Plaza.outerCivicFlowerOffset(dx, dz);
			boolean lamp = Plaza.civicLampOffset(dx, dz) || Plaza.outerCivicLampOffset(dx, dz);
			int purposeCount = (bench ? 1 : 0) + (planter ? 1 : 0) + (flower ? 1 : 0) + (lamp ? 1 : 0);
			if (purposeCount == 0) continue;
			require(purposeCount == 1 && details.add(dx + ":" + dz),
				"the inner and outer garden circuits do not overlap");
			require(Plaza.inside(center, center.offset(dx, 0, dz), 0) && !Plaza.roadArmOffset(dx, dz),
				"all seats, plants, flowers, and lamps stay inside the plaza and outside its road arms");
			require(Plaza.civicDetailOffsetSafe(dx, dz, lamp),
				"every civic detail clears the pavilion, board, and chest access");
			benches += bench ? 1 : 0;
			planters += planter ? 1 : 0;
			flowers += flower ? 1 : 0;
			lamps += lamp ? 1 : 0;
			outerBenches += Plaza.outerCivicBenchOffset(dx, dz) ? 1 : 0;
			outerPlanters += Plaza.outerCivicPlanterOffset(dx, dz) ? 1 : 0;
			outerFlowers += Plaza.outerCivicFlowerOffset(dx, dz) ? 1 : 0;
			outerLamps += Plaza.outerCivicLampOffset(dx, dz) ? 1 : 0;
			detailSymmetry &= bench == (Plaza.civicBenchOffset(-dz, dx) || Plaza.outerCivicBenchOffset(-dz, dx))
				&& planter == (Plaza.civicPlanterOffset(-dz, dx) || Plaza.outerCivicPlanterOffset(-dz, dx))
				&& flower == (Plaza.civicFlowerOffset(-dz, dx) || Plaza.outerCivicFlowerOffset(-dz, dx))
				&& lamp == (Plaza.civicLampOffset(-dz, dx) || Plaza.outerCivicLampOffset(-dz, dx));
		}
		require(benches == 16 && planters == 12 && flowers == 32 && lamps == 8
			&& outerBenches == 8 && outerPlanters == 8 && outerFlowers == 16 && outerLamps == 4
			&& details.size() == Plaza.allCivicDetailCount()
			&& Plaza.civicDetailCount() == 32 && Plaza.outerCivicDetailCount() == 36,
			"two furniture/garden circuits provide sixteen seats, twelve shrubs, thirty-two flowers, and eight lights");
		require(detailSymmetry, "both planted circuits and seating rings are rotationally balanced");
		require(Plaza.civicDetailOffsetSafe(-7, -7, false)
			&& Plaza.civicDetailOffsetSafe(7, -7, false)
			&& Plaza.civicDetailOffsetSafe(-7, 7, false)
			&& Plaza.civicDetailOffsetSafe(7, 7, false)
			&& !Plaza.civicDetailOffsetSafe(0, 4, false)
			&& !Plaza.civicDetailOffsetSafe(-2, 4, false)
			&& !Plaza.civicDetailOffsetSafe(-1, -5, false)
			&& !Plaza.civicDetailOffsetSafe(9, 9, false),
			"four court planters remain clear of historic lamp cells while pavilion, chest, board, and active light access stays reserved");

		ZonePolicy.Zone touching = new ZonePolicy.Zone("touching", "housing", 133, -82, 145, -70);
		ZonePolicy.Zone beyond = new ZonePolicy.Zone("beyond", "farm", 134, -82, 145, -70);
		ZonePolicy.Zone reversed = new ZonePolicy.Zone("reversed", "pasture", 145, -70, 133, -82);
		require(Plaza.civicZoneOverlapsSquare(touching, center.getX(), center.getZ(), 13)
			&& !Plaza.civicZoneOverlapsSquare(beyond, center.getX(), center.getZ(), 13)
			&& Plaza.civicZoneOverlapsSquare(reversed, center.getX(), center.getZ(), 13),
			"plaza planning rejects protected zones that touch or cross its footprint, including reversed saved bounds");
		require(!Plaza.civicZoneOverlapsSquare(touching, center.getX(), center.getZ(), -1)
			&& !Plaza.civicZoneOverlapsSquare(null, center.getX(), center.getZ(), 13),
			"invalid zone geometry is ignored safely");
		return checks;
	}

	private static void require(boolean condition, String message) {
		checks++;
		if (!condition) throw new IllegalStateException("Plaza geometry checks: " + message);
	}
}
