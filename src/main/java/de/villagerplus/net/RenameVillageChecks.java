package de.villagerplus.net;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Network;
import de.villagerplus.village.VillageRef;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** World checks for server-side rename validation, persistence, and reset behavior. */
public final class RenameVillageChecks {
	private RenameVillageChecks() { }

	public static int run(ServerLevel level, VillageRef ref) {
		if (level == null || ref == null || ref.bellEntity() == null) throw new IllegalArgumentException("rename fixture needs a village bell");
		String original = ref.bellEntity().getAttached(VillagerPlus.VILLAGE_NAME);
		int checks = 0;
		try {
			String expected = "Königsbrück am Fluss";
			var renamed = Network.rename(level, ref, "  Königsbrück\u3000am\u3000Fluss  ");
			require(renamed.isPresent() && renamed.get().equals(expected), "server accepts, normalizes, and returns the committed name"); checks++;
			require(expected.equals(ref.bellEntity().getAttached(VillagerPlus.VILLAGE_NAME)), "confirmed manual name is stored on the village bell"); checks++;

			var encoded = Codec.STRING.encodeStart(JsonOps.INSTANCE, ref.bellEntity().getAttached(VillagerPlus.VILLAGE_NAME)).getOrThrow();
			String restored = Codec.STRING.parse(JsonOps.INSTANCE, encoded).getOrThrow();
			require(restored.equals(expected), "confirmed manual name round-trips through its persistent string codec"); checks++;

			List<String> invalid = List.of("A".repeat(Network.MAX_MANUAL_VILLAGE_NAME_CODE_POINTS + 1), "Dorf\nNeu", "Dorf\u202Eabc", "Alt\u200Bstadt", "§kDorf");
			for (String candidate : invalid) {
				require(Network.rename(level, ref, candidate).isEmpty(), "server rejects control, formatting, bidi, invisible, or overlong input"); checks++;
				require(expected.equals(ref.bellEntity().getAttached(VillagerPlus.VILLAGE_NAME)), "rejected input cannot overwrite the persisted village name"); checks++;
			}

			String procedural = Network.rename(level, ref, "  \u3000 ").orElseThrow();
			require(ref.bellEntity().getAttached(VillagerPlus.VILLAGE_NAME) == null
				&& !procedural.isBlank() && procedural.equals(Network.villageName(ref)), "whitespace-only request clears the manual override and returns the actual procedural name"); checks++;
			return checks;
		} finally {
			if (original == null) ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_NAME);
			else ref.bellEntity().setAttached(VillagerPlus.VILLAGE_NAME, original);
			ref.dirty();
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Rename village check failed: " + message);
	}
}
