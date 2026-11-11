package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.villagerplus.VillagerPlus;
import de.villagerplus.net.SetZone;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Persistent, bounded map zones attached to their owning village bell. */
public final class ZonePolicy {
	public static final int MAX_ZONES = 32;
	public static final int MAX_RADIUS = 64;
	public record RequestAccess(boolean spectator, boolean serverLevelAvailable, boolean boardChunkLoaded,
		boolean validBoard, boolean withinBoardRange, boolean villageFound) { }
	public record Zone(String id, String kind, int minX, int minZ, int maxX, int maxZ) {
		public static final Codec<Zone> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("id").forGetter(Zone::id), Codec.STRING.fieldOf("kind").forGetter(Zone::kind),
			Codec.INT.fieldOf("min_x").forGetter(Zone::minX), Codec.INT.fieldOf("min_z").forGetter(Zone::minZ),
			Codec.INT.fieldOf("max_x").forGetter(Zone::maxX), Codec.INT.fieldOf("max_z").forGetter(Zone::maxZ)
		).apply(i, Zone::new));
	}
	private ZonePolicy() { }

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(SetZone.TYPE, SetZone.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SetZone.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
			boolean boardChunkLoaded = level != null && level.hasChunkAt(payload.board());
			boolean validBoard = boardChunkLoaded
				&& level.getBlockState(payload.board()).getBlock() instanceof de.villagerplus.block.DorftafelBlock;
			boolean withinBoardRange = validBoard && player.blockPosition().distSqr(payload.board()) <= 24L * 24L;
			VillageRef ref = withinBoardRange ? Villages.villageNear(level, payload.board(), 96) : null;
			RequestAccess access = new RequestAccess(player.isSpectator(), level != null, boardChunkLoaded,
				validBoard, withinBoardRange, ref != null);
			String rejection = accessFailure(access);
			if (rejection == null) rejection = actionFailure(ref, payload);
			boolean accepted = rejection == null && apply(ref, payload);
			String message = rejection != null ? rejectionMessage(rejection) : accepted ? resultMessage(payload, true)
				: rejectionMessage("Die Zone konnte wegen einer zwischenzeitlichen Änderung nicht gespeichert werden.");
			ServerPlayNetworking.send(player, new SetZone.Result(payload.board(), payload.requestId(), accepted, message));
			if (validBoard && withinBoardRange && ref != null && (accepted || rejection != null)) {
				de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
			}
		});
	}

	/** Returns the first board/player access failure so every request receives an actionable answer. */
	public static String accessFailure(RequestAccess access) {
		if (access == null) return "Die Anfrage fehlt.";
		if (access.spectator()) return "Zonen können im Zuschauermodus nicht geändert werden.";
		if (!access.serverLevelAvailable()) return "Die Anfrage kann in dieser Welt nicht gespeichert werden.";
		if (!access.boardChunkLoaded()) return "Die Dorftafel ist nicht geladen oder nicht vorhanden.";
		if (!access.validBoard()) return "An der angegebenen Position steht keine gültige Dorftafel.";
		if (!access.withinBoardRange()) return "Du bist zu weit von der Dorftafel entfernt; erlaubt sind höchstens 24 Blöcke.";
		if (!access.villageFound()) return "In der Nähe der Dorftafel wurde keine Dorfglocke gefunden.";
		return null;
	}

	/** Returns the specific invalid dimension, capacity, or stale-delete reason before mutation. */
	public static String actionFailure(VillageRef ref, SetZone payload) {
		if (payload == null) return "Die Zonenanfrage fehlt.";
		if (payload.requestId() <= 0) return "Die Kennung der Zonenanfrage ist ungültig.";
		if (ref == null) return "In der Nähe der Dorftafel wurde keine Dorfglocke gefunden.";
		if (payload.delete()) {
			if (payload.kind() == null || payload.kind().isBlank() || payload.kind().length() > 36) {
				return "Die Kennung der zu löschenden Zone ist ungültig.";
			}
			boolean exists = ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
				.anyMatch(zone -> zone.id().equals(payload.kind()));
			return exists ? null : "Diese Zone existiert nicht mehr oder wurde bereits gelöscht.";
		}
		if (!("farm".equals(payload.kind()) || "housing".equals(payload.kind())
			|| "work".equals(payload.kind()) || "pasture".equals(payload.kind()))) {
			return "Die ausgewählte Zonenkategorie ist ungültig.";
		}
		int minX = Math.min(payload.minX(), payload.maxX()), maxX = Math.max(payload.minX(), payload.maxX());
		int minZ = Math.min(payload.minZ(), payload.maxZ()), maxZ = Math.max(payload.minZ(), payload.maxZ());
		if (Math.abs((long)minX - ref.bell().getX()) > MAX_RADIUS || Math.abs((long)maxX - ref.bell().getX()) > MAX_RADIUS
			|| Math.abs((long)minZ - ref.bell().getZ()) > MAX_RADIUS || Math.abs((long)maxZ - ref.bell().getZ()) > MAX_RADIUS) {
			return "Die Zone liegt außerhalb des zulässigen Radius von 64 Blöcken um die Dorfglocke.";
		}
		long area = ((long)maxX - minX + 1L) * ((long)maxZ - minZ + 1L);
		if (area <= 0L || area > 4096L) return "Die Zone überschreitet die zulässige Fläche von 4096 Blöcken.";
		if (ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).size() >= MAX_ZONES) {
			return "Das Dorf hat bereits die Höchstzahl von " + MAX_ZONES + " Zonen.";
		}
		return null;
	}

	/** Explicit feedback keeps a saved or rejected map action visible even when the zone outline is subtle. */
	public static String resultMessage(SetZone payload, boolean accepted) {
		if (payload == null) return rejectionMessage("Die Anfrage fehlt.");
		if (!accepted) return rejectionMessage("Auswahl ungültig oder Zonenlimit erreicht.");
		if (payload.delete()) return "Zone gelöscht: " + (payload.kind() == null ? "unbekannte Kennung" : payload.kind()) + ".";
		String name = switch (payload.kind() == null ? "" : payload.kind()) {
			case "farm" -> "Feldzone";
			case "housing" -> "Wohnzone";
			case "work" -> "Arbeitszone";
			case "pasture" -> "Weidezone";
			default -> "Zone";
		};
		int minX = Math.min(payload.minX(), payload.maxX()), maxX = Math.max(payload.minX(), payload.maxX());
		int minZ = Math.min(payload.minZ(), payload.maxZ()), maxZ = Math.max(payload.minZ(), payload.maxZ());
		return name + " gespeichert: X " + minX + " bis " + maxX + ", Z " + minZ + " bis " + maxZ + ".";
	}

	private static String rejectionMessage(String reason) { return "Zone abgelehnt: " + reason; }

	/** Applies the server-bound zone action after the network handler has verified the board and player. */
	public static boolean apply(VillageRef ref, SetZone payload) {
		if (ref == null || payload == null) return false;
		return payload.delete() ? delete(ref, payload.kind()) : add(ref, payload);
	}

	private static boolean add(VillageRef ref, SetZone p) {
		int minX = Math.min(p.minX(), p.maxX()), maxX = Math.max(p.minX(), p.maxX());
		int minZ = Math.min(p.minZ(), p.maxZ()), maxZ = Math.max(p.minZ(), p.maxZ());
		if (!validBounds(ref, p.kind(), minX, minZ, maxX, maxZ)) return false;
		List<Zone> zones = new ArrayList<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()));
		if (zones.size() >= MAX_ZONES) return false;
		zones.add(new Zone(UUID.randomUUID().toString(), p.kind(), minX, minZ, maxX, maxZ));
		ref.bellEntity().setAttached(VillagerPlus.ZONES, List.copyOf(zones)); ref.dirty(); return true;
	}
	public static boolean validBounds(VillageRef ref, String kind, int minX, int minZ, int maxX, int maxZ) {
		if (ref == null || !("farm".equals(kind) || "housing".equals(kind) || "work".equals(kind) || "pasture".equals(kind)) || minX > maxX || minZ > maxZ) return false;
		return Math.abs((long)minX - ref.bell().getX()) <= MAX_RADIUS && Math.abs((long)maxX - ref.bell().getX()) <= MAX_RADIUS
			&& Math.abs((long)minZ - ref.bell().getZ()) <= MAX_RADIUS && Math.abs((long)maxZ - ref.bell().getZ()) <= MAX_RADIUS
			&& (long)(maxX - minX + 1) * (maxZ - minZ + 1) <= 4096;
	}
	private static boolean delete(VillageRef ref, String id) {
		if (id == null || id.length() > 36) return false;
		List<Zone> zones = new ArrayList<>(ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()));
		boolean removed = zones.removeIf(z -> z.id().equals(id));
		if (removed) { ref.bellEntity().setAttached(VillagerPlus.ZONES, List.copyOf(zones)); ref.dirty(); }
		return removed;
	}
	public static List<String> rows(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.map(z -> "@z|" + z.id() + "|" + z.kind() + "|" + z.minX() + "|" + z.minZ() + "|" + z.maxX() + "|" + z.maxZ()).toList();
	}
}
