package de.villagerplus.mixin;

import de.villagerplus.StallWatchdog;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One heartbeat per rendered frame, so a frozen picture is visible even while the game tick keeps running. */
@Mixin(Minecraft.class)
public class ClientFrameMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void villagerplus$frameBeat(boolean advanceGameTime, CallbackInfo ci) {
		StallWatchdog.beatClientFrame();
	}
}
