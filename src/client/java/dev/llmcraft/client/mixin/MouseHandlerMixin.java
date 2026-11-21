package dev.llmcraft.client.mixin;

import dev.llmcraft.client.InputLock;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blocks human mouse movement/clicks/scroll while the agent owns the controls. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void llmcraft$grabMouse(CallbackInfo ci) {
		if (InputLock.shouldReleaseCursor()) {
			ci.cancel();
		}
	}

	@Inject(method = "isMouseGrabbed", at = @At("HEAD"), cancellable = true)
	private void llmcraft$isMouseGrabbed(CallbackInfoReturnable<Boolean> cir) {
		if (InputLock.simulateMouseGrab()) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void llmcraft$onButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
		if (InputLock.blocksInput()) {
			ci.cancel();
		}
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void llmcraft$onScroll(long window, double x, double y, CallbackInfo ci) {
		if (InputLock.blocksInput()) {
			ci.cancel();
		}
	}

	@Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
	private void llmcraft$onMove(long window, double x, double y, double dx, double dy, CallbackInfo ci) {
		if (InputLock.blocksInput()) {
			ci.cancel();
		}
	}
}
