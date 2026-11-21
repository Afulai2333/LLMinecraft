package dev.llmcraft.client.util;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Simulated key presses and camera control. */
public final class InputController {
	private InputController() {
	}

	public static void set(KeyMapping mapping, boolean down) {
		if (mapping.isDown() != down) {
			mapping.setDown(down);
		}
	}

	public static void releaseMovement(Minecraft minecraft) {
		set(minecraft.options.keyUp, false);
		set(minecraft.options.keyDown, false);
		set(minecraft.options.keyLeft, false);
		set(minecraft.options.keyRight, false);
		set(minecraft.options.keyJump, false);
		set(minecraft.options.keyShift, false);
		set(minecraft.options.keySprint, false);
		set(minecraft.options.keyUse, false);
		set(minecraft.options.keyAttack, false);
	}

	public static void lookAt(LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		player.setYRot(wrapDegrees(yaw));
		player.setXRot(Mth.clamp(pitch, -90.0f, 90.0f));
	}

	public static void lookAtBlock(LocalPlayer player, int x, int y, int z) {
		lookAt(player, new Vec3(x + 0.5, y + 0.5, z + 0.5));
	}

	public static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360.0f;
		if (wrapped >= 180.0f) {
			wrapped -= 360.0f;
		}
		if (wrapped < -180.0f) {
			wrapped += 360.0f;
		}
		return wrapped;
	}

	public static Vec3 direction(float yawDegrees, float pitchDegrees) {
		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		double x = -Math.sin(yaw) * Math.cos(pitch);
		double y = -Math.sin(pitch);
		double z = Math.cos(yaw) * Math.cos(pitch);
		return new Vec3(x, y, z);
	}
}
