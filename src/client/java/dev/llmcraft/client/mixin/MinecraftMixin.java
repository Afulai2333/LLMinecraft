package dev.llmcraft.client.mixin;

import dev.llmcraft.client.InputLock;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the integrated server running while the agent controls the game, even if the
 * Minecraft window loses focus (the human alt-tabs to another program). The cursor is
 * already released by the mouse mixin, so this makes background play possible.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
	private void llmcraft$keepRunning(CallbackInfo ci) {
		if (InputLock.shouldPreventPause()) {
			ci.cancel();
		}
	}
}
