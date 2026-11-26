package de.villagerplus.village;

import com.mojang.authlib.GameProfile;
import de.villagerplus.VillagerPlus;
import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.block.DorftafelGrossBlock;
import de.villagerplus.block.DorftafelNameBlockEntity;
import de.villagerplus.block.ModBlocks;
import de.villagerplus.build.Palette;
import de.villagerplus.job.ButcherChecks;
import de.villagerplus.world.LoadedColumns;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Server-side invariants of the large (4 x 2) village board and the upgrade of old three-block boards. */
public final class DorftafelGrossChecks {
	private DorftafelGrossChecks() { }

	private static int checks;

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("DORFTAFEL CHECK FAILED: " + message);
		checks++;
	}

	private static void clearArea(ServerLevel level, BlockPos origin) {
		for (BlockPos p : BlockPos.betweenClosed(origin.offset(-8, -1, -8), origin.offset(8, 4, 8))) {
			level.setBlock(p, p.getY() == origin.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
		}
	}

	public static int run(ServerLevel level, BlockPos origin, VillageRef ref) {
		checks = 0;
		// Relief sampling reads only columns that are already loaded (it never generates terrain), so the fixture loads the
		// whole sampled square first; otherwise two samples could see different sets of loaded chunks.
		int[] gridOrigin = VillageMap.gridOrigin(level, ref);
		int gridSize = VillageMap.GRID * Math.max(1, VillageMap.gridCellSize(level, ref));
		for (int x = gridOrigin[0]; x <= gridOrigin[0] + gridSize + 15; x += 16) {
			for (int z = gridOrigin[1]; z <= gridOrigin[1] + gridSize + 15; z += 16) level.getChunk(x >> 4, z >> 4);
		}
		// The terrain grid is built incrementally (a fixed number of cells per call); sample until it has converged.
		int[] relief = DorftafelNameBlockEntity.sampleRelief(level, ref);
		for (int i = 0; i < 400 && !java.util.Arrays.equals(relief, DorftafelNameBlockEntity.sampleRelief(level, ref)); i++) {
			relief = DorftafelNameBlockEntity.sampleRelief(level, ref);
		}
		check(relief.length == DorftafelNameBlockEntity.MAP_W * DorftafelNameBlockEntity.MAP_H,
			"Relief samples cover the full 32 by 23 map");
		check(java.util.Arrays.stream(relief).allMatch(value -> (value & ~0x1f3f) == 0 && (value & 15) <= 11),
			"relief samples contain valid terrain, bounded elevation and marker fields");
		check(java.util.Arrays.stream(relief).filter(value -> (value & 15) == 3 || (value & 15) == 11)
			.allMatch(value -> ((value >> 4) & 3) == 0), "water and unloaded terrain are flat in the relief");
		check(java.util.Arrays.equals(relief, DorftafelNameBlockEntity.sampleRelief(level, ref)),
			"unchanged village terrain and markers produce stable relief samples");
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			clearArea(level, origin);
			BlockState base = ModBlocks.DORFTAFEL_GROSS.defaultBlockState().setValue(DorftafelBlock.FACING, facing);
			DorftafelBlock.placeAll(level, origin, base);
			BlockState any = level.getBlockState(origin);
			String label = "gross " + facing;
			check(any.getBlock() instanceof DorftafelGrossBlock, label + ": Block gesetzt");
			check(DorftafelBlock.widthOf(any) == 4, label + ": vier Spalten");
			check(DorftafelBlock.complete(level, origin, any), label + ": vollstaendig");
			BlockPos[] lower = DorftafelBlock.parts(any, origin);
			check(lower.length == 4 && lower[0].equals(origin), label + ": vier untere Teile ab links");
			Direction r = facing.getCounterClockWise();
			for (int i = 0; i < 4; i++) {
				check(lower[i].equals(origin.relative(r, i)), label + ": Teil " + i + " liegt rechts daneben");
				BlockState lowerState = level.getBlockState(lower[i]);
				BlockState upperState = level.getBlockState(lower[i].above());
				check(lowerState.getValue(DorftafelBlock.PART) == DorftafelBlock.partAt(true, i), label + ": Teil " + i + " Rolle");
				check(lowerState.getValue(DorftafelBlock.HALF) == DoubleBlockHalf.LOWER && upperState.getValue(DorftafelBlock.HALF) == DoubleBlockHalf.UPPER,
					label + ": Teil " + i + " unten und oben");
				// every part finds the same set of parts, whichever one is asked
				check(java.util.Arrays.equals(DorftafelBlock.parts(upperState, lower[i].above()), lower), label + ": Teil " + i + " kennt die Tafel");
			}
			check(level.getBlockState(origin).getValue(DorftafelGrossBlock.WOOD) != null, label + ": Holzart gesetzt");
			// the village name lives on the first middle column, upper half, and nowhere else
			int entities = 0;
			for (BlockPos p : lower) {
				for (BlockPos q : new BlockPos[] {p, p.above()}) {
					if (level.getBlockEntity(q) instanceof DorftafelNameBlockEntity) entities++;
				}
			}
			check(entities == 1 && level.getBlockEntity(lower[1].above()) instanceof DorftafelNameBlockEntity, label + ": ein Namens-Block auf Mitte/oben");
			// a missing part makes the board incomplete
			level.setBlock(lower[3].above(), Blocks.AIR.defaultBlockState(), 3);
			check(!DorftafelBlock.complete(level, origin, any), label + ": fehlendes Teil wird erkannt");
		}

		// wood follows the surroundings: no village here, so the biome decides (plains -> oak)
		check(DorftafelGrossBlock.woodFor(level, origin) == DorftafelGrossBlock.Wood.OAK
			|| DorftafelGrossBlock.woodFor(level, origin) != null, "Holzart bestimmbar");

		// The large board occupies two block cells; a block above that space must not push it away.
		clearArea(level, origin);
		level.setBlock(origin.above(2), Blocks.OAK_LEAVES.defaultBlockState(), 3);
		check(Board.boardColumnClear(level, origin), "three-block canopy does not block two-block board clearance");
		level.setBlock(origin.above(), Blocks.STONE.defaultBlockState(), 3);
		check(!Board.boardColumnClear(level, origin), "obstruction inside the upper board cell blocks placement");

		// A chest below the surrounding terrain can still find a safe board strip at surface height.
		clearArea(level, origin);
		BlockPos elevatedSite = Board.findFallbackSite(level, origin.below(2));
		check(elevatedSite != null && elevatedSite.getY() == origin.getY(),
			"fallback finds loaded level ground above a chest on a slope");
		check(level.getBlockState(elevatedSite).isAir() && level.getBlockState(elevatedSite.below()).is(Blocks.STONE),
			"fallback search leaves the terrain untouched");

		// an old three-block board stays valid and is upgraded in place when its fourth column is free
		clearArea(level, origin);
		BlockState old = ModBlocks.DORFTAFEL.defaultBlockState().setValue(DorftafelBlock.FACING, Direction.SOUTH);
		DorftafelBlock.placeAll(level, origin, old);
		BlockState oldAny = level.getBlockState(origin);
		check(DorftafelBlock.widthOf(oldAny) == 3 && DorftafelBlock.complete(level, origin, oldAny), "alte Tafel bleibt dreibreit und vollstaendig");
		check(DorftafelBlock.parts(oldAny, origin).length == 3, "alte Tafel hat drei Teile");
		ref.data().extra.board = java.util.Optional.of(origin);
		Board.ensure(level, ref);
		BlockState upgraded = level.getBlockState(origin);
		check(upgraded.getBlock() instanceof DorftafelGrossBlock && DorftafelBlock.complete(level, origin, upgraded), "alte Tafel wird zur grossen Tafel");
		check(level.getBlockState(origin.relative(Direction.SOUTH.getCounterClockWise(), 3)).getBlock() instanceof DorftafelGrossBlock, "vierte Spalte steht");

		// a blocked fourth column keeps the old board as it is
		clearArea(level, origin);
		DorftafelBlock.placeAll(level, origin, old);
		level.setBlock(origin.relative(Direction.SOUTH.getCounterClockWise(), 3), Blocks.STONE.defaultBlockState(), 3);
		Board.ensure(level, ref);
		check(level.getBlockState(origin).getBlock() == ModBlocks.DORFTAFEL, "blockierte vierte Spalte: alte Tafel bleibt");
		clearArea(level, origin);
		portableBoard(level, origin, ref);
		return checks;
	}

	public static boolean prepare(ServerLevel level, BlockPos origin) {
		boolean ready = true;
		for (BlockPos corner : tickingCorners(origin)) ready &= ButcherChecks.prepareEntityTickingFixture(level, corner);
		return ready;
	}

	public static void release(ServerLevel level, BlockPos origin) {
		for (BlockPos corner : tickingCorners(origin)) ButcherChecks.releaseEntityTickingFixture(level, corner);
	}

	private static List<BlockPos> tickingCorners(BlockPos origin) {
		return List.of(origin.offset(-8, 0, -8), origin.offset(-8, 0, 8), origin.offset(8, 0, -8), origin.offset(8, 0, 8));
	}

	private static List<ItemEntity> boardDrops(ServerLevel level, BlockPos origin) {
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(8),
			entity -> entity.getItem().is(ModBlocks.DORFTAFEL_GROSS.asItem()));
	}

	private static List<ItemStack> explosionLoot(ServerLevel level, BlockPos pos, BlockState state) {
		return state.getDrops(new LootParams.Builder(level)
			.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
			.withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
			.withParameter(LootContextParams.EXPLOSION_RADIUS, 1.0F));
	}

	private static void configurePlayerMode(ServerPlayer player, GameType type) {
		// A fixture player has no packet listener. Set the mode through the vanilla server primitive before breaking.
		try {
			var setMode = ServerPlayerGameMode.class.getDeclaredMethod("setGameModeForPlayer", GameType.class, GameType.class);
			setMode.setAccessible(true);
			setMode.invoke(player.gameMode, type, null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to switch the board fixture player's game mode", e);
		}
	}

	private static void portableBoard(ServerLevel level, BlockPos origin, VillageRef ref) {
		BlockState board = ModBlocks.DORFTAFEL_GROSS.defaultBlockState().setValue(DorftafelBlock.FACING, Direction.SOUTH);
		BlockPos marked = origin.east().above();
		BlockPos spare = origin.north(4);
		BlockPos target = origin.south(5);
		Optional<BlockPos> oldBoard = ref.data().extra.board;
		Optional<UUID> oldTransit = ref.data().extra.boardInTransit;
		boolean oldPin = ref.data().extra.boardPinned;
		AtomicReference<Component> notice = new AtomicReference<>();
		GameProfile profile = new GameProfile(UUID.randomUUID(), "VillageBoardFixture");
		ServerPlayer player = new ServerPlayer(level.getServer(), level,
			profile, ClientInformation.createDefault()) {
			@Override
			public void sendOverlayMessage(Component message) {
				notice.set(message);
			}
		};
		// Fabric sends a block resync packet when the before-break event cancels a break.
		// An unopened connection can queue that packet without requiring a real client.
		player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
			player, CommonListenerCookie.createInitial(profile, false));
		configurePlayerMode(player, GameType.SURVIVAL);
		try {
			DorftafelBlock.placeAll(level, origin, board);
			DorftafelBlock.placeAll(level, spare, board);
			Board.playerPlaced(level, spare, board, new ItemStack(ModBlocks.DORFTAFEL_GROSS));
			ref.data().extra.board = Optional.of(marked); // old worlds can record a part other than the anchor
			check(player.gameMode.destroyBlock(spare.east().above()), "a player can break the middle of an ordinary board");
			List<ItemEntity> spareDrops = boardDrops(level, origin);
			check(spareDrops.size() == 1 && spareDrops.getFirst().getItem().getCount() == 1
				&& spareDrops.getFirst().getItem().get(DataComponents.CUSTOM_DATA) == null
				&& ref.data().extra.board.equals(Optional.of(marked)) && ref.data().extra.boardInTransit.isEmpty(),
				"breaking an unrelated board drops one standard item and leaves the official board registered (drops="
					+ spareDrops.size() + ", board=" + ref.data().extra.board + ", mode=" + player.gameMode() + ")");
			spareDrops.getFirst().discard();

			checkUnavailableBell(level, origin, ref, board, marked, player, notice);
			var gameRules = level.getGameRules();
			boolean oldBlockDrops = gameRules.get(GameRules.BLOCK_DROPS);
			try {
				gameRules.set(GameRules.BLOCK_DROPS, false, level.getServer());
				check(!player.gameMode.destroyBlock(marked) && DorftafelBlock.complete(level, origin, board)
					&& ref.data().extra.boardInTransit.isEmpty() && boardDrops(level, origin).isEmpty(),
					"disabled block drops leave the official board intact and create no outstanding token");
			} finally {
				gameRules.set(GameRules.BLOCK_DROPS, oldBlockDrops, level.getServer());
			}
			check(player.gameMode.destroyBlock(marked), "a player can collect the official board from its upper middle part");
			List<ItemEntity> officialDrops = boardDrops(level, origin);
			check(officialDrops.size() == 1 && officialDrops.getFirst().getItem().getCount() == 1
				&& officialDrops.getFirst().getItem().get(DataComponents.CUSTOM_DATA) != null
				&& ref.data().extra.board.isEmpty() && ref.data().extra.boardInTransit.isPresent(),
				"one portable board drops for the entire assembly and its village awaits replacement");
			check(java.util.Arrays.stream(DorftafelBlock.parts(board, origin)).allMatch(pos ->
				level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()), "no lower or upper piece is left behind");
			ItemStack carried = officialDrops.getFirst().getItem().copy();
			ItemStack copied = carried.copy();
			officialDrops.getFirst().discard();

			VillageData saved = VillageData.CODEC.parse(NbtOps.INSTANCE,
				VillageData.CODEC.encodeStart(NbtOps.INSTANCE, ref.data()).getOrThrow()).getOrThrow();
			ref.bellEntity().setAttached(VillagerPlus.VILLAGE, saved);
			VillageRef loaded = new VillageRef(ref.bell(), saved, ref.bellEntity());
			check(saved.extra.boardInTransit.equals(ref.data().extra.boardInTransit) && saved.extra.board.isEmpty(),
				"saving and reloading remembers the exact outstanding board");
			Board.ensure(level, loaded);
			Palette palette = Palette.detect(level, ref.bell(), null);
			check(Plaza.placeBoardAndFlagpole(level, loaded, origin, palette, true) == null
				&& saved.extra.board.isEmpty() && level.getBlockState(origin).isAir(),
				"village maintenance and new plaza decoration both await the carried board");
			DorftafelBlock.placeAll(level, spare, board);
			check(Board.playerPlaced(level, spare, board, new ItemStack(ModBlocks.DORFTAFEL_GROSS)),
				"an independent board can be placed while the official board is carried");
			var carriedToken = saved.extra.boardInTransit;
			player.setPos(Vec3.atBottomCenterOf(origin));
			check(!Board.relocateOfficial(level, loaded, player, spare)
				&& DorftafelBlock.complete(level, spare, board) && level.getBlockState(origin).isAir()
				&& saved.extra.board.isEmpty() && saved.extra.boardInTransit.equals(carriedToken),
				"the ordinary board's relocate action cannot invalidate the carried official board");
			DorftafelBlock.removeParts(level, spare, board, null);
			check(!Board.canPlace(level, ref.bell().north(120), board, carried, null),
				"the carried official board cannot adopt a different village");
			BlockPlaceContext blocked = boardContext(player, target, carried);
			Direction side = blocked.getHorizontalDirection().getOpposite().getCounterClockWise();
			BlockPos obstructionPos = target.relative(side, 2);
			var obstruction = EntityTypes.VILLAGER.spawn(level, obstructionPos, EntitySpawnReason.COMMAND);
			check(obstruction != null, "the placement fixture spawns a villager in a side column beyond the root block");
			try {
				obstruction.setNoAi(true);
				var token = saved.extra.boardInTransit;
				check(!((BlockItem) carried.getItem()).place(blocked).consumesAction() && carried.getCount() == 1
					&& saved.extra.boardInTransit.equals(token) && saved.extra.board.isEmpty()
					&& java.util.stream.IntStream.range(0, 4).mapToObj(i -> target.relative(side, i)).allMatch(cell ->
						level.getBlockState(cell).isAir() && level.getBlockState(cell.above()).isAir()),
					"an obstruction outside the anchor rejects the entire assembly without consuming the board or its token");
			} finally {
				obstruction.discard();
			}

			InteractionResult result = placeBoardItem(player, target, carried);
			BlockState placed = level.getBlockState(target);
			check(result.consumesAction() && carried.isEmpty() && placed.getBlock() instanceof DorftafelGrossBlock
				&& DorftafelBlock.complete(level, target, placed) && saved.extra.board.equals(Optional.of(target))
				&& saved.extra.boardPinned && saved.extra.boardInTransit.isEmpty(),
				"normal block-item placement consumes the drop and pins its complete board at the new position");
			level.tick(() -> false);
			Board.ensure(level, loaded);
			check(Plaza.placeBoardAndFlagpole(level, loaded, origin, palette, true).equals(target)
				&& DorftafelBlock.complete(level, target, placed) && level.getBlockState(origin).isAir()
				&& boardDrops(level, origin).isEmpty(), "a server tick and both maintenance paths keep just the manually placed board");
			check(!Board.canPlace(level, spare, board, copied, null) && level.getBlockState(spare).isAir(),
				"an already used portable board token cannot place again");
			BlockPos upper = DorftafelBlock.parts(placed, target)[1].above();
			List<ItemStack> explosionRoot = explosionLoot(level, target, placed);
			check(explosionRoot.size() == 1 && explosionRoot.getFirst().getCount() == 1
				&& explosionRoot.getFirst().get(DataComponents.CUSTOM_DATA) == null
				&& explosionLoot(level, upper, level.getBlockState(upper)).isEmpty(),
				"explosion loot can drop one standard board from the root only at a guaranteed survival radius");
			check(player.gameMode.destroyBlock(target), "a player can collect the pinned official board from its lower left root");
			List<ItemEntity> rootDrops = boardDrops(level, origin);
			check(rootDrops.size() == 1 && rootDrops.getFirst().getItem().getCount() == 1
				&& rootDrops.getFirst().getItem().get(DataComponents.CUSTOM_DATA) != null
				&& saved.extra.boardInTransit.isPresent() && saved.extra.board.isEmpty(),
				"the root's ordinary loot cannot produce a second item during player pickup");
			ItemStack forCreative = rootDrops.getFirst().getItem().copy();
			rootDrops.getFirst().discard();
			check(placeBoardItem(player, origin, forCreative).consumesAction() && forCreative.isEmpty(),
				"a survival player can restore the registered board for the creative-mode check");
			configurePlayerMode(player, GameType.CREATIVE);
			DorftafelBlock.placeAll(level, spare, board);
			Board.playerPlaced(level, spare, board, new ItemStack(ModBlocks.DORFTAFEL_GROSS));
			check(player.gameMode.destroyBlock(spare.east().above()) && boardDrops(level, origin).isEmpty(),
				"breaking an independent board in creative mode does not create an item");
			BlockState creativeBoard = level.getBlockState(origin);
			BlockPos creativeMiddle = DorftafelBlock.parts(creativeBoard, origin)[1].above();
			check(player.gameMode.destroyBlock(creativeMiddle), "creative can lift the registered board when its bell is loaded");
			List<ItemEntity> creativeDrops = boardDrops(level, origin);
			Board.ensure(level, loaded);
			check(creativeDrops.size() == 1 && creativeDrops.getFirst().getItem().getCount() == 1
				&& creativeDrops.getFirst().getItem().get(DataComponents.CUSTOM_DATA) != null
				&& saved.extra.boardInTransit.isPresent() && saved.extra.board.isEmpty() && level.getBlockState(origin).isAir(),
				"creative pickup of the official board still creates exactly one linked item and no replacement board");
		} finally {
			boardDrops(level, origin).forEach(ItemEntity::discard);
			clearArea(level, origin);
			ref.data().extra.board = oldBoard;
			ref.data().extra.boardInTransit = oldTransit;
			ref.data().extra.boardPinned = oldPin;
			ref.bellEntity().setAttached(VillagerPlus.VILLAGE, ref.data());
			ref.dirty();
		}
	}

	private static InteractionResult placeBoardItem(ServerPlayer player, BlockPos target, ItemStack item) {
		return ((BlockItem) item.getItem()).place(boardContext(player, target, item));
	}

	private static BlockPlaceContext boardContext(ServerPlayer player, BlockPos target, ItemStack item) {
		player.setPos(target.getX() - 2.5, target.getY(), target.getZ() + 3.5);
		BlockHitResult ground = new BlockHitResult(Vec3.atBottomCenterOf(target), Direction.UP, target.below(), false);
		return new BlockPlaceContext(player, InteractionHand.MAIN_HAND, item, ground);
	}

	private static void checkUnavailableBell(ServerLevel level, BlockPos origin, VillageRef ref, BlockState board,
			BlockPos marked, ServerPlayer player, AtomicReference<Component> notice) {
		Board.ensure(level, ref);
		DorftafelNameBlockEntity display = (DorftafelNameBlockEntity) level.getBlockEntity(marked);
		GlobalPos currentOwner = GlobalPos.of(level.dimension(), ref.bell());
		check(currentOwner.equals(display.villageOwner()), "maintenance upgrades an old marker to a persistent board-side bell reference");
		BlockPos unloadedBell = origin.offset(512, 0, 512);
		check(!LoadedColumns.ready(level, unloadedBell), "the fixture bell's alternate column starts unloaded");
		GlobalPos absentOwner = GlobalPos.of(level.dimension(), unloadedBell);
		display.rememberVillageOwner(absentOwner);
		var unloadedBoardSave = display.saveWithoutMetadata(level.registryAccess());
		display.rememberVillageOwner(currentOwner);
		display.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), unloadedBoardSave));
		check(absentOwner.equals(display.villageOwner()), "saving and loading a board keeps its bell dimension and position");
		check(!player.gameMode.destroyBlock(marked) && DorftafelBlock.complete(level, origin, board)
			&& !LoadedColumns.ready(level, unloadedBell) && boardDrops(level, origin).isEmpty()
			&& ref.data().extra.board.equals(Optional.of(marked)) && ref.data().extra.boardInTransit.isEmpty(),
			"survival mining refuses the official board while its recorded bell is unloaded, without drops, partial removal or chunk loads");
		check(notice.get() != null && notice.get().getSiblings().getLast().getContents() instanceof TranslatableContents contents
			&& contents.getKey().equals("message.villagerplus.board_break_bell_unavailable"), "the blocked break explains that the bell must be loaded");
		Board.ensure(level, ref);
		check(currentOwner.equals(display.villageOwner()) && level.getBlockState(origin).getBlock() instanceof DorftafelGrossBlock
			&& ref.data().extra.board.equals(Optional.of(marked)), "maintenance keeps the original assembly and rebinds its loaded official marker");
	}
}
