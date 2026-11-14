package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/**
 * Handelsnetz eines Dorfes (haengt als eigenes Attachment an der Glocke; {@code VillageData.Extra} ist mit 16 Codec-Feldern voll):
 * die drei fiktiven Partnerorte der Karawanen samt Beziehung, die mit echten Dorfern verbundenen Nachbarn, Abklingzeiten je Aktion
 * und Paar sowie die Gruss-Stimmung. Alle Records sind unveraenderlich; Aenderungen erzeugen ein neues Objekt (siehe {@link Network}).
 */
public record TradeNetwork(List<Partner> partners, List<Link> links, Map<String, Long> cooldowns, int moodBonus, long moodBonusUntil,
	Map<String, Long> tradeBalance, List<PendingAction> pendingActions) {
	public static final int MAX_LINKS = 12;
	public static final int MAX_PENDING_ACTIONS = 64;
	public TradeNetwork(List<Partner> partners,List<Link> links,Map<String,Long> cooldowns,int moodBonus,long moodBonusUntil) {
		this(partners,links,cooldowns,moodBonus,moodBonusUntil,Map.of(),List.of());
	}
	public TradeNetwork(List<Partner> partners,List<Link> links,Map<String,Long> cooldowns,int moodBonus,long moodBonusUntil,Map<String,Long> tradeBalance) {
		this(partners,links,cooldowns,moodBonus,moodBonusUntil,tradeBalance,List.of());
	}

	public TradeNetwork {
		partners = partners == null ? List.of() : List.copyOf(partners);
		links = links == null ? List.of() : List.copyOf(links);
		cooldowns = cooldowns == null ? Map.of() : Map.copyOf(cooldowns);
		tradeBalance = tradeBalance == null ? Map.of() : Map.copyOf(tradeBalance);
		pendingActions = pendingActions == null ? List.of() : pendingActions.stream()
			.filter(action -> action != null && action.valid()).limit(MAX_PENDING_ACTIONS).toList();
	}

	/** Spezialitaeten der fiktiven Partnerorte. */
	public static final String[] SPECIALTIES = {"Erze & Werkzeug", "Stoffe & Wolle", "Lebensmittel", "Holz & Baumaterial"};

	/** Fiktiver Handelspartner: Name, Spezialitaet (Index in {@link #SPECIALTIES}), Beziehung -100..100, Tag des letzten Besuchs. */
	public record Partner(String name, int specialty, int relation, long lastVisitDay) {
		public static final Codec<Partner> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("name").forGetter(Partner::name),
			Codec.INT.fieldOf("specialty").forGetter(Partner::specialty),
			Codec.INT.optionalFieldOf("relation", 0).forGetter(Partner::relation),
			Codec.LONG.optionalFieldOf("last_visit_day", -1L).forGetter(Partner::lastVisitDay)).apply(i, Partner::new));

		public Partner withRelation(int r) {
			return new Partner(name, specialty, Math.max(-100, Math.min(100, r)), lastVisitDay);
		}

		public Partner visited(long day) {
			return new Partner(name, specialty, relation, day);
		}
	}

	/** Verbindung zu einem echten Dorf (dimensionsqualifiziert), mit Einwohnerstand und letztem echten Handelsbesuch. */
	public record Link(String dimension, BlockPos bell, String name, int relation, boolean route, int population, long populationDay, long lastVisitDay) {
		public Link(String dimension, BlockPos bell, String name, int relation, boolean route, int population, long populationDay) {
			this(dimension, bell, name, relation, route, population, populationDay, -1L);
		}

		public static final Codec<Link> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("dimension").forGetter(Link::dimension),
			BlockPos.CODEC.fieldOf("bell").forGetter(Link::bell),
			Codec.STRING.fieldOf("name").forGetter(Link::name),
			Codec.INT.optionalFieldOf("relation", 10).forGetter(Link::relation),
			Codec.BOOL.optionalFieldOf("route", false).forGetter(Link::route),
			Codec.INT.optionalFieldOf("population", -1).forGetter(Link::population),
			Codec.LONG.optionalFieldOf("population_day", -1L).forGetter(Link::populationDay),
			Codec.LONG.optionalFieldOf("last_visit_day", -1L).forGetter(Link::lastVisitDay)).apply(i, Link::new));

		public Link withRelation(int r) {
			return new Link(dimension, bell, name, Math.max(-100, Math.min(100, r)), route, population, populationDay, lastVisitDay);
		}

		public Link withRoute(boolean on) {
			return new Link(dimension, bell, name, relation, on, population, populationDay, lastVisitDay);
		}

		public Link withPopulation(int pop, long day) {
			return new Link(dimension, bell, name, relation, route, pop, day, lastVisitDay);
		}

		public Link visited(long day) {
			return new Link(dimension, bell, name, relation, route, population, populationDay, day);
		}
	}

	/** A durable action accepted while its counterpart bell is unloaded; the source village owns this queue. */
	public record PendingAction(String action, String dimension, BlockPos targetBell, boolean routeEnabled, long requestedAt) {
		public static final Codec<PendingAction> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("action").forGetter(PendingAction::action),
			Codec.STRING.fieldOf("dimension").forGetter(PendingAction::dimension),
			BlockPos.CODEC.fieldOf("target_bell").forGetter(PendingAction::targetBell),
			Codec.BOOL.optionalFieldOf("route_enabled", false).forGetter(PendingAction::routeEnabled),
			Codec.LONG.optionalFieldOf("requested_at", 0L).forGetter(PendingAction::requestedAt)).apply(i, PendingAction::new));

		public boolean valid() {
			return ("route".equals(action) || "greet".equals(action)) && dimension != null && !dimension.isBlank()
				&& dimension.length() <= 128 && targetBell != null && requestedAt >= 0;
		}
	}

	public static final Codec<TradeNetwork> CODEC = RecordCodecBuilder.create(i -> i.group(
		Partner.CODEC.listOf().optionalFieldOf("partners", List.of()).forGetter(TradeNetwork::partners),
		Link.CODEC.listOf().optionalFieldOf("links", List.of()).forGetter(TradeNetwork::links),
		Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("cooldowns", Map.of()).forGetter(TradeNetwork::cooldowns),
		Codec.INT.optionalFieldOf("mood_bonus", 0).forGetter(TradeNetwork::moodBonus),
		Codec.LONG.optionalFieldOf("mood_bonus_until", 0L).forGetter(TradeNetwork::moodBonusUntil),
		Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("trade_balance", Map.of()).forGetter(TradeNetwork::tradeBalance),
		PendingAction.CODEC.listOf().optionalFieldOf("pending_actions", List.of()).forGetter(TradeNetwork::pendingActions)).apply(i, TradeNetwork::new));

	public TradeNetwork withPartners(List<Partner> p) {
		return new TradeNetwork(List.copyOf(p), links, cooldowns, moodBonus, moodBonusUntil, tradeBalance, pendingActions);
	}

	public TradeNetwork withLinks(List<Link> l) {
		return new TradeNetwork(partners, List.copyOf(l), cooldowns, moodBonus, moodBonusUntil, tradeBalance, pendingActions);
	}

	public TradeNetwork withCooldown(String key, long until) {
		Map<String, Long> c = new HashMap<>(cooldowns);
		c.put(key, until);
		return new TradeNetwork(partners, links, Map.copyOf(c), moodBonus, moodBonusUntil, tradeBalance, pendingActions);
	}

	public TradeNetwork withMood(int bonus, long until) {
		return new TradeNetwork(partners, links, cooldowns, bonus, until, tradeBalance, pendingActions);
	}

	public TradeNetwork withPendingAction(PendingAction pending) {
		if (pending == null || !pending.valid()) return this;
		List<PendingAction> updated = new ArrayList<>(pendingActions);
		if ("route".equals(pending.action())) {
			updated.removeIf(existing -> "route".equals(existing.action()) && existing.dimension().equals(pending.dimension())
				&& existing.targetBell().equals(pending.targetBell()));
		} else if (updated.stream().anyMatch(existing -> existing.action().equals(pending.action())
			&& existing.dimension().equals(pending.dimension()) && existing.targetBell().equals(pending.targetBell()))) {
			return this;
		}
		if (updated.size() >= MAX_PENDING_ACTIONS) return this;
		updated.add(pending);
		return new TradeNetwork(partners, links, cooldowns, moodBonus, moodBonusUntil, tradeBalance, updated);
	}

	public TradeNetwork withoutPendingAction(PendingAction pending) {
		if (pending == null || !pendingActions.contains(pending)) return this;
		List<PendingAction> updated = new ArrayList<>(pendingActions);
		updated.remove(pending);
		return new TradeNetwork(partners, links, cooldowns, moodBonus, moodBonusUntil, tradeBalance, updated);
	}

	public boolean hasPendingAction(String action, String dimension, BlockPos targetBell) {
		return action != null && dimension != null && targetBell != null && pendingActions.stream().anyMatch(pending ->
			pending.action().equals(action) && pending.dimension().equals(dimension) && pending.targetBell().equals(targetBell));
	}

	/** Persistent cumulative village-side balance: positive items received, negative items shipped. */
	public TradeNetwork withTradeDelta(String itemId,long delta) {
		if(itemId==null||itemId.isBlank()||delta==0)return this;
		Map<String,Long> balances=new HashMap<>(tradeBalance);
		long old=balances.getOrDefault(itemId,0L),next;
		try { next=Math.addExact(old,delta); } catch(ArithmeticException ignored) { next=delta>0?Long.MAX_VALUE:Long.MIN_VALUE; }
		balances.put(itemId,next);
		return new TradeNetwork(partners,links,cooldowns,moodBonus,moodBonusUntil,Map.copyOf(balances),pendingActions);
	}

	public int linkIndex(String dimension, BlockPos bell) {
		for (int i = 0; i < links.size(); i++) {
			if (links.get(i).dimension().equals(dimension) && links.get(i).bell().equals(bell)) {
				return i;
			}
		}
		return -1;
	}

	/** Frisches Netz mit drei deterministisch aus der Glockenposition gewuerfelten Partnerorten. */
	public static TradeNetwork create(BlockPos bell) {
		return create(bell, "plains");
	}

	/** Frisches Netz mit deterministischen, an das Biom angepassten Partnerorten. */
	public static TradeNetwork create(BlockPos bell, String biome) {
		String path = biome == null ? "plains" : biome.toLowerCase(java.util.Locale.ROOT);
		String[] firsts = path.contains("desert") || path.contains("badlands")
			? new String[] {"Oasen", "Sand", "Sonnen", "Dünen", "Gold", "Palmen", "Kaktus", "Staub", "Kupfer", "Fata", "Wüsten", "Salz"}
			: path.contains("snow") || path.contains("ice") || path.contains("frozen")
			? new String[] {"Schnee", "Frost", "Eis", "Nord", "Winter", "Silber", "Nebel", "Tannen", "Kristall", "Polar", "Gletscher", "Mond"}
			: path.contains("jungle") || path.contains("bamboo")
			? new String[] {"Dschungel", "Bambus", "Lianen", "Regen", "Smaragd", "Papagei", "Fluss", "Tropen", "Orchideen", "Farn", "Kakao", "Morgen"}
			: path.contains("taiga") || path.contains("forest") || path.contains("grove")
			? new String[] {"Fichten", "Birken", "Eichen", "Linden", "Wald", "Moos", "Falken", "Raben", "Tannen", "Wild", "Holz", "Bären"}
			: new String[] {"Fern", "Nebel", "Sonnen", "Eisen", "Raben", "Linden", "Wolken", "Stein", "Birken", "Mond", "Falken", "Bären"};
		String[] lasts = path.contains("desert") || path.contains("badlands")
			? new String[] {"oase", "dünen", "furt", "quell", "stein", "au", "brunnen", "dorf", "brück", "hang", "hain", "mark"}
			: path.contains("snow") || path.contains("ice") || path.contains("frozen")
			? new String[] {"hausen", "tal", "furt", "bach", "stein", "heim", "dorf", "brück", "wald", "hain", "pass", "mark"}
			: new String[] {"hausen", "tal", "furt", "bach", "stein", "au", "heim", "dorf", "brück", "wald", "hain", "mark"};
		java.util.Random r = new java.util.Random(bell.asLong() * 6364136223846793005L + 1442695040888963407L);
		List<Partner> ps = new ArrayList<>();
		int spec = r.nextInt(SPECIALTIES.length);
		while (ps.size() < 3) {
			String name = firsts[r.nextInt(firsts.length)] + lasts[r.nextInt(lasts.length)];
			if (ps.stream().noneMatch(p -> p.name().equals(name))) {
				ps.add(new Partner(name, (spec + ps.size()) % SPECIALTIES.length, 5 + r.nextInt(10), -1L));
			}
		}
		return new TradeNetwork(ps, List.of(), Map.of(), 0, 0L);
	}
}
