package de.villagerplus.mixin;

import com.mojang.logging.LogUtils;
import de.villagerplus.Config;
import net.minecraft.server.level.ServerChunkCache;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev-only: log when mod code triggers synchronous chunk generation through {@code getChunkBlocking}.
 * Enable with {@code -Dvillagerplus.debugChunkBlocking=true} or {@link Config#DEBUG}.
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheBlockingMixin {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final boolean ENABLED = Config.DEBUG
		|| Boolean.getBoolean("villagerplus.debugChunkBlocking");

	@Inject(method = "getChunkBlocking", at = @At("HEAD"))
	private void villagerplus$logBlockingChunkLoad(int chunkX, int chunkZ, CallbackInfoReturnable<?> cir) {
		if (!ENABLED) return;
		for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
			if ("de.villagerplus".equals(frame.getClassName())) {
				LOGGER.warn("Villagers: Repurposed: getChunkBlocking called from mod code ({}:{}). Stack:\n{}",
					frame.getClassName(), frame.getLineNumber(),
					String.join("\n", java.util.Arrays.stream(Thread.currentThread().getStackTrace())
						.map(StackTraceElement::toString).limit(20).toList()));
				return;
			}
		}
	}
}