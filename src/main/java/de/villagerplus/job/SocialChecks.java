package de.villagerplus.job;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import java.util.UUID;

/** Pure rules for bounded partner retries, eligibility, pair spacing, and social-walk expiry. */
public final class SocialChecks {
	static final long RETRY_MIN_TICKS = 60;
	static final long RETRY_JITTER_TICKS = 40;
	static final long CHAT_WALK_GRACE_TICKS = 400;
	static final double CHAT_MEETING_REACH = 0.9;
	static final int MEETING_SLOT_COUNT = 24;
	/** Keep separate chat pairs at least five horizontal blocks apart. */
	static final int MEETING_PAIR_CLEARANCE_SQUARED = 25;
	/** Covers the four-block candidate ring plus five-block pair spacing and resident width. */
	static final double MEETING_RESERVATION_SCAN_RADIUS = 10.0;
	static final int MEETING_STAND_DISTANCE_SQUARED = 1;
	/** Cap navigation probes per pair search so bad terrain cannot turn chat selection into a tick spike. */
	static final int MEETING_PATH_PROBE_BUDGET = 8;

	private SocialChecks() { }

	/** Two state-local, adjacent standing blocks for one face-to-face conversation. */
	record MeetingSpots(BlockPos initiator, BlockPos recipient) {
		MeetingSpots {
			initiator = initiator == null ? null : initiator.immutable();
			recipient = recipient == null ? null : recipient.immutable();
		}
	}

	static boolean retryDue(long now, long retryAt) {
		return now >= retryAt;
	}

	static long nextRetryAt(long now, UUID villagerId) {
		long mixed = villagerId.getMostSignificantBits()
			^ Long.rotateLeft(villagerId.getLeastSignificantBits(), 23);
		long jitter = Long.remainderUnsigned(mixed, RETRY_JITTER_TICKS + 1);
		return now + RETRY_MIN_TICKS + jitter;
	}

	static boolean eligiblePartner(boolean managed, boolean unavailable, boolean alreadyPaired,
		long now, long cooldownUntil) {
		return managed && !unavailable && !alreadyPaired && now >= cooldownUntil;
	}

	/** Stable slot for a pair for the whole chat; sorting UUIDs makes both residents derive the same spot. */
	static int meetingSlot(UUID first, UUID second, long chatUntil) {
		if (first == null || second == null) return 0;
		if (first.compareTo(second) > 0) {
			UUID swap = first;
			first = second;
			second = swap;
		}
		long mixed = first.getMostSignificantBits() * 0x9E3779B97F4A7C15L
			+ Long.rotateLeft(first.getLeastSignificantBits(), 17);
		mixed ^= second.getMostSignificantBits() * 0xBF58476D1CE4E5B9L
			+ Long.rotateLeft(second.getLeastSignificantBits(), 41);
		mixed ^= Long.rotateLeft(chatUntil, 29);
		mixed ^= mixed >>> 30;
		mixed *= 0xBF58476D1CE4E5B9L;
		mixed ^= mixed >>> 27;
		mixed *= 0x94D049BB133111EBL;
		mixed ^= mixed >>> 31;
		return (int) Long.remainderUnsigned(mixed, MEETING_SLOT_COUNT);
	}

	static int meetingOffsetX(int slot) {
		int normalized = Math.floorMod(slot, MEETING_SLOT_COUNT);
		int radius = 2 + normalized / 8;
		int diagonal = radius - 1;
		return switch (normalized % 8) {
			case 0 -> radius;
			case 1, 7 -> diagonal;
			case 2, 6 -> 0;
			case 3, 5 -> -diagonal;
			case 4 -> -radius;
			default -> 0;
		};
	}

	static int meetingOffsetZ(int slot) {
		int normalized = Math.floorMod(slot, MEETING_SLOT_COUNT);
		int radius = 2 + normalized / 8;
		int diagonal = radius - 1;
		return switch (normalized % 8) {
			case 0, 4 -> 0;
			case 1, 3 -> diagonal;
			case 2 -> radius;
			case 5, 7 -> -diagonal;
			case 6 -> -radius;
			default -> 0;
		};
	}

	static boolean atMeetingSpot(double distanceSquared) {
		return distanceSquared <= CHAT_MEETING_REACH * CHAT_MEETING_REACH;
	}

	static boolean walkExpired(long now, long deadline, boolean arrived) {
		return !arrived && now > deadline;
	}

	/** Existing pair reservations take precedence over a fresh crowd-based search and stay stable during the chat. */
	static MeetingSpots retainedMeetingSpots(BlockPos initiatorReservation, BlockPos recipientReservation) {
		if (initiatorReservation == null || recipientReservation == null
			|| initiatorReservation.equals(recipientReservation)) return null;
		return new MeetingSpots(initiatorReservation, recipientReservation);
	}

	/** Reserve both standing cells atomically, keeping each pair close while separating independent pairs. */
	static boolean meetingSpotsHaveClearance(MeetingSpots candidate, List<BlockPos> nearbyVillagers,
		Set<BlockPos> reservedSpots) {
		if (candidate == null || candidate.initiator() == null || candidate.recipient() == null
			|| nearbyVillagers == null || reservedSpots == null) return false;
		if (horizontalDistanceSquared(candidate.initiator(), candidate.recipient()) != MEETING_STAND_DISTANCE_SQUARED) return false;
		for (BlockPos nearby : nearbyVillagers) {
			if (nearby == null || horizontalDistanceSquared(candidate.initiator(), nearby) < 4
				|| horizontalDistanceSquared(candidate.recipient(), nearby) < 4) return false;
		}
		for (BlockPos reserved : reservedSpots) {
			if (reserved == null || horizontalDistanceSquared(candidate.initiator(), reserved) < MEETING_PAIR_CLEARANCE_SQUARED
				|| horizontalDistanceSquared(candidate.recipient(), reserved) < MEETING_PAIR_CLEARANCE_SQUARED) return false;
		}
		return true;
	}

	/** Reservation lifetime ends with the normal walk grace window; no independent cache can leak UUIDs. */
	static boolean reservationActive(long chatUntil, long now) {
		return now <= chatUntil + CHAT_WALK_GRACE_TICKS;
	}

	private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
		long dx = (long) first.getX() - second.getX();
		long dz = (long) first.getZ() - second.getZ();
		return dx * dx + dz * dz;
	}

	/** No world access; the suite can run without creating entities or mutating inventories. */
	public static int run() {
		int checks = 0;
		long now = 10_000;
		UUID resident = new UUID(0x2a9d_31b4_44c7_10a1L, 0x7c52_d8e3_91ab_002fL);
		long retryAt = nextRetryAt(now, resident);
		require(retryDue(now, 0), "the default retry time permits the first search immediately"); checks++;
		require(!retryDue(now, retryAt), "a failed search schedules a later retry"); checks++;
		require(retryAt >= now + RETRY_MIN_TICKS && retryAt <= now + RETRY_MIN_TICKS + RETRY_JITTER_TICKS,
			"retry delay remains within the bounded few-second window"); checks++;
		require(retryAt == nextRetryAt(now, resident), "the same villager receives the same deterministic offset"); checks++;
		require(!retryDue(retryAt - 1, retryAt) && retryDue(retryAt, retryAt),
			"search resumes exactly when its retry expires"); checks++;
		Set<Long> staggered = new HashSet<>();
		for (int i = 0; i < 16; i++) staggered.add(nextRetryAt(now, new UUID(0, i)));
		require(staggered.size() > 1, "different residents stagger their retry ticks"); checks++;
		UUID first = new UUID(0x2a9d_31b4_44c7_10a1L, 0x7c52_d8e3_91ab_002fL);
		UUID second = new UUID(0x31e4_981a_0d73_52c6L, 0x5b20_ef91_8a64_317dL);
		int slot = meetingSlot(first, second, now + 200);
		require(slot == meetingSlot(second, first, now + 200)
			&& slot == meetingSlot(first, second, now + 200),
			"both residents derive the same deterministic slot throughout their shared chat"); checks++;
		Set<String> offsets = new HashSet<>();
		for (int i = 0; i < MEETING_SLOT_COUNT; i++) {
			int dx = meetingOffsetX(i), dz = meetingOffsetZ(i);
			offsets.add(dx + "," + dz);
			int radius = 2 + i / 8;
			int far = Math.max(Math.abs(dx), Math.abs(dz));
			int near = Math.min(Math.abs(dx), Math.abs(dz));
			require((far == radius && near == 0) || (far == radius - 1 && near == radius - 1),
				"conversation slots stay on separated cardinal and diagonal rings"); checks++;
		}
		require(offsets.size() == MEETING_SLOT_COUNT && atMeetingSpot(0.81) && !atMeetingSpot(0.82),
			"pair destinations spread across 24 nearby spots and use a tight arrival radius"); checks++;
		MeetingSpots firstPair = new MeetingSpots(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));
		require(meetingSpotsHaveClearance(firstPair, List.of(), Set.of()), "both adjacent standing cells can be reserved together"); checks++;
		require(!meetingSpotsHaveClearance(firstPair, List.of(new BlockPos(0, 64, 0)), Set.of()),
			"an already occupied stand is rejected before the pair starts walking"); checks++;
		require(meetingSpotsHaveClearance(firstPair, List.of(new BlockPos(3, 120, 0)), Set.of()),
			"vertical separation does not crowd a pair when a resident is horizontally two blocks away"); checks++;
		MeetingSpots secondPair = new MeetingSpots(new BlockPos(7, 64, 0), new BlockPos(8, 64, 0));
		require(meetingSpotsHaveClearance(secondPair, List.of(), Set.of(firstPair.initiator(), firstPair.recipient())),
			"two simultaneous pairs get separate stand pairs with at least five horizontal blocks of clearance"); checks++;
		MeetingSpots crowdedPair = new MeetingSpots(new BlockPos(4, 64, 0), new BlockPos(5, 64, 0));
		require(!meetingSpotsHaveClearance(crowdedPair, List.of(), Set.of(firstPair.initiator(), firstPair.recipient())),
			"an occupied reservation prevents a second pair from stacking in the same gathering area"); checks++;
		MeetingSpots invalidPair = new MeetingSpots(new BlockPos(0, 64, 0), new BlockPos(0, 64, 0));
		require(!meetingSpotsHaveClearance(invalidPair, List.of(), Set.of()), "a pair cannot claim the same standing cell twice"); checks++;
		BlockPos savedInitiator = new BlockPos(12, 64, -8), savedRecipient = savedInitiator.east();
		MeetingSpots retained = retainedMeetingSpots(savedInitiator, savedRecipient);
		require(retained != null && retained.initiator().equals(savedInitiator) && retained.recipient().equals(savedRecipient)
			&& retainedMeetingSpots(savedInitiator, null) == null && retainedMeetingSpots(null, savedRecipient) == null,
			"an active pair reuses both stand reservations; a half-reservation is discarded and recalculated"); checks++;
		Set<BlockPos> temporaryReservations = new HashSet<>(Set.of(firstPair.initiator(), firstPair.recipient()));
		long reservationUntil = now + 200;
		require(reservationActive(reservationUntil, reservationUntil + CHAT_WALK_GRACE_TICKS)
			&& !reservationActive(reservationUntil, reservationUntil + CHAT_WALK_GRACE_TICKS + 1),
			"pair reservations expire immediately after the existing bounded walk grace period"); checks++;
		if (!reservationActive(reservationUntil, reservationUntil + CHAT_WALK_GRACE_TICKS + 1)) {
			temporaryReservations.clear();
		}
		require(meetingSpotsHaveClearance(firstPair, List.of(), temporaryReservations),
			"expired pair stands are released and can be reused without a UUID reservation cache"); checks++;
		require(retainedMeetingSpots(null, null) == null,
			"after pair cleanup clears both JobState stand fields, no old meeting remains reusable"); checks++;
		Set<Integer> pairSlots = new HashSet<>();
		for (int i = 0; i < 32; i++) {
			pairSlots.add(meetingSlot(new UUID(0x1000L + i, i * 257L),
				new UUID(0x5000L + i * 17L, i * 7919L + 11L), now + 200));
		}
		require(pairSlots.size() >= 13, "independent pairs are distributed across most of the available spots"); checks++;
		require(eligiblePartner(true, false, false, now, now), "a managed free resident can join a chat when its cooldown is due"); checks++;
		require(!eligiblePartner(false, false, false, now, now), "an unmanaged villager is never reserved as a social walk target"); checks++;
		require(!eligiblePartner(true, true, false, now, now), "sleeping, trading, guest, or already-moving villagers remain ineligible social walk targets"); checks++;
		require(!eligiblePartner(true, false, true, now, now), "a resident already paired cannot receive a second chat target"); checks++;
		require(!eligiblePartner(true, false, false, now, now + 1), "a resident's active chat cooldown prevents another target"); checks++;
		require(!walkExpired(now, now, false) && walkExpired(now + 1, now, false),
			"an unreachable social walk ends only after its bounded grace period"); checks++;
		require(!walkExpired(now + 1, now, true), "arrival keeps a valid chat instead of treating the walk as failed"); checks++;
		return checks;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Social check failed: " + message);
	}
}
