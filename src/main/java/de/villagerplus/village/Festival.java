package de.villagerplus.village;

import de.villagerplus.Config;
import de.villagerplus.job.Anim;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Meals;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * Dorffest: Alle {@code Config.FESTIVAL_PERIOD_DAYS} Spieltage feiert ein Dorf mit mindestens {@code Config.FESTIVAL_MIN_POP}
 * Einwohnern am Abend an der Glocke. Statt nach Hause zu gehen, kommen alle zusammen (Soziales wird voll), bekommen ein Festmahl
 * aus der Dorfkiste und feiern mit Partikeln und Glockenklang. Der Countdown steht auf der Dorftafel.
 */
public final class Festival {
	/** Je Dorf: {Tag der letzten Pruefung, Fest ja/nein, Fest angekuendigt ja/nein}. */
	private static final Map<Long, long[]> STATE = new HashMap<>();

	private Festival() {
	}

	private static long day(ServerLevel level) {
		return level.getOverworldClockTime() / 24000L;
	}

	private static long[] state(ServerLevel level, VillageRef ref) {
		long today = day(level);
		long[] s = STATE.get(ref.bell().asLong());
		if (s == null || s[0] != today) {
			boolean due = today % Config.FESTIVAL_PERIOD_DAYS == Config.FESTIVAL_PERIOD_DAYS - 1;
			boolean enough = false;
			if (due) {
				int pop = Villages.residents(level, ref, Config.VILLAGE_RADIUS, v -> !Guests.isGuest(v) && !Expedition.isAway(v)).size();
				enough = pop >= Config.FESTIVAL_MIN_POP;
			}
			s = new long[] {today, due && enough ? 1 : 0, 0};
			STATE.put(ref.bell().asLong(), s);
		}
		return s;
	}

	/** Findet heute abend ein Fest statt? */
	public static boolean isOn(ServerLevel level, VillageRef ref) {
		return state(level, ref)[1] == 1;
	}

	/** Tage bis zum naechsten Fest (0 = heute). */
	public static long daysUntil(ServerLevel level) {
		long period = Config.FESTIVAL_PERIOD_DAYS;
		return Math.floorMod(period - 1 - day(level) % period, period);
	}

	/** Ein Villager feiert mit (true = beschaeftigt). */
	public static boolean attend(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null) {
			return false;
		}
		ServerLevel level = c.level();
		long[] s = state(level, ref);
		if (s[2] == 0) {
			s[2] = 1;
			JobUtil.tellNearbyPlayers(level, c.v(), 64, Component.translatable("message.villagerplus.festival"));
			level.playSound(null, ref.bell(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, 1.0F);
		}
		c.setActivity("feiert beim Dorffest");
		// Treffpunkt ist die Dorfkiste (die Glocke haengt in manchen Dorfern auf einem unerreichbaren Turm).
		BlockPos chestAt = ref.chestPos(level);
		BlockPos bell = chestAt != null ? chestAt : ref.bell();
		if (!JobUtil.approach(c, bell, 5.0)) {
			return true;
		}
		c.mem().lastChat = c.now();
		Villager v = c.v();
		// Festmahl: wer lange nichts gegessen hat, bekommt etwas aus der Dorfkiste.
		if (c.now() - c.mem().lastMeal > 6000L) {
			Container box = ref.chest(level);
			if (box != null && JobUtil.take(box, Meals::isFood, 1) > 0) {
				c.mem().lastMeal = c.now();
				Anim.eatParticles(level, v, net.minecraft.world.item.Items.BREAD);
			}
		}
		if (v.getRandom().nextInt(3) == 0) {
			level.sendParticles(ParticleTypes.NOTE, v.getX(), v.getY() + 2.2, v.getZ(), 1, 0.2, 0.1, 0.2, 1.0);
			JobUtil.swing(v);
		}
		if (v.getRandom().nextInt(40) == 0) {
			level.sendParticles(ParticleTypes.FIREWORK, bell.getX() + 0.5, bell.getY() + 4.0, bell.getZ() + 0.5, 30, 2.0, 1.5, 2.0, 0.08);
			level.playSound(null, bell, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.BLOCKS, 1.0F, 1.0F);
		}
		return true;
	}
}
