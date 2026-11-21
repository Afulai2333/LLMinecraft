package dev.llmcraft.client.mixin;

import dev.llmcraft.client.InputLock;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Blocks human keyboard input while the agent owns the controls (F8 toggles takeover). */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void llmcraft$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (InputLock.handleKey(action, event)) {
			ci.cancel();
		}
	}

	@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
	private void llmcraft$charTyped(long window, CharacterEvent event, CallbackInfo ci) {
		if (InputLock.blocksInput()) {
			ci.cancel();
		}
	}

	@Inject(method = "textInput", at = @At("HEAD"), cancellable = true)
	private void llmcraft$textInput(long window, String text, CallbackInfo ci) {
		if (InputLock.blocksInput()) {
			ci.cancel();
		}
	}

	@Inject(method = "textEditing", at = @At("HEAD"), cancellable = true)
	private void llmcraft$textEditing(long window, PreeditEvent event, CallbackInfo ci) {
		if (InputLock.blocksInput()) {
			ci.cancel();
		}
	}
}
