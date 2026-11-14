package de.villagerplus.net;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobManager;
import de.villagerplus.village.Expedition;
import de.villagerplus.village.Guests;
import de.villagerplus.village.Names;
import de.villagerplus.village.VillageMap;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStats;
import de.villagerplus.village.Wellbeing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * Server -> Client: alles, was die Dorftafel anzeigt. Karte als zwei Byte-Gitter (Gelaende, Markierungen), Balken
 * ({@code Label|Text|Prozent|RGB}), Einwohnerliste ({@code Name|Beruf|Taetigkeit|Stimmung|Haus|UUID|Berufs-ID|Kind|Lehrlingsziel}), Talk-Steckbriefe
 * ({@code UUID|Name|Beruf|Auftrag|Position|Ziel|Hunger|Stimmung|Truhenstatus|Slot;ID;Menge,...}) und Textzeilen.
 */
public record BoardData(BlockPos board, String village, int size, int cellSize, byte[] cells, byte[] marks, List<String> bars, List<String> residents,
	List<String> details, List<String> expeditions, List<String> neighbors, List<String> partners, List<String> journal,
	int talkRequestId, List<String> residentTalk) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BoardData> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "board_data"));
	private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> JOURNAL_STRINGS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(400));
	private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> STRINGS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(200));
	public static final StreamCodec<RegistryFriendlyByteBuf, BoardData> CODEC = StreamCodec.of(
		(buf, data) -> {
			BlockPos.STREAM_CODEC.encode(buf, data.board());
			ByteBufCodecs.STRING_UTF8.encode(buf, data.village());
			ByteBufCodecs.VAR_INT.encode(buf, data.size());
			ByteBufCodecs.VAR_INT.encode(buf, data.cellSize());
			ByteBufCodecs.BYTE_ARRAY.encode(buf, data.cells());
			ByteBufCodecs.BYTE_ARRAY.encode(buf, data.marks());
			STRINGS.encode(buf, data.bars());
			STRINGS.encode(buf, data.residents());
			STRINGS.encode(buf, data.details());
			STRINGS.encode(buf, data.expeditions());
			STRINGS.encode(buf, data.neighbors());
			STRINGS.encode(buf, data.partners());
			JOURNAL_STRINGS.encode(buf, data.journal());
			ByteBufCodecs.VAR_INT.encode(buf, data.talkRequestId());
			STRINGS.encode(buf, data.residentTalk());
		},
		buf -> new BoardData(
			BlockPos.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
			ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
			ByteBufCodecs.BYTE_ARRAY.decode(buf), ByteBufCodecs.BYTE_ARRAY.decode(buf),
			STRINGS.decode(buf), STRINGS.decode(buf), STRINGS.decode(buf), STRINGS.decode(buf),
			STRINGS.decode(buf), STRINGS.decode(buf), JOURNAL_STRINGS.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), STRINGS.decode(buf)));

	public BoardData {
		talkRequestId = Math.max(0, talkRequestId);
		residentTalk = residentTalk == null ? List.of() : residentTalk.stream().limit(1).toList();
	}

	/** Compatibility for client fixtures and callers that do not construct resident Talk details. */
	public BoardData(BlockPos board, String village, int size, int cellSize, byte[] cells, byte[] marks, List<String> bars, List<String> residents,
		List<String> details, List<String> expeditions, List<String> neighbors, List<String> partners, List<String> journal) {
		this(board, village, size, cellSize, cells, marks, bars, residents, details, expeditions, neighbors, partners, journal, 0, List.of());
	}

	/** Convenience overload for reports without a correlated Talk request. */
	public BoardData(BlockPos board, String village, int size, int cellSize, byte[] cells, byte[] marks, List<String> bars, List<String> residents,
		List<String> details, List<String> expeditions, List<String> neighbors, List<String> partners, List<String> journal, List<String> residentTalk) {
		this(board, village, size, cellSize, cells, marks, bars, residents, details, expeditions, neighbors, partners, journal, 0, residentTalk);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static BoardData build(ServerLevel level, VillageRef ref, BlockPos board) {
		return build(level, ref, board, "", 0);
	}

	/** Builds one requested resident report only; ordinary board refreshes never traverse private inventories. */
	public static BoardData build(ServerLevel level, VillageRef ref, BlockPos board, String requestedResidentId, int talkRequestId) {
		List<String> neighbors = de.villagerplus.village.Network.neighborRows(level, ref);
		byte[][] grid = VillageMap.grid(level, ref);
		List<String> residents = new ArrayList<>();
		List<String> residentTalk = new ArrayList<>();
		List<Villager> people = de.villagerplus.village.Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v));
		de.villagerplus.village.Journal.sample(level,ref,people,level.getGameTime());
		people.sort(Comparator.comparing(v -> v.getAttachedOrCreate(VillagerPlus.MEMORY).name));
		for (Villager v : people) {
			if (residents.size() >= 190) {
				break;
			}
			VillagerMemory mem = v.getAttachedOrCreate(VillagerPlus.MEMORY);
			Wellbeing.Status st = Wellbeing.of(level, v, mem);
			String apprenticeship = de.villagerplus.job.Apprentices.boardLabel(v);
			String job = apprenticeship != null ? apprenticeship : Names.profession(v.getVillagerData().profession()) + " " + v.getVillagerData().level();
			String marketRole = de.villagerplus.village.Market.role(ref,v); if (apprenticeship == null && !marketRole.isEmpty()) job = marketRole;
			String profKey = v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:none");
			String activity = JobManager.activityOf(v, level);
			residents.add((mem.name.isEmpty() ? "?" : mem.name) + "|" + job + "|" + activity + "|" + st.mood() + "|" + (st.home() ? "ja" : "nein")
				+ "|" + v.getUUID() + "|" + profKey + "|" + v.isBaby() + "|" + de.villagerplus.job.Apprentices.get(v).target());
			if (requestedResidentId != null && !requestedResidentId.isBlank()
				&& v.getUUID().toString().equals(requestedResidentId)) {
				residentTalk.add(residentTalkRow(level, v, mem, st, job, activity));
			}
		}
		String name = de.villagerplus.village.Network.villageName(ref);
		List<String> relationshipRows = new ArrayList<>(de.villagerplus.village.Network.partnerRows(level, ref));
		List<String> guestNames = de.villagerplus.village.Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS,
			Guests::isGuest).stream().map(v -> v.getCustomName() == null ? "Gast ohne Namen" : v.getCustomName().getString())
			.toList();
		relationshipRows.addAll(guestRows(guestNames));
		return new BoardData(board, name, VillageMap.GRID, VillageMap.gridCellSize(level, ref), grid[0], grid[1], VillageMap.bars(level, ref), residents, VillageStats.lines(level, ref),
			Expedition.lines(level, ref, level.getGameTime()), neighbors, relationshipRows,
			de.villagerplus.village.Journal.rows(level,ref), requestedResidentId == null || requestedResidentId.isBlank() ? 0 : talkRequestId,
			residentTalk);
	}

	/** Compact report for only the selected resident; ordinary refreshes never walk private inventories. */
	private static String residentTalkRow(ServerLevel level, Villager villager, VillagerMemory memory, Wellbeing.Status wellbeing,
		String job, String activity) {
		BlockPos position = villager.blockPosition();
		String target = villager.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET)
			.map(walk -> talkPosition(walk.getTarget().currentBlockPosition())).orElse("kein aktives Wegziel");
		int hunger = Math.max(0, Math.min(100, 100 - wellbeing.satiety()));
		String mood = wellbeing.moodLabel() + " (" + wellbeing.mood() + "%)";
		PrivateChestContents chest = privateChestContents(level, villager.getUUID(), memory);
		return villager.getUUID() + "|" + talkField(memory.name.isEmpty() ? "?" : memory.name) + "|" + talkField(job)
			+ "|" + talkField(activity == null || activity.isBlank() ? "untätig" : activity) + "|" + talkPosition(position)
			+ "|" + talkField(target) + "|" + hunger + "|" + talkField(mood) + "|" + talkField(chest.status()) + "|" + chest.items();
	}

	private record PrivateChestContents(String status, String items) { }

	/** Reads only a confirmed, owner-marked home chest. Item IDs and counts stay attached to their original slots. */
	private static PrivateChestContents privateChestContents(ServerLevel level, java.util.UUID owner, VillagerMemory memory) {
		if (memory.homeChest.isEmpty()) return new PrivateChestContents("nicht gefunden", "");
		BlockPos pos = memory.homeChest.get();
		if (!level.hasChunkAt(pos)) return new PrivateChestContents("nicht geladen", "");
		if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
			|| !de.villagerplus.village.Ownership.isOwnedBy(level, pos, owner)) {
			return new PrivateChestContents("keine nachweislich eigene Truhe", "");
		}
		var state = level.getBlockState(pos);
		if (state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
			!= net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
			BlockPos other = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
			if (!level.hasChunkAt(other)
				|| !(level.getBlockState(other).getBlock() instanceof net.minecraft.world.level.block.ChestBlock)
				|| !de.villagerplus.village.Ownership.isOwnedBy(level, other, owner)) {
				return new PrivateChestContents("Doppelkiste nicht vollständig bestätigt", "");
			}
		}
		net.minecraft.world.Container container = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, pos);
		if (container == null) return new PrivateChestContents("nicht geladen", "");
		List<String> items = new ArrayList<>();
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			net.minecraft.world.item.ItemStack stack = container.getItem(slot);
			if (stack.isEmpty()) continue;
			Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
			if (itemId == null) continue;
			items.add((slot + 1) + "#" + itemId + "#" + stack.getCount());
		}
		return new PrivateChestContents(items.isEmpty() ? "leer" : "Inhalt", String.join(";", items));
	}

	private static String talkPosition(BlockPos pos) {
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	private static String talkField(String value) {
		if (value == null || value.isBlank()) return "-";
		return value.replace('|', '/').replace(';', ',').replace('#', '/').replace('\n', ' ').replace('\r', ' ').replace('\u001F', ' ');
	}

	/** Board-facing rows for currently present guests; these are entity names, not invented village identities. */
	public static List<String> guestRows(List<String> names) {
		Map<String, Integer> counts = new TreeMap<>();
		if (names != null) for (String name : names) {
			String shown = name == null || name.isBlank() ? "Gast ohne Namen" : name.replace('|', '/');
			counts.merge(shown, 1, Integer::sum);
		}
		return counts.entrySet().stream().map(entry -> "@guest|" + entry.getKey() + "|" + entry.getValue() + "|anwesend").toList();
	}
}
