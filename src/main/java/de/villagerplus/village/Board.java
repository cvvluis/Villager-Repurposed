package de.villagerplus.village;

import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.block.DorftafelNameBlockEntity;
import de.villagerplus.block.ModBlocks;
import de.villagerplus.build.Palette;
import de.villagerplus.world.LoadedColumns;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * Dorftafel: Ein Schild in der Dorfmitte. Rechtsklick zeigt die Uebersicht (Karte und Diagramme); die Reiter
 * "Uebersicht", "Details" (alle Zahlen) und "Expeditionen" lassen sich anklicken oder per Befehl
 * {@code /villagerplus tafel <reiter>} aufrufen. Schleichen + Rechtsklick zeigt direkt die Expeditionen.
 */
public final class Board {
	public static final String[][] TABS = {{"uebersicht", "message.villagerplus.board_tab_overview"}, {"details", "message.villagerplus.board_tab_details"}, {"expeditionen", "message.villagerplus.board_tab_expeditions"}};
	private static final String PORTABLE_BOARD = "vp_portable_board";
	private record BoardSource(boolean available, VillageRef village) { }
	private static final BoardSource UNAVAILABLE = new BoardSource(false, null);
	private static final BoardSource PLAYER_BOARD = new BoardSource(true, null);

	private Board() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register(Board::onUseBoard);
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
			if (!(world instanceof ServerLevel level) || !(state.getBlock() instanceof DorftafelBlock)) return true;
			if (!DorftafelBlock.footprintLoaded(level, pos, state)) return false;
			BoardSource source = availableToMove(level, player, pos, state, entity);
			if (source.village() != null && !level.getGameRules().get(GameRules.BLOCK_DROPS)) {
				VillageMessages.overlay(player, Component.translatable("message.villagerplus.board_break_drops_disabled"));
				return false;
			}
			return source.available();
		});
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
			if (world instanceof ServerLevel level && state.getBlock() instanceof DorftafelBlock) {
				afterPlayerBreak(level, player, pos, state, entity);
			}
		});
	}

	private static void afterPlayerBreak(ServerLevel level, Player player, BlockPos pos, BlockState state, BlockEntity entity) {
		BoardSource source = source(level, pos, state, entity);
		// Another break listener can remove a bell between the before and after callbacks.
		if (!source.available() || (source.village() != null && !level.getGameRules().get(GameRules.BLOCK_DROPS))) {
			BlockState current = LoadedColumns.state(level, pos);
			if (current != null && current.isAir() && level.setBlock(pos, state, Block.UPDATE_ALL)
				&& entity instanceof DorftafelNameBlockEntity && level.getBlockEntity(pos) instanceof DorftafelNameBlockEntity restored) {
				restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(),
					entity.saveWithoutMetadata(level.registryAccess())));
				restored.setChanged();
			}
			return;
		}
		BlockPos anchor = DorftafelBlock.parts(state, pos)[0];
		boolean hadAnchor = pos.equals(anchor) || DorftafelBlock.isPart(level, anchor, state, 0, DoubleBlockHalf.LOWER);
		VillageRef ref = source.village();
		ItemStack drop = new ItemStack(state.getBlock());
		if (ref != null) {
			UUID token = UUID.randomUUID();
			ref.data().extra.boardInTransit = Optional.of(token);
			ref.data().extra.board = Optional.empty();
			ref.dirty();
			CompoundTag carried = new CompoundTag();
			carried.putString("dimension", level.dimension().identifier().toString());
			carried.putString("token", token.toString());
			CompoundTag data = new CompoundTag();
			data.put(PORTABLE_BOARD, carried);
			drop.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		}
		DorftafelBlock.removeParts(level, pos, state, pos);
		if (ref != null || (!player.isCreative() && hadAnchor)) Block.popResource(level, pos, drop);
	}

	private static DorftafelNameBlockEntity nameEntity(ServerLevel level, BlockPos pos, BlockState state, BlockEntity oldEntity) {
		BlockPos middle = DorftafelBlock.parts(state, pos)[1].above();
		if (pos.equals(middle) && oldEntity instanceof DorftafelNameBlockEntity name) return name;
		return LoadedColumns.ready(level, middle) && level.getBlockEntity(middle) instanceof DorftafelNameBlockEntity name ? name : null;
	}

	static void rememberOfficial(ServerLevel level, VillageRef ref, BlockPos pos, BlockState state) {
		DorftafelNameBlockEntity name = nameEntity(level, pos, state, null);
		if (name != null) name.rememberVillageOwner(GlobalPos.of(level.dimension(), ref.bell()));
	}

	public static void retrofitOwner(ServerLevel level, BlockPos pos, BlockState state, DorftafelNameBlockEntity entity) {
		VillageRef ref = Villages.officialBoardAt(level, pos, state);
		if (ref != null) entity.rememberVillageOwner(GlobalPos.of(level.dimension(), ref.bell()));
	}

	private static BoardSource availableToMove(ServerLevel level, Player player, BlockPos pos, BlockState state, BlockEntity entity) {
		BoardSource source = source(level, pos, state, entity);
		if (!source.available()) VillageMessages.overlay(player, Component.translatable("message.villagerplus.board_break_bell_unavailable"));
		return source;
	}

	private static BoardSource source(ServerLevel level, BlockPos pos, BlockState state, BlockEntity entity) {
		DorftafelNameBlockEntity name = nameEntity(level, pos, state, entity);
		if (name != null && name.playerBoard()) return PLAYER_BOARD;
		GlobalPos owner = name == null ? null : name.villageOwner();
		BlockPos[] parts = DorftafelBlock.parts(state, pos);
		if (owner != null && (!owner.dimension().equals(level.dimension()) || !LoadedColumns.ready(level, owner.pos()))) return UNAVAILABLE;
		VillageRef village = owner == null ? null : Villages.officialBoardForBell(level, owner.pos(), parts);
		if (village == null) village = Villages.officialBoardAt(level, pos, state);
		if (village == null) {
			// Old saves did not store board ownership locally. Only classify such a board once the
			// nearby legacy bell footprint can be inspected without requesting any chunks.
			if (!indexLoadedLegacyArea(level, parts[0])) return UNAVAILABLE;
			village = Villages.officialBoardAt(level, pos, state);
		}
		if (village == null) {
			if (name != null) name.rememberPlayerBoard();
			return PLAYER_BOARD;
		}
		if (name != null) name.rememberVillageOwner(GlobalPos.of(level.dimension(), village.bell()));
		return new BoardSource(true, village);
	}

	private static boolean indexLoadedLegacyArea(ServerLevel level, BlockPos origin) {
		int radius = Villages.LEGACY_BOARD_OWNER_RADIUS;
		int minX = SectionPos.blockToSectionCoord(origin.getX() - radius), maxX = SectionPos.blockToSectionCoord(origin.getX() + radius);
		int minZ = SectionPos.blockToSectionCoord(origin.getZ() - radius), maxZ = SectionPos.blockToSectionCoord(origin.getZ() + radius);
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				var chunk = level.getChunkSource().getChunkNow(x, z);
				if (chunk == null) return false;
				Villages.noteChunk(chunk);
			}
		}
		return true;
	}

	private static CompoundTag carriedBoard(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? new CompoundTag() : data.copyTag().getCompoundOrEmpty(PORTABLE_BOARD);
	}

	private static VillageRef destinationVillage(ServerLevel level, BlockPos anchor, BlockState state, CompoundTag carried) {
		if (!carried.getStringOr("dimension", "").equals(level.dimension().identifier().toString())) return null;
		VillageRef ref = Villages.villageNear(level, anchor, 96);
		String carriedToken = carried.getStringOr("token", "");
		if (ref == null || ref.data().extra.boardInTransit.filter(token -> token.toString().equals(carriedToken)).isEmpty()) return null;
		// The existing board menu uses the nearest loaded village. Every board part must resolve to its original village.
		for (BlockPos lower : DorftafelBlock.parts(state, anchor)) {
			if (!belongsToVillage(level, ref.bell(), lower) || !belongsToVillage(level, ref.bell(), lower.above())) return null;
		}
		return ref;
	}

	private static boolean belongsToVillage(ServerLevel level, BlockPos bell, BlockPos boardPart) {
		VillageRef nearest = Villages.villageNear(level, boardPart, 96);
		return nearest != null && nearest.bell().equals(bell);
	}

	public static boolean canPlace(ServerLevel level, BlockPos anchor, BlockState state, ItemStack stack, Player player) {
		CompoundTag carried = carriedBoard(stack);
		if (carried.isEmpty() || destinationVillage(level, anchor, state, carried) != null) return true;
		if (player instanceof ServerPlayer serverPlayer) {
			VillageMessages.overlay(serverPlayer, Component.translatable("message.villagerplus.board_place_origin"));
		}
		return false;
	}

	public static boolean playerPlaced(ServerLevel level, BlockPos anchor, BlockState state, ItemStack stack) {
		if (!DorftafelBlock.complete(level, anchor, state)) return false;
		DorftafelNameBlockEntity name = nameEntity(level, anchor, state, null);
		if (name == null) return false;
		CompoundTag carried = carriedBoard(stack);
		if (carried.isEmpty()) {
			name.rememberPlayerBoard();
			return true;
		}
		VillageRef ref = destinationVillage(level, anchor, state, carried);
		if (ref == null) return false;
		recordManualPosition(level, ref, anchor, state);
		return true;
	}

	private static void recordManualPosition(ServerLevel level, VillageRef ref, BlockPos anchor, BlockState state) {
		rememberOfficial(level, ref, anchor, state);
		ref.data().extra.board = Optional.of(anchor.immutable());
		ref.data().extra.boardPinned = true;
		ref.data().extra.boardInTransit = Optional.empty();
		ref.dirty();
	}

	private static InteractionResult onUseBoard(Player player, Level world, InteractionHand hand,
		BlockHitResult hit) {
		if (world.isClientSide() || !(world instanceof ServerLevel level) || !player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}
		if (!(level.getBlockState(hit.getBlockPos()).getBlock() instanceof DorftafelBlock)) {
			return InteractionResult.PASS;
		}
		VillageRef ref = Villages.villageNear(level, hit.getBlockPos(), 96);
		if (ref == null) return InteractionResult.PASS;
		return relocateOfficial(level, ref, player, hit.getBlockPos()) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
	}

	/** Verlegt die offizielle Tafel an die Spielerposition (Schleich-Rechtsklick). */
	public static boolean relocateOfficial(ServerLevel level, VillageRef ref, Player player, BlockPos from) {
		if (ref.data().extra.boardInTransit.isPresent()) return false;
		BlockState state = LoadedColumns.state(level, from);
		if (state == null || !(state.getBlock() instanceof DorftafelBlock) || !DorftafelBlock.footprintLoaded(level, from, state)) return false;
		BoardSource source = availableToMove(level, player, from, state, null);
		if (!source.available()) return false;
		VillageRef owner = source.village();
		if ((owner != null && !owner.bell().equals(ref.bell()))
			|| (owner == null && ref.data().extra.board.isPresent())) return false;
		var stand = de.villagerplus.job.JobUtil.safeSpot(level, player.position());
		if (stand == null) {
			VillageMessages.overlay((net.minecraft.server.level.ServerPlayer) player,
				Component.translatable("message.villagerplus.board_relocate_unsafe"));
			return false;
		}
		BlockPos target = BlockPos.containing(stand);
		if (!Plaza.inside(level, ref, from, 64) && Market.of(ref).plaza().isEmpty()) {
			VillageMessages.overlay((net.minecraft.server.level.ServerPlayer) player,
				Component.translatable("message.villagerplus.board_relocate_no_plaza"));
			return false;
		}
		Direction facing = Sites.facingTowards(target, Market.of(ref).plaza().orElse(ref.bell()));
		BlockState placed = state.getBlock().defaultBlockState().setValue(DorftafelBlock.FACING, facing);
		for (BlockPos cell : DorftafelBlock.parts(placed, target)) {
			if (!boardColumnClear(level, cell) || !belongsToVillage(level, ref.bell(), cell) || !belongsToVillage(level, ref.bell(), cell.above())) {
				VillageMessages.overlay((ServerPlayer) player, Component.translatable("message.villagerplus.board_relocate_unsafe"));
				return false;
			}
		}
		DorftafelBlock.placeAll(level, target, placed);
		if (!DorftafelBlock.complete(level, target, placed)) return false;
		DorftafelBlock.removeParts(level, from, state, null);
		recordManualPosition(level, ref, target, placed);
		VillageMessages.overlay((net.minecraft.server.level.ServerPlayer) player,
			Component.translatable("message.villagerplus.board_relocated"));
		return true;
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
		MutableComponent h = Component.literal("══ ").append(Component.translatable("message.villagerplus.board_title")).append(" ══  ").withStyle(ChatFormatting.GOLD);
		for (String[] tab : TABS) {
			boolean on = tab[0].equals(active);
			Style style = Style.EMPTY.withColor(on ? ChatFormatting.YELLOW : ChatFormatting.AQUA).withUnderlined(!on)
				.withClickEvent(new ClickEvent.RunCommand("villagerplus tafel " + tab[0]));
			h.append(Component.literal("[").append(Component.translatable(tab[1])).append("] ").withStyle(style));
		}
		return h;
	}

	/** Stellt sicher, dass neben der Dorfkiste eine Dorftafel (3 Bloecke breit, 2 hoch) steht; alte Tafeln werden ersetzt. */
	public static void ensure(ServerLevel level, VillageRef ref) {
		if (ref.data().extra.boardInTransit.isPresent()) return;
		Optional<BlockPos> board = ref.data().extra.board;
		BlockState savedState = LoadedColumns.state(level, board.orElse(null));
		if (savedState != null && savedState.getBlock() instanceof DorftafelBlock) {
			rememberOfficial(level, ref, board.orElseThrow(), savedState);
		}
		if (board.isPresent() && (savedState == null
			|| (ref.data().extra.boardPinned && savedState.getBlock() instanceof DorftafelBlock))) {
			return;
		}
		Optional<BlockPos> plaza = Market.of(ref).plaza();
		if (plaza.isPresent()) {
			Palette palette = Palette.detect(level, ref.bell(), null, plaza.get().asLong(), plaza.get());
			Plaza.placeBoardAndFlagpole(level, ref, plaza.get(), palette, false);
			return;
		}
		BlockPos chest = ref.chestPos(level);
		if (chest == null) {
			return;
		}
		if (board.isPresent()) {
			BlockState cur = savedState;
			if (cur.getBlock() instanceof DorftafelBlock) {
				if (!DorftafelBlock.footprintLoaded(level, board.get(), cur)) return;
				BlockPos[] parts = DorftafelBlock.parts(cur, board.get());
				if (DorftafelBlock.complete(level, board.get(), cur)) {
					if (DorftafelBlock.widthOf(cur) == 4 || !upgradeToLarge(level, ref, cur, parts)) {
						return;
					}
					return;
				}
				// Aeltere, kleinere Tafel: abbauen und neu (in voller Groesse) aufstellen.
				DorftafelBlock.removeParts(level, board.get(), cur, null);
			} else if (cur.getBlock() instanceof net.minecraft.world.level.block.StandingSignBlock) {
				level.setBlock(board.get(), Blocks.AIR.defaultBlockState(), 3);
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
			BlockState placed = ModBlocks.DORFTAFEL_GROSS.defaultBlockState().setValue(DorftafelBlock.FACING, toChest);
			DorftafelBlock.placeAll(level, p.immutable(), placed);
			rememberOfficial(level, ref, p, placed);
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
			BlockState placed = ModBlocks.DORFTAFEL_GROSS.defaultBlockState().setValue(DorftafelBlock.FACING, facing);
			DorftafelBlock.placeAll(level, fallback, placed);
			rememberOfficial(level, ref, fallback, placed);
			ref.data().extra.board = Optional.of(fallback);
			ref.dirty();
		}
	}

	static BlockPos findFallbackSite(ServerLevel level, BlockPos chest) {
		for (int radius = 3; radius <= 16; radius++) {
			for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				BlockPos column = chest.offset(dx, 0, dz);
				if (!de.villagerplus.world.LoadedColumns.ready(level, column)) continue;
				int y = de.villagerplus.world.LoadedColumns.heightOrMinY(level, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
				if (Math.abs(y - chest.getY()) > 8) continue;
				BlockPos p = new BlockPos(column.getX(), y, column.getZ());
				Direction right = Sites.facingTowards(p, chest).getCounterClockWise();
				boolean safe = true;
				for (int i = 0; i < 4 && safe; i++) {
					BlockPos cell = p.relative(right, i);
					if (!de.villagerplus.world.LoadedColumns.ready(level, cell)) { safe = false; break; }
					safe = boardColumnClear(level, cell);
				}
				if (safe) return p;
			}
		}
		return null;
	}

	static boolean boardColumnClear(ServerLevel level, BlockPos cell) {
		BlockPos below = cell.below();
		if (level.isOutsideBuildHeight(cell.above()) || !level.getWorldBorder().isWithinBounds(cell) || !LoadedColumns.ready(level, cell)) return false;
		return level.getBlockState(cell).isAir() && level.getBlockState(cell.above()).isAir()
			&& level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
	}

	/** An old three-block board becomes the large one in place, if its fourth column is free; otherwise it stays as it is. */
	private static boolean upgradeToLarge(ServerLevel level, VillageRef ref, BlockState old, BlockPos[] lower) {
		Direction r = old.getValue(DorftafelBlock.FACING).getCounterClockWise();
		BlockPos extra = lower[lower.length - 1].relative(r);
		if (!LoadedColumns.ready(level, extra)) return false;
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
		BlockState placed = ModBlocks.DORFTAFEL_GROSS.defaultBlockState().setValue(DorftafelBlock.FACING, old.getValue(DorftafelBlock.FACING));
		DorftafelBlock.placeAll(level, lower[0], placed);
		rememberOfficial(level, ref, lower[0], placed);
		ref.dirty();
		return true;
	}
}
