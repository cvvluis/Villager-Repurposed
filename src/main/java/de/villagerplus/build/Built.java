package de.villagerplus.build;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Ein von den Dorfbewohnern gebautes Gebaeude (Entwurf aus Seed + damals benutzte Palette): Grundlage fuer die Instandhaltung. */
public record Built(String type, BlockPos origin, int dir, int variant, List<String> palette, List<BlockPos> fallbackOpenings) {
	public static final Codec<Built> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("type").forGetter(Built::type),
		BlockPos.CODEC.fieldOf("origin").forGetter(Built::origin),
		Codec.INT.fieldOf("dir").forGetter(Built::dir),
		Codec.INT.fieldOf("variant").forGetter(Built::variant),
		Codec.STRING.listOf().optionalFieldOf("palette", List.of()).forGetter(Built::palette),
		BlockPos.CODEC.listOf().optionalFieldOf("fallback_openings", List.of()).forGetter(Built::fallbackOpenings))
		.apply(i, Built::new));

	public Built(String type, BlockPos origin, int dir, int variant, List<String> palette) {
		this(type, origin, dir, variant, palette, List.of());
	}

	public Built {
		palette = List.copyOf(palette);
		fallbackOpenings = List.copyOf(fallbackOpenings);
	}
}
