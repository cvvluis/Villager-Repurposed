package de.villagerplus.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.villagerplus.village.G01VillageSpacingChecks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.sugar.Local;

import java.util.List;

/**
 * Applies spacing only while a single Jigsaw structure is being assembled. Fabric's public structure hooks can alter pools,
 * but do not expose a per-candidate Jigsaw veto that can inspect already accepted pieces; this wraps vanilla's collision gate.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
abstract class JigsawHouseSpacingMixin {
	@Shadow @Final private List<? super PoolElementStructurePiece> pieces;

	@WrapOperation(
		method = "tryPlacingChildren(Lnet/minecraft/world/level/levelgen/structure/PoolElementStructurePiece;Lorg/apache/commons/lang3/mutable/MutableObject;IZLnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"
		)
	)
	private boolean villagerplus$enforceResidentialHouseGap(
		VoxelShape currentFreeSpace,
		VoxelShape candidateShape,
		BooleanOp operation,
		Operation<Boolean> original,
		// 26.2 source local ordinals at this call: structurepoolelement1 and boundingbox3.
		@Local(ordinal = 1) StructurePoolElement candidateElement,
		@Local(ordinal = 3) BoundingBox candidateBounds
	) {
		if (G01VillageSpacingChecks.blocksResidentialCandidate(candidateElement, candidateBounds, pieces)) {
			// The vanilla condition treats true as a collision and continues trying alternatives.
			return true;
		}
		return original.call(currentFreeSpace, candidateShape, operation);
	}
}
