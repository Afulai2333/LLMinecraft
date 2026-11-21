package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.ClientBridge;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.client.util.InputController;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Instantly rotates the camera to yaw/pitch or toward a point/entity. */
public final class LookAction implements ClientAction {
	private final Float yaw;
	private final Float pitch;
	private final Vec3 target;
	private final Integer entityId;
	private int ticks;

	public LookAction(Float yaw, Float pitch, Vec3 target, Integer entityId) {
		this.yaw = yaw;
		this.pitch = pitch;
		this.target = target;
		this.entityId = entityId;
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return ToolResult.error("No world is loaded.");
		}
		Vec3 point = target;
		if (entityId != null) {
			Entity entity = minecraft.level.getEntity(entityId);
			if (entity == null) {
				return ToolResult.error("No loaded entity with id " + entityId + ".");
			}
			point = entity.getEyePosition();
		}
		if (point != null) {
			InputController.lookAt(player, point);
		} else {
			float newYaw = yaw == null ? player.getYRot() : yaw;
			float newPitch = pitch == null ? player.getXRot() : pitch;
			player.setYRot(InputController.wrapDegrees(newYaw));
			player.setXRot(Math.clamp(newPitch, -90.0f, 90.0f));
		}
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++ticks < 2) {
			return null;
		}
		LocalPlayer player = minecraft.player;
		JsonObject result = new JsonObject();
		result.addProperty("ok", true);
		result.addProperty("message", "Camera rotated.");
		result.addProperty("yaw", StateSnapshot.round(player.getYRot()));
		result.addProperty("pitch", StateSnapshot.round(player.getXRot()));
		result.add("view", StateSnapshot.view(minecraft, minecraft.level, player));
		return ToolResult.text(result.toString());
	}

	@Override
	public String name() {
		return "look";
	}
}
