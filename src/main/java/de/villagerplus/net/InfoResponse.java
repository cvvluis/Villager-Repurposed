package de.villagerplus.net;

import de.villagerplus.VillagerPlus;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> Client: Request-ID und Zeilen des Steckbriefs (Format {@code Label|Wert|Prozent}). */
public record InfoResponse(int requestId, List<String> lines) implements CustomPacketPayload {
	public static final int MAX_LINES = 64;
	public static final int MAX_LINE_CODE_POINTS = 192;
	public static final CustomPacketPayload.Type<InfoResponse> TYPE =
		new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "info_response"));
	public static final StreamCodec<RegistryFriendlyByteBuf, InfoResponse> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, InfoResponse::requestId,
		ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_LINES)), InfoResponse::lines,
		InfoResponse::new);

	public InfoResponse {
		lines = lines == null ? List.of() : lines.stream().limit(MAX_LINES).map(InfoResponse::boundLine).toList();
	}

	private static String boundLine(String line) {
		String safe = line == null ? "" : line;
		if (safe.codePointCount(0, safe.length()) <= MAX_LINE_CODE_POINTS) return safe;
		int first = safe.indexOf('|');
		int last = safe.lastIndexOf('|');
		if (first > 0 && last > first) {
			String label = truncate(safe.substring(0, first), 48);
			String percent = truncate(safe.substring(last + 1), 8);
			int valueLimit = MAX_LINE_CODE_POINTS - label.codePointCount(0, label.length())
				- percent.codePointCount(0, percent.length()) - 2;
			return label + "|" + truncate(safe.substring(first + 1, last), valueLimit) + "|" + percent;
		}
		return truncate(safe, MAX_LINE_CODE_POINTS);
	}

	private static String truncate(String value, int maxCodePoints) {
		int count = value.codePointCount(0, value.length());
		if (count <= maxCodePoints) return value;
		int prefix = Math.max(0, maxCodePoints - 1);
		return value.substring(0, value.offsetByCodePoints(0, prefix)) + "…";
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
