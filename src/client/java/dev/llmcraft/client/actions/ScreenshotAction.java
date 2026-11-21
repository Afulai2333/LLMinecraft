package dev.llmcraft.client.actions;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.llmcraft.client.ClientAction;
import dev.llmcraft.client.ClientBridge;
import dev.llmcraft.client.tools.StateSnapshot;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Captures the current frame and returns it as an MCP image. */
public final class ScreenshotAction implements ClientAction {
	private static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;

	private final int requestedScale;
	private CompletableFuture<byte[]> capture;
	private Path savedPath;
	private int age;

	public ScreenshotAction(int requestedScale) {
		this.requestedScale = Math.max(1, requestedScale);
	}

	@Override
	public ToolResult start(Minecraft minecraft) {
		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		if (target.width <= 0 || target.height <= 0 || target.getColorTexture() == null) {
			return ToolResult.error("The framebuffer is not ready; wait a moment and retry.");
		}
		int scale = requestedScale;
		if (target.width % scale != 0 || target.height % scale != 0) {
			scale = 1;
		}
		capture = new CompletableFuture<>();
		savedPath = minecraft.gameDirectory.toPath().resolve("screenshots")
				.resolve("llmcraft-" + System.currentTimeMillis() + ".png");
		try {
			Files.createDirectories(savedPath.getParent());
		} catch (Exception e) {
			savedPath = null;
		}
		final int finalScale = scale;
		final Path output = savedPath;
		Screenshot.takeScreenshot(target, finalScale, image -> {
			try (image) {
				byte[] bytes;
				if (output != null) {
					image.writeToFile(output.toFile());
					bytes = Files.readAllBytes(output);
				} else {
					Path temp = Files.createTempFile("llmcraft-", ".png");
					try {
						image.writeToFile(temp.toFile());
						bytes = Files.readAllBytes(temp);
					} finally {
						Files.deleteIfExists(temp);
					}
				}
				capture.complete(bytes);
			} catch (Throwable t) {
				capture.completeExceptionally(t);
			}
		});
		return null;
	}

	@Override
	public ToolResult tick(Minecraft minecraft) {
		if (++age > 200) {
			return ToolResult.error("Screenshot timed out.");
		}
		if (capture == null || !capture.isDone()) {
			return null;
		}
		try {
			byte[] bytes = capture.join();
			JsonObject meta = new JsonObject();
			meta.addProperty("ok", true);
			meta.addProperty("message", "Captured the current view.");
			meta.addProperty("file", savedPath == null ? "" : savedPath.toString());
			if (minecraft.player != null) {
				meta.addProperty("x", StateSnapshot.round(minecraft.player.getX()));
				meta.addProperty("y", StateSnapshot.round(minecraft.player.getY()));
				meta.addProperty("z", StateSnapshot.round(minecraft.player.getZ()));
				meta.addProperty("yaw", StateSnapshot.round(minecraft.player.getYRot()));
				meta.addProperty("pitch", StateSnapshot.round(minecraft.player.getXRot()));
				meta.add("held", StateSnapshot.item(minecraft.player.getMainHandItem(),
						minecraft.player.getInventory().getSelectedSlot()));
			}
			ToolResult result = ToolResult.text(meta.toString());
			if (bytes.length <= MAX_IMAGE_BYTES) {
				result.addImage(bytes);
			} else {
				result.addText("Image was too large to inline (" + bytes.length
						+ " bytes); open the saved file instead.");
			}
			return result;
		} catch (Throwable t) {
			return ToolResult.error("Screenshot failed: " + t.getMessage());
		}
	}

	@Override
	public void cleanup(Minecraft minecraft) {
	}

	@Override
	public String name() {
		return "screenshot";
	}
}
