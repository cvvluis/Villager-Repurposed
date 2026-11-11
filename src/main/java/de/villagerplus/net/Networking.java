package de.villagerplus.net;

import de.villagerplus.village.VillageMessages;
import de.villagerplus.village.VillagerInfo;
import de.villagerplus.entity.CaravanTraderEntity;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/** Registriert die Pakete (Client und Server) und beantwortet Steckbrief-Anfragen auf dem Server. */
public final class Networking {
	private Networking() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(InfoRequest.TYPE, InfoRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(InfoResponse.TYPE, InfoResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BoardRequest.TYPE, BoardRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(AssignJob.TYPE, AssignJob.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NetworkAction.TYPE, NetworkAction.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(NetworkActionResult.TYPE, NetworkActionResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SetZone.Result.TYPE, SetZone.Result.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CancelTask.TYPE, CancelTask.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RenameVillage.TYPE, RenameVillage.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetQuota.TYPE, SetQuota.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BuildingAction.TYPE, BuildingAction.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(BuildingAction.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!payload.supportedAction() || player.isSpectator() || !(player.level() instanceof ServerLevel level) || player.blockPosition().distSqr(payload.board()) > 24 * 24
				|| !(level.getBlockState(payload.board()).getBlock() instanceof de.villagerplus.block.DorftafelBlock)) {
				return;
			}
			var ref = de.villagerplus.village.Villages.villageNear(level, payload.board(), 96);
			if (ref != null && payload.target().distSqr(payload.board()) <= 128L * 128L && payload.action().equals(BuildingAction.DEMOLISH)) {
				VillageMessages.system(player, net.minecraft.network.chat.Component.literal(de.villagerplus.village.Demolition.request(level, ref, payload.target())));
				de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
			} else if (ref != null && payload.target().distSqr(payload.board()) <= 128L * 128L && payload.action().equals(BuildingAction.MOVE)) {
				VillageMessages.system(player, net.minecraft.network.chat.Component.literal(de.villagerplus.village.BuildingRelocation.move(level, ref, payload.target(), payload.destination())));
				de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
			}
		});
		PayloadTypeRegistry.clientboundPlay().register(QuotaData.TYPE, QuotaData.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SetQuota.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.isSpectator() || !(player.level() instanceof ServerLevel level) || player.blockPosition().distSqr(payload.board()) > 24 * 24
				|| !(level.getBlockState(payload.board()).getBlock() instanceof de.villagerplus.block.DorftafelBlock)) {
				return;
			}
			var ref = de.villagerplus.village.Villages.villageNear(level, payload.board(), 96);
			if (ref != null && applyQuota(ref, payload.job(), payload.delta())) {
				de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(RenameVillage.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.isSpectator() || !(player.level() instanceof ServerLevel level) || player.blockPosition().distSqr(payload.board()) > 24 * 24
				|| !(level.getBlockState(payload.board()).getBlock() instanceof de.villagerplus.block.DorftafelBlock)) {
				return;
			}
			var ref = de.villagerplus.village.Villages.villageNear(level, payload.board(), 96);
			if (ref != null) {
				var renamed = de.villagerplus.village.Network.rename(level, ref, payload.name());
				if (renamed.isPresent()) {
					VillageMessages.system(player, net.minecraft.network.chat.Component.literal("Das Dorf heißt jetzt „" + renamed.get() + "“."));
				} else {
					VillageMessages.system(player, net.minecraft.network.chat.Component.literal(
						"Ungültiger Dorfname. Erlaubt sind 1–" + de.villagerplus.village.Network.MAX_MANUAL_VILLAGE_NAME_CODE_POINTS
							+ " Unicode-Zeichen ohne Steuer- oder Formatzeichen."));
				}
				de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(CancelTask.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.isSpectator() || !(player.level() instanceof ServerLevel level) || player.blockPosition().distSqr(payload.board()) > 24 * 24
				|| !(level.getBlockState(payload.board()).getBlock() instanceof de.villagerplus.block.DorftafelBlock)) {
				return;
			}
			var ref = de.villagerplus.village.Villages.villageNear(level, payload.board(), 96);
			if (ref == null) {
				return;
			}
			if (payload.kind().equals("build")) {
				if (ref.data().hasPlan()) {
					String what = ref.data().planType;
					de.villagerplus.build.Builder.cancel(ref, level.getGameTime());
					de.villagerplus.village.Journal.add(level, ref, "Bauen", player.getName().getString(), "Bauauftrag abgebrochen: " + what, 0);
					VillageMessages.system(player, net.minecraft.network.chat.Component.literal("Bauauftrag abgebrochen: " + what + ". Das Dorf plant frühestens in einer Minute neu."));
				}
			} else if (payload.kind().equals("villager")) {
				net.minecraft.world.entity.Entity e = level.getEntity(payload.villager());
				if (e instanceof Villager v && v.isAlive() && v.blockPosition().distSqr(payload.board()) <= 96 * 96) {
					var st = de.villagerplus.job.JobManager.state(v);
					st.clearTarget();
					st.cooldownUntil = level.getGameTime() + 600;
					st.idleUntil[0] = level.getGameTime() + 600;
					st.idleUntil[1] = level.getGameTime() + 600;
					st.mineCheckAt = level.getGameTime() + 600;
					st.buildCheckAt = level.getGameTime() + 600;
					v.getNavigation().stop();
					v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
					String name = v.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY).name;
					VillageMessages.system(player, net.minecraft.network.chat.Component.literal((name.isEmpty() ? "Der Dorfbewohner" : name) + " bricht die Aufgabe ab und sucht sich eine neue."));
				}
			}
			de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
		});
		ServerPlayNetworking.registerGlobalReceiver(NetworkAction.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
			boolean sameLevel = level != null && player.level() == level;
			boolean boardLoaded = sameLevel && level.hasChunkAt(payload.board());
			boolean inReach = sameLevel && player.blockPosition().distSqr(payload.board()) <= 24L * 24L;
			boolean isBoard = boardLoaded && level.getBlockState(payload.board()).getBlock() instanceof de.villagerplus.block.DorftafelBlock;
			String admissionFailure = networkActionAdmissionFailure(player.isSpectator(), sameLevel, boardLoaded, inReach, isBoard);
			de.villagerplus.village.Network.ActionOutcome outcome;
			if (payload.requestId() <= 0) {
				outcome = new de.villagerplus.village.Network.ActionOutcome(false, "Ungültige Aktionsanfrage. Bitte Dorftafel aktualisieren.");
			} else if (admissionFailure != null) {
				outcome = new de.villagerplus.village.Network.ActionOutcome(false, admissionFailure);
			} else {
				outcome = de.villagerplus.village.Network.act(level, player, payload.board(), payload.action(), payload.index(),
					payload.targetDimension(), payload.targetBell());
			}
			ServerPlayNetworking.send(player, new NetworkActionResult(payload.board(), payload.requestId(), outcome.success(), outcome.message()));
			if (admissionFailure == null && payload.requestId() > 0) {
				de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(AssignJob.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.isSpectator() || !(player.level() instanceof ServerLevel level) || player.blockPosition().distSqr(payload.board()) > 24 * 24
				|| !(level.getBlockState(payload.board()).getBlock() instanceof de.villagerplus.block.DorftafelBlock)) {
				return;
			}
			var boardVillage = de.villagerplus.village.Villages.villageNear(level, payload.board(), 96);
			if (boardVillage == null) return;
			net.minecraft.world.entity.Entity e = level.getEntity(payload.villager());
			net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(payload.profession());
			if (!(e instanceof Villager v) || !v.isAlive() || id == null || v.blockPosition().distSqr(payload.board()) > 96 * 96) {
				return;
			}
			var villagerVillage = de.villagerplus.village.Villages.find(level, v);
			if (villagerVillage == null || !villagerVillage.bell().equals(boardVillage.bell())) return;
			var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION, id);
			if (level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION).get(key).isEmpty()) {
				return;
			}
			if (v.isBaby()) {
				de.villagerplus.job.Apprentices.plan(v, id.toString());
				var apprenticeship = de.villagerplus.job.Apprentices.get(v);
				String name = v.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY).name;
				VillageMessages.system(player, net.minecraft.network.chat.Component.literal((name.isEmpty() ? "Das Kind" : name) + (id.getPath().equals("none")
					? " hat keinen Lehrlingsberuf mehr." : " lernt künftig " + de.villagerplus.job.Apprentices.professionName(id.toString()) + " (" + apprenticeship.lessonXp() + " XP).")));
				de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
				return;
			}
			de.villagerplus.job.Apprentices.clear(v);
			// Neuer Beruf: alter Arbeitsplatz wird frei, Handel startet neu; Stufe 2, damit er den Beruf auch ohne Arbeitsblock behaelt.
			v.releasePoi(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
			v.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
			boolean none = id.getPath().equals("none");
			v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), key).withLevel(none ? 1 : 2));
			v.setVillagerXp(none ? 0 : net.minecraft.world.entity.npc.villager.VillagerData.getMinXpPerLevel(2));
			v.setOffers(null);
			v.refreshBrain(level);
			String name = v.getAttachedOrCreate(de.villagerplus.VillagerPlus.MEMORY).name;
			VillageMessages.system(player, net.minecraft.network.chat.Component.literal((name.isEmpty() ? "Der Dorfbewohner" : name) + " arbeitet jetzt als "
				+ de.villagerplus.village.Names.profession(v.getVillagerData().profession()) + "."));
			de.villagerplus.block.DorftafelBlock.open(level, player, payload.board());
		});
		PayloadTypeRegistry.clientboundPlay().register(BoardData.TYPE, BoardData.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(BoardRequest.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!(player.level() instanceof ServerLevel level) || player.blockPosition().distSqr(payload.pos()) >= 24 * 24
				|| !level.hasChunkAt(payload.pos())
				|| !(level.getBlockState(payload.pos()).getBlock() instanceof de.villagerplus.block.DorftafelBlock)) return;
			if (payload.talkResidentId().isBlank()) {
				de.villagerplus.block.DorftafelBlock.open(level, player, payload.pos());
				return;
			}
			// A Talk request sees only already loaded, uniquely assigned non-guest residents. It never resolves or loads an entity/chunk by UUID.
			var ref = de.villagerplus.village.Villages.villageNear(level, payload.pos(), 96);
			if (ref == null) return;
			ServerPlayNetworking.send(player, BoardData.build(level, ref, payload.pos(), payload.talkResidentId(), payload.talkRequestId()));
		});

		ServerPlayNetworking.registerGlobalReceiver(InfoRequest.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (!(player.level() instanceof ServerLevel level)) {
				return;
			}
			// Der Villager, mit dem der Spieler gerade handelt.
			List<Villager> partners = level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(10.0),
				v -> v.isAlive() && v.getTradingPlayer() == player);
			if (!partners.isEmpty()) {
				ServerPlayNetworking.send(player, new InfoResponse(payload.requestId(), VillagerInfo.lines(level, partners.get(0), player)));
				return;
			}
			List<CaravanTraderEntity> caravanTraders = level.getEntitiesOfClass(CaravanTraderEntity.class,
				player.getBoundingBox().inflate(10.0), trader -> trader.isAlive() && trader.getTradingPlayer() == player);
			if (!caravanTraders.isEmpty()) {
				ServerPlayNetworking.send(player, new InfoResponse(payload.requestId(), caravanTraders.get(0).infoLines()));
			}
		});
	}

	/** Shared admission policy: invalid clicks are rejected before any village or inventory lookup. */
	public static String networkActionAdmissionFailure(boolean spectator, boolean sameLevel, boolean boardLoaded,
		boolean inReach, boolean isBoard) {
		if (spectator) return "Diese Dorftafelaktion ist im Zuschauermodus nicht verfügbar.";
		if (!sameLevel || !boardLoaded) return "Die Dorftafel ist gerade nicht geladen. Öffne sie erneut.";
		if (!inReach) return "Du bist zu weit von der Dorftafel entfernt. Geh näher heran.";
		if (!isBoard) return "An dieser Position steht keine Dorftafel mehr. Öffne die richtige Tafel erneut.";
		return null;
	}

	/** 41..81 encode absolute 0..40 slider targets; -40..40 retain the older delta protocol. */
	public static boolean applyQuota(de.villagerplus.village.VillageRef ref, String job, int encoded) {
		if (ref == null || job == null || job.length() > 24) return false;
		if (encoded >= 41 && encoded <= 81) return de.villagerplus.village.Staffing.setQuota(ref, job, encoded - 41);
		if (encoded < -40 || encoded > 40 || de.villagerplus.village.Staffing.quotaPercent(ref, job) < 0) return false;
		de.villagerplus.village.Staffing.changeQuota(ref, job, encoded);
		return true;
	}
}
