package de.villagerplus.village;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

/** Focused validation and persistent-string codec checks for Dorftafel village renames. */
public final class VillageRenameChecks {
	private VillageRenameChecks() { }

	public static int run() {
		int checks = 0;
		require(Network.normalizeManualVillageName("  Königsbrück\u3000am\u3000Fluss  ").orElseThrow()
			.equals("Königsbrück am Fluss"), "Unicode spacing is trimmed/collapsed and German characters are preserved"); checks++;
		require(Network.normalizeManualVillageName("Cafe\u0301").orElseThrow().equals("Café"), "accepted names are normalized to NFC"); checks++;
		require(Network.normalizeManualVillageName("München 🌳").isPresent(), "valid supplementary Unicode characters are retained"); checks++;
		require(Network.normalizeManualVillageName("A".repeat(Network.MAX_MANUAL_VILLAGE_NAME_CODE_POINTS)).isPresent(), "24 Unicode code points are accepted"); checks++;
		require(Network.normalizeManualVillageName("A".repeat(Network.MAX_MANUAL_VILLAGE_NAME_CODE_POINTS + 1)).isEmpty(), "names over 24 Unicode code points are rejected"); checks++;
		require(Network.normalizeManualVillageName(null).isEmpty(), "null input is rejected"); checks++;
		require(Network.normalizeManualVillageName("").orElseThrow().isEmpty()
			&& Network.normalizeManualVillageName("   \u3000 ").orElseThrow().isEmpty(), "empty and whitespace-only names request a procedural-name reset"); checks++;
		require(Network.normalizeManualVillageName("Neu\nDorf").isEmpty(), "control characters are rejected"); checks++;
		require(Network.normalizeManualVillageName("\t").isEmpty(), "control whitespace cannot be used to request a reset"); checks++;
		require(Network.normalizeManualVillageName("Dorf\u202Eabc").isEmpty(), "bidirectional format controls are rejected"); checks++;
		require(Network.normalizeManualVillageName("Alt\u200Bstadt").isEmpty(), "zero-width format characters are rejected"); checks++;
		require(Network.normalizeManualVillageName("§kDorf").isEmpty(), "Minecraft formatting codes are rejected"); checks++;

		String accepted = Network.normalizeManualVillageName("  Köln am Rhein  ").orElseThrow();
		var encoded = Codec.STRING.encodeStart(JsonOps.INSTANCE, accepted).getOrThrow();
		String restored = Codec.STRING.parse(JsonOps.INSTANCE, encoded).getOrThrow();
		require(restored.equals(accepted), "accepted custom name survives the persistent VILLAGE_NAME Codec.STRING round trip"); checks++;
		return checks;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Village rename check failed: " + message);
	}
}
