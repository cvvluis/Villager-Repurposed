package de.villagerplus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.ItemStack;

/** Dauerhaftes, pro Dorfbewohner gespeichertes Gedaechtnis (Fabric Data Attachment). */
public class VillagerMemory {
	private record HandSnapshot(boolean pending, Optional<ItemStack> previous, Optional<ItemStack> shown) {
		private static final Codec<HandSnapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.BOOL.optionalFieldOf("pending", false).forGetter(HandSnapshot::pending),
			ItemStack.CODEC.optionalFieldOf("previous").forGetter(HandSnapshot::previous),
			ItemStack.CODEC.optionalFieldOf("shown").forGetter(HandSnapshot::shown)
		).apply(i, HandSnapshot::new));
	}

	private static final MapCodec<VillagerMemory> BASE_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		BlockPos.CODEC.listOf().optionalFieldOf("containers", List.of()).forGetter(m -> m.containers),
		BlockPos.CODEC.listOf().optionalFieldOf("work_spots", List.of()).forGetter(m -> m.workSpots),
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.INT).optionalFieldOf("gifts", Map.of()).forGetter(m -> m.gifts),
		Codec.INT.optionalFieldOf("gossip_days", 0).forGetter(m -> m.gossipDays),
		Codec.INT.optionalFieldOf("jobs_done", 0).forGetter(m -> m.jobsDone),
		Codec.STRING.optionalFieldOf("name", "").forGetter(m -> m.name),
		BlockPos.CODEC.optionalFieldOf("home_bed").forGetter(m -> m.homeBed),
		BlockPos.CODEC.optionalFieldOf("home_door").forGetter(m -> m.homeDoor),
		BlockPos.CODEC.optionalFieldOf("sign_pos").forGetter(m -> m.signPos),
		Codec.STRING.optionalFieldOf("sign_text", "").forGetter(m -> m.signText),
		BlockPos.CODEC.optionalFieldOf("home_chest").forGetter(m -> m.homeChest),
		Codec.LONG.optionalFieldOf("last_meal", 0L).forGetter(m -> m.lastMeal),
		Codec.INT.optionalFieldOf("wages", 0).forGetter(m -> m.wages),
		Codec.INT.optionalFieldOf("chats", 0).forGetter(m -> m.chats),
		Codec.LONG.optionalFieldOf("last_sleep", 0L).forGetter(m -> m.lastSleep)
	).apply(i, VillagerMemory::new));
	public static final Codec<VillagerMemory> CODEC = BASE_CODEC.dependent(
		Codec.LONG.optionalFieldOf("last_chat", 0L),
		memory -> Pair.of(memory.lastChat, Codec.LONG.optionalFieldOf("last_chat", 0L)),
		(memory, lastChat) -> { memory.lastChat = lastChat; return memory; }
	).dependent(
		HandSnapshot.CODEC.optionalFieldOf("anim_hand", new HandSnapshot(false, Optional.empty(), Optional.empty())),
		memory -> Pair.of(new HandSnapshot(memory.animHandPending, copyStack(memory.animHandPrevious), copyStack(memory.animHandShown)),
			HandSnapshot.CODEC.optionalFieldOf("anim_hand", new HandSnapshot(false, Optional.empty(), Optional.empty()))),
		(memory, snapshot) -> {
			memory.animHandPending = snapshot.pending();
			memory.animHandPrevious = copyStack(snapshot.previous());
			memory.animHandShown = copyStack(snapshot.shown());
			return memory;
		}
	).codec();

	public final List<BlockPos> containers = new ArrayList<>();
	public final List<BlockPos> workSpots = new ArrayList<>();
	public final Map<UUID, Integer> gifts = new HashMap<>();
	public int gossipDays;
	public int jobsDone;
	public String name = "";
	public Optional<BlockPos> homeBed = Optional.empty();
	public Optional<BlockPos> homeDoor = Optional.empty();
	public Optional<BlockPos> signPos = Optional.empty();
	public String signText = "";
	/** Private Kiste im eigenen Haus (Privateigentum). */
	public Optional<BlockPos> homeChest = Optional.empty();
	public long lastMeal;
	/** Wie viele Loehne (Smaragde) schon ausgezahlt wurden. */
	public int wages;
	public int chats;
	/** Zeitpunkt (Spielzeit) des letzten Schlafs bzw. letzten Gespraechs - Grundlage fuer Energie und Soziales. */
	public long lastSleep;
	public long lastChat;
	/** Durable snapshot for restoring MAINHAND if a displayed work/cargo copy is saved mid-animation. */
	public boolean animHandPending;
	public Optional<ItemStack> animHandPrevious = Optional.empty();
	public Optional<ItemStack> animHandShown = Optional.empty();

	public VillagerMemory() {
	}

	private VillagerMemory(List<BlockPos> containers, List<BlockPos> workSpots, Map<UUID, Integer> gifts, int gossipDays, int jobsDone,
		String name, Optional<BlockPos> homeBed, Optional<BlockPos> homeDoor, Optional<BlockPos> signPos, String signText,
		Optional<BlockPos> homeChest, long lastMeal, int wages, int chats, long lastSleep) {
		this.lastSleep = lastSleep;
		this.homeChest = homeChest;
		this.lastMeal = lastMeal;
		this.wages = wages;
		this.chats = chats;
		this.containers.addAll(containers);
		this.workSpots.addAll(workSpots);
		this.gifts.putAll(gifts);
		this.gossipDays = gossipDays;
		this.jobsDone = jobsDone;
		this.name = name;
		this.homeBed = homeBed;
		this.homeDoor = homeDoor;
		this.signPos = signPos;
		this.signText = signText;
	}

	private static Optional<ItemStack> copyStack(Optional<ItemStack> stack) {
		return stack == null ? Optional.empty() : stack.filter(value -> value != null && !value.isEmpty()).map(ItemStack::copy);
	}

	/** Merkt sich eine Position (zuletzt benutzt = hinten, aelteste fliegen raus). */
	public static void remember(List<BlockPos> list, BlockPos pos, int max) {
		BlockPos immutable = pos.immutable();
		list.remove(immutable);
		list.add(immutable);
		while (list.size() > max) {
			list.remove(0);
		}
	}
}
