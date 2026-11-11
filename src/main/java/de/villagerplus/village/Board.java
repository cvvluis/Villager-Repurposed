package de.villagerplus.village;

import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.block.ModBlocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Dorftafel: Ein Schild in der Dorfmitte. Rechtsklick zeigt die Uebersicht (Karte und Diagramme); die Reiter
 * "Uebersicht", "Details" (alle Zahlen) und "Expeditionen" lassen sich anklicken oder per Befehl
 * {@code /villagerplus tafel <reiter>} aufrufen. Schleichen + Rechtsklick zeigt direkt die Expeditionen.
 */
public final class Board {
	public static final String[][] TABS = {{"uebersicht", "Übersicht"}, {"details", "Details"}, {"expeditionen", "Expeditionen"}};

	private Board() {
	}

	public static void register() {
		// Die Dorftafel ist ein eigener Block (siehe DorftafelBlock); hier gibt es nichts mehr zu registrieren.
	}

	/** Schreibt den gewaehlten Reiter der Dorftafel. */
	public static void show(Consumer<Component> out, ServerLevel level, VillageRef ref, String tab) {
		out.accept(header(tab));
		switch (tab) {
			case "details" -> {
				for (String line : VillageStats.lines(level, ref)) {
					out.accept(Component.literal(line));
				}
			}
			case "expeditionen" -> {
				for (String line : Expedition.lines(level, ref, level.getGameTime())) {
					out.accept(Component.literal(line));
				}
			}
			default -> {
				for (Component line : VillageMap.render(level, ref)) {
					out.accept(line);
				}
			}
		}
	}

	private static Component header(String active) {
		MutableComponent h = Component.literal("══ Dorftafel ══  ").withStyle(ChatFormatting.GOLD);
		for (String[] tab : TABS) {
			boolean on = tab[0].equals(active);
			Style style = Style.EMPTY.withColor(on ? ChatFormatting.YELLOW : ChatFormatting.AQUA).withUnderlined(!on)
				.withClickEvent(new ClickEvent.RunCommand("villagerplus tafel " + tab[0]));
			h.append(Component.literal("[" + tab[1] + "] ").withStyle(style));
		}
		return h;
	}

	/** Stellt sicher, dass neben der Dorfkiste eine Dorftafel (3 Bloecke breit, 2 hoch) steht; alte Tafeln werden ersetzt. */
	public static void ensure(ServerLevel level, VillageRef ref) {
		BlockPos chest = ref.chestPos(level);
		if (chest == null) {
			return;
		}
		Optional<BlockPos> board = ref.data().extra.board;
		if (board.isPresent()) {
			BlockState cur = level.getBlockState(board.get());
			if (cur.getBlock() instanceof DorftafelBlock) {
				BlockPos[] parts = DorftafelBlock.parts(cur, board.get());
				if (DorftafelBlock.complete(level, board.get(), cur)) {
					if (DorftafelBlock.widthOf(cur) == 4 || !upgradeToLarge(level, ref, cur, parts)) {
						return;
					}
					return;
				}
				// Aeltere, kleinere Tafel: abbauen und neu (in voller Groesse) aufstellen.
				for (BlockPos p : parts) {
					for (BlockPos q : new BlockPos[] {p, p.above()}) {
						if (level.getBlockState(q).getBlock() instanceof DorftafelBlock) {
							level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
						}
					}
				}
			} else if (cur.getBlock() instanceof net.minecraft.world.level.block.StandingSignBlock) {
				level.setBlock(board.get(), Blocks.AIR.defaultBlockState(), 3);
			}
			for (BlockPos p : BlockPos.betweenClosed(board.get().offset(-3, 0, -3), board.get().offset(3, 1, 3))) {
				if (level.getBlockState(p).getBlock() instanceof DorftafelBlock) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
		for (BlockPos p : BlockPos.betweenClosed(chest.offset(-5, 0, -5), chest.offset(5, 0, 5))) {
			double dist = p.distSqr(chest);
			if (dist < 6.0 || dist > 26.0) {
				continue;
			}
			Direction toChest = Sites.facingTowards(p, chest);
			Direction r = toChest.getCounterClockWise();
			boolean ok = true;
			for (int i = 0; i < 4 && ok; i++) {
				BlockPos t = p.relative(r, i);
				ok = boardColumnClear(level, t);
			}
			if (!ok) {
				continue;
			}
			DorftafelBlock.placeAll(level, p.immutable(), ModBlocks.DORFTAFEL_GROSS.defaultBlockState().setValue(DorftafelBlock.FACING, toChest));
			ref.data().extra.board = Optional.of(p.immutable());
			ref.dirty();
			return;
		}
		// A chest in a steep or crowded generated village may have no four-block
		// level strip at its own elevation. Search loaded terrain farther out without
		// clearing foliage, structures, containers, or any other existing blocks.
		BlockPos fallback = findFallbackSite(level, chest);
		if (fallback != null) {
			Direction facing = Sites.facingTowards(fallback, chest);
			DorftafelBlock.placeAll(level, fallback, ModBlocks.DORFTAFEL_GROSS.defaultBlockState().setValue(DorftafelBlock.FACING, facing));
			ref.data().extra.board = Optional.of(fallback);
			ref.dirty();
		}
	}

	static BlockPos findFallbackSite(ServerLevel level, BlockPos chest) {
		for (int radius = 3; radius <= 16; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				BlockPos column = chest.offset(dx, 0, dz);
				if (!level.hasChunkAt(column)) continue;
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					column.getX(), column.getZ());
				if (Math.abs(y - chest.getY()) > 8) continue;
				BlockPos p = new BlockPos(column.getX(), y, column.getZ());
				Direction right = Sites.facingTowards(p, chest).getCounterClockWise();
				boolean safe = true;
				for (int i = 0; i < 4 && safe; i++) {
					BlockPos cell = p.relative(right, i);
					if (!level.hasChunkAt(cell)) { safe = false; break; }
					safe = boardColumnClear(level, cell);
				}
				if (safe) return p;
			}
		}
		return null;
	}

	static boolean boardColumnClear(ServerLevel level, BlockPos cell) {
		BlockPos below = cell.below();
		return level.getBlockState(cell).isAir() && level.getBlockState(cell.above()).isAir()
			&& level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	/** An old three-block board becomes the large one in place, if its fourth column is free; otherwise it stays as it is. */
	private static boolean upgradeToLarge(ServerLevel level, VillageRef ref, BlockState old, BlockPos[] lower) {
		Direction r = old.getValue(DorftafelBlock.FACING).getCounterClockWise();
		BlockPos extra = lower[lower.length - 1].relative(r);
		BlockPos below = extra.below();
		boolean free = level.getBlockState(extra).canBeReplaced() && level.getBlockState(extra.above()).canBeReplaced()
			&& level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
		if (!free) {
			return false;
		}
		for (BlockPos p : lower) {
			for (BlockPos q : new BlockPos[] {p, p.above()}) {
				if (level.getBlockState(q).getBlock() instanceof DorftafelBlock) {
					level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}
		DorftafelBlock.placeAll(level, lower[0], ModBlocks.DORFTAFEL_GROSS.defaultBlockState()
			.setValue(DorftafelBlock.FACING, old.getValue(DorftafelBlock.FACING)));
		ref.dirty();
		return true;
	}
}
