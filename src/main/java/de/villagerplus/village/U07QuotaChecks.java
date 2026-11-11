package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Apprentices;
import de.villagerplus.net.Networking;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Isolated server fixtures for quota packet targets, persistence, staffing, and apprenticeship. */
public final class U07QuotaChecks {
 private static final Set<Long> OWNED_TICKETS = ConcurrentHashMap.newKeySet();
 private U07QuotaChecks() { }
 public static int run(ServerLevel level, VillageRef source) {
  BlockPos column = source.bell().offset(400, 0, 400);
  int chunkX = Math.floorDiv(column.getX(), 16);
  int chunkZ = Math.floorDiv(column.getZ(), 16);
  long chunkKey = ChunkPos.pack(chunkX, chunkZ);
  if (!level.getChunkSource().getForceLoadedChunks().contains(chunkKey)) {
   if (!level.setChunkForced(chunkX, chunkZ, true)) throw new IllegalStateException("U07 could not force-load its isolated entity fixture chunk");
   OWNED_TICKETS.add(chunkKey);
  }
  // FORCED tickets load the chunk asynchronously. A first command invocation prepares it;
  // retry after a few normal server ticks so the real entity query sees the residents.
  if (!level.areEntitiesLoaded(chunkKey) || !level.isPositionEntityTicking(column)) return -1;
  boolean releaseTicket = OWNED_TICKETS.remove(chunkKey);
  try {
  level.getChunkAt(column);
  BlockPos bell = new BlockPos(column.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), column.getZ());
  var old = level.getBlockState(bell);
  if (!old.isAir() || level.getBlockEntity(bell) != null) throw new IllegalStateException("U07 fixture bell must be empty");
  List<Villager> villagers = new ArrayList<>();
  int checks = 0;
  try {
   level.setBlock(bell, Blocks.BELL.defaultBlockState(), 3);
   var be = level.getBlockEntity(bell);
   if (be == null) throw new IllegalStateException("U07 fixture bell missing");
   VillageData data = new VillageData();
   be.setAttached(VillagerPlus.VILLAGE, data);
   VillageRef ref = new VillageRef(bell, data, be);
   Staffing.clearTickForChecks(ref);
   for (int i = 0; i < 3; i++) {
    Villager v = EntityTypes.VILLAGER.spawn(level, bell.offset(i + 1, 0, 0), EntitySpawnReason.COMMAND);
    if (v == null) throw new IllegalStateException("U07 villager spawn failed");
    villagers.add(v); v.setNoAi(true);
    v.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), bell));
   }
   List<Villager> visibleResidents = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS,
    v -> !Guests.isGuest(v) && Market.shop(ref, v.getUUID()) == null);
   check(visibleResidents.size() == 3 && visibleResidents.containsAll(villagers),
    "staffing fixture exposes all three spawned villagers to the real resident query (found " + visibleResidents.size() + ")"); checks++;
   check(Staffing.quotaPercent(ref, "mason") == 12, "default mason target rises to 12 percent for growing villages"); checks++;
   check(Staffing.quotaTarget(12, 12) == 1 && Staffing.quotaTarget(12, 13) == 2
    && Staffing.quotaTarget(12, 21) == 3 && Staffing.quotaTarget(0, 21) == 0,
    "12 percent mason staffing yields one up to 12 residents, two from 13, three from 21, and zero when disabled"); checks++;
   check(Staffing.setQuota(ref, "mason", 7) && Staffing.quotaPercent(ref, "mason") == 7
    && Staffing.setQuota(ref, "mason", 12) && Staffing.quotaPercent(ref, "mason") == 12,
    "an explicit mason quota overrides the new default and can be set back to 12 percent"); checks++;
   check(Networking.applyQuota(ref, "farmer", 81) && Staffing.quotaPercent(ref, "farmer") == 40, "upper absolute target"); checks++;
   check(Networking.applyQuota(ref, "farmer", 41) && Staffing.quotaPercent(ref, "farmer") == 0, "lower absolute target"); checks++;
   check(!Networking.applyQuota(ref, "farmer", 82) && !Networking.applyQuota(ref, "farmer", -41)
    && !Networking.applyQuota(ref, "invalid", 41) && Staffing.quotaPercent(ref, "farmer") == 0, "invalid packets do not change stored quota"); checks++;
   check(Networking.applyQuota(ref, "mason", 41) && Networking.applyQuota(ref, "fletcher", 41), "zero critical quotas accepted"); checks++;
   var codec = Codec.unboundedMap(Codec.STRING, Codec.INT);
   Map<String, Integer> stored = be.getAttached(VillagerPlus.QUOTAS);
   Map<String, Integer> loaded = codec.parse(JsonOps.INSTANCE, codec.encodeStart(JsonOps.INSTANCE, stored).getOrThrow()).getOrThrow();
   be.setAttached(VillagerPlus.QUOTAS, loaded);
   check(loaded.equals(stored) && Staffing.quotaPercent(ref, "farmer") == 0, "stored quotas round-trip through attachment codec"); checks++;
   Staffing.tick(level, ref, 1000000L);
   check(villagers.stream().allMatch(v -> v.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.NONE)), "zero prevents real critical staffing tick"); checks++;
   check(Networking.applyQuota(ref, "farmer", 81), "farmer quota can be restored"); checks++;
   Staffing.tick(level, ref, 1000400L);
   check(villagers.stream().anyMatch(v -> "farmer".equals(de.villagerplus.Compat.jobKey(v.getVillagerData().profession()))), "positive quota enables real farmer assignment"); checks++;
   Villager child = villagers.get(0); child.setAge(-24000);
   Apprentices.plan(child, "minecraft:mason");
   var restored = Apprentices.Progress.CODEC.parse(JsonOps.INSTANCE, Apprentices.Progress.CODEC.encodeStart(JsonOps.INSTANCE, Apprentices.get(child)).getOrThrow()).getOrThrow();
   child.setAttached(VillagerPlus.APPRENTICE, restored); child.setAge(0);
   check(Apprentices.completeIfAdult(level, child) && child.getVillagerData().profession().is(net.minecraft.world.entity.npc.villager.VillagerProfession.MASON), "saved child target becomes adult profession"); checks++;

   for (Villager villager : villagers) { villager.setAge(-24000); Apprentices.clear(villager); }
   check(Networking.applyQuota(ref, "apprentice", 41) && Staffing.quotaPercent(ref, "apprentice") == 0,
    "zero apprentice target is accepted"); checks++;
   Staffing.clearTickForChecks(ref);
   Staffing.tick(level, ref, 1000800L);
   check(villagers.stream().noneMatch(v -> !Apprentices.get(v).target().isBlank()),
    "zero apprentice target does not assign children on a normal staffing tick"); checks++;
   check(Networking.applyQuota(ref, "apprentice", 61), "positive apprentice target is accepted"); checks++;
   Staffing.clearTickForChecks(ref);
   Staffing.tick(level, ref, 1001200L);
   check(villagers.stream().filter(v -> !Apprentices.get(v).target().isBlank()).count() == 1,
    "20 percent target assigns one of three children"); checks++;
   Villager manualChild = villagers.stream().filter(Apprentices::quotaAssigned).findFirst().orElse(null);
   check(manualChild != null, "automatic apprentice target is marked as quota-managed"); checks++;
   if (manualChild == null) throw new IllegalStateException("U07 automatic apprentice assignment is missing");
   var automaticProgress = Apprentices.Progress.CODEC.parse(JsonOps.INSTANCE,
    Apprentices.Progress.CODEC.encodeStart(JsonOps.INSTANCE, Apprentices.get(manualChild)).getOrThrow()).getOrThrow();
   manualChild.setAttached(VillagerPlus.APPRENTICE, automaticProgress);
   check(Apprentices.quotaAssigned(manualChild), "quota-managed target survives its attachment codec round trip"); checks++;
   Villager optedOutChild = villagers.stream().filter(v -> v != manualChild).findFirst().orElseThrow();
   Apprentices.plan(manualChild, "minecraft:mason");
   Apprentices.plan(optedOutChild, "minecraft:none");
   var optOutProgress = Apprentices.Progress.CODEC.parse(JsonOps.INSTANCE,
    Apprentices.Progress.CODEC.encodeStart(JsonOps.INSTANCE, Apprentices.get(optedOutChild)).getOrThrow()).getOrThrow();
   optedOutChild.setAttached(VillagerPlus.APPRENTICE, optOutProgress);
   check(!Apprentices.quotaAssigned(manualChild) && !Apprentices.get(manualChild).quotaOptOut()
    && Apprentices.get(manualChild).target().equals("minecraft:mason") && Apprentices.quotaOptOut(optedOutChild),
    "manual profession choice and opt-out persist separately from quota plans"); checks++;
   var legacyProgress = Apprentices.Progress.CODEC.parse(JsonOps.INSTANCE,
    com.google.gson.JsonParser.parseString("{\"target\":\"minecraft:farmer\",\"lesson_xp\":5}")).getOrThrow();
   check(!legacyProgress.quotaAssigned() && !legacyProgress.quotaOptOut(),
    "legacy apprenticeship progress defaults to a protected manual target"); checks++;

   check(Networking.applyQuota(ref, "apprentice", 81), "apprentice target can be raised"); checks++;
   var bellSnapshot = be.saveWithFullMetadata(level.registryAccess());
   level.removeBlockEntity(bell);
   BlockEntity restoredBell = BlockEntity.loadStatic(bell, level.getBlockState(bell), bellSnapshot.copy(), level.registryAccess());
   if (restoredBell == null) throw new IllegalStateException("U07 bell restore failed");
   level.setBlockEntity(restoredBell);
   VillageData restoredData = restoredBell.getAttached(VillagerPlus.VILLAGE);
   if (restoredData == null) throw new IllegalStateException("U07 village attachment did not survive bell restore");
   VillageRef restoredRef = new VillageRef(bell, restoredData, restoredBell);
   check(Staffing.quotaPercent(restoredRef, "apprentice") == 40, "apprentice target survives bell save and restore"); checks++;
   Staffing.clearTickForChecks(restoredRef);
   Staffing.tick(level, restoredRef, 1001600L);
   check(villagers.stream().filter(v -> !Apprentices.get(v).target().isBlank()).count() == 2
    && Apprentices.get(manualChild).target().equals("minecraft:mason") && !Apprentices.quotaAssigned(manualChild)
    && Apprentices.quotaOptOut(optedOutChild) && Apprentices.get(optedOutChild).target().isBlank()
    && villagers.stream().filter(Apprentices::quotaAssigned).count() == 1,
    "restored 40 percent target assigns only the available child, preserving manual choice and opt-out"); checks++;
   Staffing.clearTickForChecks(restoredRef);
   Staffing.tick(level, restoredRef, 1002000L);
   check(villagers.stream().filter(v -> !Apprentices.get(v).target().isBlank()).count() == 2,
    "repeated staffing tick does not exceed the apprentice target"); checks++;
   check(Networking.applyQuota(restoredRef, "apprentice", 61), "apprentice target can be reduced"); checks++;
   Staffing.clearTickForChecks(restoredRef);
   Staffing.tick(level, restoredRef, 1002400L);
   check(villagers.stream().filter(v -> !Apprentices.get(v).target().isBlank()).count() == 1
    && Apprentices.get(manualChild).target().equals("minecraft:mason")
    && Apprentices.quotaOptOut(optedOutChild)
    && villagers.stream().noneMatch(Apprentices::quotaAssigned),
    "lower quota clears only automatic plans and retains manual choice and opt-out"); checks++;
   return checks;
  } finally {
   for (Villager v : villagers) { Villages.forget(v.getUUID()); v.discard(); }
   var fixtureEntity = level.getBlockEntity(bell);
   if (fixtureEntity != null) Staffing.clearTickForChecks(new VillageRef(bell, new VillageData(), fixtureEntity));
   level.setBlock(bell, old, 3);
  }
  } finally {
   if (releaseTicket) level.setChunkForced(chunkX, chunkZ, false);
  }
 }
 private static void check(boolean condition, String message) {
  if (!condition) throw new IllegalStateException("U07 check failed: " + message);
 }
}
