package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Expedition;
import de.villagerplus.village.Guests;
import de.villagerplus.village.RailTransport;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Pausen: Dorfbewohner suchen sich einen Gespraechspartner, stellen sich zusammen, unterhalten sich und tauschen Klatsch aus. */
public final class Social {
	private Social() {
	}

	/** true = gerade im Gespraech oder auf dem Weg dorthin. */
	public static boolean tick(Ctx c) {
		JobState st = c.st();
		Villager v = c.v();
		long now = c.now();

		Villager partner = st.chatPartner;
		if (partner != null && (!partner.isAlive() || partner.isSleeping() || partner.level() != v.level())) {
			end(st, v);
			partner = null;
		}

		if (partner != null) {
			c.setActivity("plaudert mit " + nameOf(partner));
			JobState partnerState = JobManager.stateIfKnown(partner);
			boolean mutualPair = partnerState != null && partnerState.chatPartner == v;
			if (!mutualPair) {
				// A half-pair can only leave one stand reserved; release it and retry through the ordinary search.
				end(st, v);
				return false;
			}
			JobState initiatorState = st.chatInitiator ? st : partnerState;
			JobState recipientState = st.chatInitiator ? partnerState : st;
			SocialChecks.MeetingSpots meeting = SocialChecks.retainedMeetingSpots(
				initiatorState.chatMeetingSpot, recipientState.chatMeetingSpot);
			if (meeting == null) {
				Villager initiator = st.chatInitiator ? v : partner;
				Villager recipient = st.chatInitiator ? partner : v;
				meeting = chooseMeetingSpots(initiator, recipient, st.chatUntil);
				if (meeting == null) {
					end(st, v);
					return false;
				}
				initiatorState.chatMeetingSpot = meeting.initiator();
				recipientState.chatMeetingSpot = meeting.recipient();
			}
			BlockPos ownMeetingBlock = st.chatInitiator ? meeting.initiator() : meeting.recipient();
			Vec3 meetingSpot = Vec3.atBottomCenterOf(ownMeetingBlock);
			boolean close = SocialChecks.atMeetingSpot(v.position().distanceToSqr(meetingSpot));
			if (!close) {
				if (now <= st.chatUntil + SocialChecks.CHAT_WALK_GRACE_TICKS) {
					JobUtil.approach(c, meetingSpot, SocialChecks.CHAT_MEETING_REACH);
				} else if (SocialChecks.walkExpired(now, st.chatUntil + SocialChecks.CHAT_WALK_GRACE_TICKS, false)) {
					end(st, v);
				}
				return true;
			}
			stopSocialWalk(st, v);
			v.getLookControl().setLookAt(partner);
			// Etwa alle 2 Sekunden "reden": Laut und Herzchen/Notenteilchen.
			if ((now / Config.JOB_INTERVAL + v.getId()) % 4 == 0) {
				c.level().playSound(null, v.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.7F, 0.9F + v.getRandom().nextFloat() * 0.3F);
				c.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 2.0, v.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
			}
			if (now >= st.chatUntil) {
				if (st.chatInitiator) {
					v.gossip(c.level(), partner, now);
				}
				c.mem().chats++;
				c.mem().lastChat = now;
				if (partner.getAttachedOrCreate(VillagerPlus.MEMORY) != null) {
					partner.getAttachedOrCreate(VillagerPlus.MEMORY).lastChat = now;
				}
				end(st, v);
				st.chatCooldown = now + Config.CHAT_COOLDOWN;
			}
			return true;
		}

		if (now < st.chatCooldown) {
			c.setActivity("macht Pause");
			return false;
		}
		if (!SocialChecks.retryDue(now, st.chatSearchAt)) {
			c.setActivity("macht Pause");
			return false;
		}
		Villager other = c.level().getEntitiesOfClass(Villager.class, v.getBoundingBox().inflate(Config.CHAT_RADIUS),
				o -> o != v && o.isAlive() && !o.isBaby() && !o.isSleeping() && !o.isTrading())
			.stream()
			.filter(o -> availablePartner(o, now))
			.min(Comparator.comparingDouble(o -> o.distanceToSqr(v))).orElse(null);
		if (other == null) {
			st.chatSearchAt = SocialChecks.nextRetryAt(now, v.getUUID());
			c.setActivity("macht Pause");
			return false;
		}
		JobState os = JobManager.stateIfKnown(other);
		if (os == null) {
			st.chatSearchAt = SocialChecks.nextRetryAt(now, v.getUUID());
			c.setActivity("macht Pause");
			return false;
		}
		long chatUntil = now + Config.CHAT_DURATION;
		SocialChecks.MeetingSpots meeting = chooseMeetingSpots(v, other, chatUntil);
		if (meeting == null) {
			st.chatSearchAt = SocialChecks.nextRetryAt(now, v.getUUID());
			os.chatSearchAt = SocialChecks.nextRetryAt(now, other.getUUID());
			c.setActivity("macht Pause");
			return false;
		}
		st.chatSearchAt = 0;
		os.chatSearchAt = 0;
		st.chatPartner = other;
		st.chatInitiator = true;
		st.chatUntil = chatUntil;
		st.chatMeetingSpot = meeting.initiator();
		os.chatPartner = v;
		os.chatInitiator = false;
		os.chatUntil = chatUntil;
		os.chatMeetingSpot = meeting.recipient();
		return true;
	}

	/**
	 * Independent pairs use two adjacent stands around the recipient so both residents walk to a reachable,
	 * face-to-face position instead of one walking to the other resident's original block.
	 */
	private static SocialChecks.MeetingSpots chooseMeetingSpots(Villager initiator, Villager recipient, long chatUntil) {
		var level = recipient.level();
		BlockPos center = recipient.blockPosition();
		int start = SocialChecks.meetingSlot(initiator.getUUID(), recipient.getUUID(), chatUntil);
		List<Villager> nearby = level.getEntitiesOfClass(Villager.class,
			recipient.getBoundingBox().inflate(SocialChecks.MEETING_RESERVATION_SCAN_RADIUS), villager -> villager.isAlive()
				&& villager != initiator && villager != recipient);
		List<BlockPos> nearbyVillagerPositions = new java.util.ArrayList<>(nearby.size());
		Set<BlockPos> reserved = new HashSet<>();
		for (Villager villager : nearby) {
			BlockPos occupied = villager.blockPosition().immutable();
			nearbyVillagerPositions.add(occupied);
			JobState state = JobManager.stateIfKnown(villager);
			if (state != null && state.chatPartner != null && state.chatPartner.isAlive() && state.chatMeetingSpot != null
				&& SocialChecks.reservationActive(state.chatUntil, level.getGameTime())) {
				reserved.add(state.chatMeetingSpot.immutable());
				JobState partnerState = JobManager.stateIfKnown(state.chatPartner);
				if (partnerState != null && partnerState.chatPartner == villager && partnerState.chatMeetingSpot != null) {
					reserved.add(partnerState.chatMeetingSpot.immutable());
				}
			}
		}
		int pathProbes = 0;
		for (int attempt = 0; attempt < SocialChecks.MEETING_SLOT_COUNT; attempt++) {
			int slot = (start + attempt) % SocialChecks.MEETING_SLOT_COUNT;
			BlockPos surface = center.offset(SocialChecks.meetingOffsetX(slot), 0, SocialChecks.meetingOffsetZ(slot));
			Direction facing = meetingFacing(slot);
			for (int yOffset : new int[]{0, 1, -1}) {
				BlockPos initiatorStand = surface.offset(0, yOffset, 0);
				BlockPos recipientStand = initiatorStand.relative(facing);
				SocialChecks.MeetingSpots candidate = new SocialChecks.MeetingSpots(initiatorStand, recipientStand);
				if (!walkableMeetingBlock(level, initiatorStand) || !walkableMeetingBlock(level, recipientStand)) continue;
				if (!SocialChecks.meetingSpotsHaveClearance(candidate, nearbyVillagerPositions, reserved)) continue;
				if (pathProbes++ >= SocialChecks.MEETING_PATH_PROBE_BUDGET) return null;
				if (!canReach(initiator, initiatorStand) || !canReach(recipient, recipientStand)) continue;
				return candidate;
			}
		}
		// Every viable pair of stands is occupied, reserved, or unreachable; retry normally after the bounded delay.
		return null;
	}

	private static Direction meetingFacing(int slot) {
		return switch (Math.floorMod(slot, 4)) {
			case 0 -> Direction.NORTH;
			case 1 -> Direction.EAST;
			case 2 -> Direction.SOUTH;
			default -> Direction.WEST;
		};
	}

	private static boolean canReach(Villager villager, BlockPos feet) {
		if (villager.blockPosition().equals(feet)) return true;
		var path = villager.getNavigation().createPath(feet, 0);
		return path != null && path.canReach();
	}

	private static boolean walkableMeetingBlock(net.minecraft.world.level.LevelReader level, BlockPos feet) {
		BlockPos floorPos = feet.below();
		BlockPos headPos = feet.above();
		if (!level.hasChunkAt(floorPos) || !level.hasChunkAt(feet) || !level.hasChunkAt(headPos)) return false;
		BlockState floor = level.getBlockState(floorPos);
		BlockState body = level.getBlockState(feet);
		BlockState head = level.getBlockState(headPos);
		return floor.isFaceSturdy(level, floorPos, Direction.UP)
			&& floor.getFluidState().isEmpty()
			&& body.getCollisionShape(level, feet).isEmpty()
			&& head.getCollisionShape(level, headPos).isEmpty()
			&& body.getFluidState().isEmpty() && head.getFluidState().isEmpty();
	}

	private static boolean availablePartner(Villager candidate, long now) {
		JobState state = JobManager.stateIfKnown(candidate);
		boolean unavailable = state == null || de.villagerplus.Compat.ignored(candidate) || Guests.isGuest(candidate)
			|| Expedition.isAway(candidate) || RailTransport.isPassenger(candidate) || candidate.isNoAi()
			|| candidate.getNavigation().isInProgress();
		return SocialChecks.eligiblePartner(state != null, unavailable,
			state != null && state.chatPartner != null, now, state == null ? Long.MAX_VALUE : state.chatCooldown);
	}

	private static void end(JobState st, Villager v) {
		stopSocialWalk(st, v);
		Villager p = st.chatPartner;
		st.chatPartner = null;
		st.chatInitiator = false;
		st.chatMeetingSpot = null;
		st.chatSearchAt = 0;
		if (p != null) {
			JobState ps = JobManager.stateIfKnown(p);
			if (ps != null && ps.chatPartner == v) {
				ps.chatSearchAt = 0;
				ps.chatPartner = null;
				ps.chatInitiator = false;
				ps.chatMeetingSpot = null;
				ps.chatCooldown = Math.max(ps.chatCooldown, v.level().getGameTime() + Config.CHAT_COOLDOWN);
			}
		}
	}

	private static void stopSocialWalk(JobState st, Villager v) {
		v.getNavigation().stop();
		v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		st.walkStart = 0;
		st.stuckCount = 0;
		st.bestDist = 0.0;
	}

	private static String nameOf(Villager v) {
		String n = v.getAttachedOrCreate(VillagerPlus.MEMORY).name;
		return n.isEmpty() ? "einem Nachbarn" : n;
	}
}
