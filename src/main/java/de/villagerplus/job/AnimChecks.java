package de.villagerplus.job;

import de.villagerplus.VillagerPlus;
import de.villagerplus.VillagerMemory;
import de.villagerplus.village.VillageRef;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import java.io.InputStream;
import java.util.List;

/** Deterministic animation checks for the explicitly gated development runner; no real-time waiting. */
public final class AnimChecks {
	private AnimChecks() {}

	public static int run(ServerLevel level, VillageRef ref) {
		int checks = 0;
		check(E04Role.fromProfessionId(Identifier.parse("morevillagers:merchant")) == E04Role.TRADER,
			"merchant profession selects trader outfit"); checks++;
		check(E04Role.fromProfessionId(Identifier.parse("morevillagers:guard")) == E04Role.GUARD,
			"guard profession selects guard outfit"); checks++;
		check(E04Role.fromProfessionId(Identifier.parse("villagerplus:rail_builder")) == E04Role.ENGINEER,
			"rail builder profession selects engineer outfit"); checks++;
		check(E04Role.fromProfessionId(Identifier.parse("morevillagers:adventurer")) == E04Role.ADVENTURER,
			"adventurer profession selects adventurer outfit"); checks++;
		check(E04Role.fromProfessionId(Identifier.parse("minecraft:farmer")) == E04Role.NONE,
			"ordinary profession keeps its vanilla appearance"); checks++;
		for (E04Role role : List.of(E04Role.TRADER, E04Role.GUARD, E04Role.ENGINEER, E04Role.ADVENTURER)) {
			String resource = "/assets/" + VillagerPlus.MOD_ID + "/textures/entity/roles/" + role.textureName() + ".png";
			try (InputStream texture = AnimChecks.class.getResourceAsStream(resource)) {
				check(texture != null, "role texture exists: " + role.textureName());
				if (texture != null) {
					var image = javax.imageio.ImageIO.read(texture);
					check(image != null && image.getWidth() == 64 && image.getHeight() == 64,
						"role texture is a readable 64x64 villager skin: " + role.textureName()); checks++;
				}
			}
			catch (java.io.IOException exception) {
				throw new IllegalStateException("ANIMATION CHECK FAILED: cannot read " + resource, exception);
			}
			checks++;
		}

		BlockPos first = ref.bell().west(14).above(4);
		BlockPos second = first.north();
		level.getChunkAt(first);
		level.getChunkAt(second);
		check(level.hasChunkAt(first) && level.hasChunkAt(second), "animation fixture cells are loaded before snapshot"); checks++;
		var oldFirst = level.getBlockState(first);
		var oldSecond = level.getBlockState(second);
		check(level.getBlockEntity(first) == null && level.getBlockEntity(second) == null,
			"animation fixture cells have no block entities before temporary stone placement"); checks++;
		var villagerType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(
			net.minecraft.resources.Identifier.parse("minecraft:villager"));
		Villager v = (Villager) villagerType.spawn(level, first.east(2), EntitySpawnReason.COMMAND);
		if (v == null) throw new IllegalStateException("ANIMATION CHECK FAILED: cannot spawn fixture");
		Donkey freightDonkey = null;
		v.addTag("vp090_fixture");
		v.setNoAi(true);
		JobState st = new JobState();
		st.activity = "animation fixture";
		long now = level.getGameTime();
		try {
			check(Anim.role(v) == E04Role.NONE, "server reads role from authoritative villager profession"); checks++;
			JobState handState = new JobState();
			Anim.hold(context(level, ref, v, handState, now + 100), Items.IRON_PICKAXE);
			v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD, 4));
			Anim.apply(v, handState, now + 100);
			check(v.getMainHandItem().is(Items.IRON_PICKAXE) && handState.holding,
				"server equips the current work tool for the client-held item layer"); checks++;
			Anim.apply(v, handState, now + 151);
			check(v.getMainHandItem().is(Items.EMERALD) && v.getMainHandItem().getCount() == 4 && !handState.holding,
				"expired work tool restores the previous main-hand stack and count"); checks++;
			JobState changedHandState = new JobState();
			v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD, 4));
			Anim.hold(context(level, ref, v, changedHandState, now + 160), Items.IRON_PICKAXE);
			Anim.apply(v, changedHandState, now + 160);
			v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 2));
			Anim.apply(v, changedHandState, now + 211);
			check(v.getMainHandItem().is(Items.DIAMOND) && v.getMainHandItem().getCount() == 2 && !changedHandState.holding,
				"expired visual equipment does not overwrite a newer real hand-slot change"); checks++;
			v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
			freightDonkey = EntityTypes.DONKEY.create(level, EntitySpawnReason.COMMAND);
			if (freightDonkey == null) throw new IllegalStateException("ANIMATION CHECK FAILED: cannot spawn freight donkey fixture");
			freightDonkey.setPos(first.getX() + 4.5, first.getY(), first.getZ() + 0.5);
			freightDonkey.setNoAi(true);
			check(level.addFreshEntity(freightDonkey), "freight donkey fixture is registered in the loaded world"); checks++;
			freightDonkey.setTamed(true);
			var chestLayout = freightDonkey.getSlot(AbstractHorse.CHEST_SLOT_OFFSET);
			check(chestLayout != null && chestLayout.set(new ItemStack(Items.CHEST)) && freightDonkey.hasChest(),
				"freight fixture is a real tamed chest donkey"); checks++;
			var freightAccess = freightDonkey.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + 2);
			check(freightAccess != null && freightAccess.set(new ItemStack(Items.CARROT, 12)),
				"real chest cargo slot accepts the twelve-carrot freight stack"); checks++;
			freightDonkey.setLeashedTo(v, true);
			JobState freightState = new JobState();
			Anim.apply(v, freightState, now + 200);
			check(v.getMainHandItem().isEmpty() && !freightState.holding,
				"ordinary leashed donkey cargo is not mistaken for caravan freight"); checks++;
			freightDonkey.addTag("vp_caravan_bell:" + ref.bell().asLong());
			v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BREAD, 2));
			Anim.apply(v, freightState, now + 201);
			check(v.getMainHandItem().is(Items.CARROT) && v.getMainHandItem().getCount() == 1
				&& freightAccess != null && freightAccess.get().getCount() == 12,
				"porter visibly holds one visual copy of the exact real caravan freight without consuming it"); checks++;
			freightAccess.set(ItemStack.EMPTY);
			Anim.apply(v, freightState, now + 202);
			check(v.getMainHandItem().is(Items.BREAD) && v.getMainHandItem().getCount() == 2 && !freightState.holding,
				"porter restores previous equipment when visible donkey freight is delivered"); checks++;
			v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);

			JobState cargoState = new JobState();
			v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BREAD, 3));
			ItemStack realCargo = BuildHaul.markForCheck(new ItemStack(Items.OAK_PLANKS, 5),
				context(level, ref, v, cargoState, now + 203), first, first.west());
			v.getInventory().setItem(0, realCargo);
			Anim.apply(v, cargoState, now + 203);
			check(v.getMainHandItem().is(Items.OAK_PLANKS) && v.getMainHandItem().getCount() == 1
				&& v.getInventory().getItem(0).getCount() == 5,
				"only one visual item is shown while the marked construction cargo remains physically carried"); checks++;
			Anim.forgetHeldHandRuntimeForCheck(v);
			var registryOps = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
			var savedMemory = VillagerMemory.CODEC.encodeStart(registryOps, v.getAttachedOrCreate(VillagerPlus.MEMORY)).getOrThrow();
			VillagerMemory reloadedMemory = VillagerMemory.CODEC.parse(registryOps, savedMemory).getOrThrow();
			v.setAttached(VillagerPlus.MEMORY, reloadedMemory);
			JobState restoredCargoState = new JobState();
			Anim.apply(v, restoredCargoState, now + 204);
			check(v.getMainHandItem().is(Items.OAK_PLANKS) && v.getMainHandItem().getCount() == 1
				&& v.getInventory().getItem(0).getCount() == 5,
				"saved hand provenance survives a runtime-state restart without changing real cargo"); checks++;
			v.getInventory().clearContent();
			Anim.apply(v, restoredCargoState, now + 205);
			check(v.getMainHandItem().is(Items.BREAD) && v.getMainHandItem().getCount() == 3 && !restoredCargoState.holding,
				"delivered or returned construction cargo restores the carrier's previous hand"); checks++;
			v.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);

			level.setBlock(first, Blocks.STONE.defaultBlockState(), 3);
			level.setBlock(second, Blocks.STONE.defaultBlockState(), 3);
			var stone = level.getBlockState(first);
			for (int stage = 0; stage <= 9; stage++) {
				Ctx c = context(level, ref, v, st, now + stage * 6L);
				check(!Anim.breakStep(c, first, stone, Items.IRON_PICKAXE) && Anim.breakingStage(v) == stage,
					"separate stroke broadcasts crack stage " + stage); checks++;
				check(!Anim.breakStep(c, first, stone, Items.IRON_PICKAXE) && Anim.breakingStage(v) == stage,
					"same-tick duplicate cannot advance stage " + stage); checks++;
			}
			check(v.getMainHandItem().is(Items.IRON_PICKAXE), "tool is equipped before animation"); checks++;
			check(Anim.breakStep(context(level, ref, v, st, now + 60), first, stone, Items.IRON_PICKAXE)
				&& Anim.breakingStage(v) == -1, "completion follows visible stage nine and clears progress"); checks++;
			check(level.getBlockState(first) == stone, "animation does not mutate or duplicate block drops"); checks++;
			Anim.breakStep(context(level, ref, v, st, now + 66), first, stone, Items.IRON_PICKAXE);
			Anim.cancelBreaking(v);
			check(Anim.breakingStage(v) == -1, "explicit abort clears progress"); checks++;
			Anim.breakStep(context(level, ref, v, st, now + 72), first, stone, Items.IRON_PICKAXE);
			Anim.breakStep(context(level, ref, v, st, now + 78), first, stone, Items.IRON_PICKAXE);
			Anim.breakStep(context(level, ref, v, st, now + 84), second, stone, Items.IRON_PICKAXE);
			check(Anim.breakingStage(v) == 0, "retarget starts a fresh crack sequence"); checks++;
			level.setBlock(second, Blocks.AIR.defaultBlockState(), 3);
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "externally removed target clears progress"); checks++;
			Anim.breakStep(context(level, ref, v, st, now), first, stone, Items.IRON_PICKAXE);
			st.cooldownUntil = now + 600;
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "task-cancel cooldown clears progress"); checks++;
			st.cooldownUntil = 0;
			Anim.breakStep(context(level, ref, v, st, now - 101), first, stone, Items.IRON_PICKAXE);
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "abandoned work expires without a later job call"); checks++;
			Anim.breakStep(context(level, ref, v, st, now), first, stone, Items.IRON_PICKAXE);
			st.activity = "another task";
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "activity switch clears progress"); checks++;
			Anim.breakStep(context(level, ref, v, st, now), first, stone, Items.IRON_PICKAXE);
			v.discard();
			Anim.maintainBreaking(level);
			check(Anim.breakingStage(v) == -1, "entity removal clears original breaker progress"); checks++;
			return checks;
		} finally {
			Anim.cancelBreaking(v);
			if (freightDonkey != null) freightDonkey.discard();
			v.discard();
			level.setBlock(first, oldFirst, 3);
			level.setBlock(second, oldSecond, 3);
		}
	}

	private static Ctx context(ServerLevel level, VillageRef ref, Villager v, JobState st, long now) {
		return new Ctx(level, v, st, v.getAttachedOrCreate(VillagerPlus.MEMORY), ref, now);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("ANIMATION CHECK FAILED: " + message);
	}
}
