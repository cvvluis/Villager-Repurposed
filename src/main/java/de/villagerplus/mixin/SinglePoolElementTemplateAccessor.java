package de.villagerplus.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the serialized template identity needed to distinguish houses from other pool pieces. */
@Mixin(SinglePoolElement.class)
public interface SinglePoolElementTemplateAccessor {
	@Accessor("template")
	Either<Identifier, StructureTemplate> villagerplus$getTemplate();
}
