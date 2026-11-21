package dev.llmcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.llmcraft.LLMinecraft;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * User-editable settings, stored in {@code config/llmcraft.json}.
 *
 * <p>The defaults are deliberately conservative: the MCP listener is bound to loopback and
 * only allows local processes. Set {@link #allowRemote} and a {@link #authToken} if a remote
 * agent has to reach the game.</p>
 */
public final class LLMinecraftConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static LLMinecraftConfig instance;

	public boolean enabled = true;
	public String host = "127.0.0.1";
	public int port = 8765;

	/** Optional bearer token. A random one is generated on first launch. */
	public String authToken = "";
	public boolean requireToken = false;
	public boolean allowRemote = false;

	/** How long an MCP tool call waits for a client-side action, in seconds. */
	public int actionTimeoutSeconds = 45;
	/** How long a single movement / mining action may take, in seconds. */
	public int maxActionSeconds = 30;

	public boolean allowChat = true;
	public boolean showChatFeedback = true;

	/** Scan for hostile mobs and expose them in get_state / read_chat. */
	public boolean threatDetection = true;
	/** Auto-melee a nearby non-creeper hostile when the agent is idle. */
	public boolean autoDefend = true;
	public int threatRadius = 16;

	/** Scan all living creatures and classify food animals, even when not hostile. */
	public boolean creatureDetection = true;
	public int creatureRadius = 24;

	/**
	 * Pause the integrated server while no tool is running, so the world does not advance
	 * while the model is thinking. Local singleplayer only.
	 */
	public boolean freezeWhenIdle = true;
	/** Quiet ticks before freezing. 10 ticks = 0.5s. */
	public int freezeDelayTicks = 10;

	public boolean screenshots = true;
	/** Downscale factor for screenshots. 1 = native resolution, 2 = half. */
	public int screenshotScale = 2;

	/** Hard limit for block/entity scans. */
	public int maxScanRadius = 64;

	public static LLMinecraftConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	public static synchronized LLMinecraftConfig load() {
		Path path = configPath();
		LLMinecraftConfig config = new LLMinecraftConfig();
		try {
			if (Files.exists(path)) {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				LLMinecraftConfig loaded = GSON.fromJson(json, LLMinecraftConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			}
		} catch (Exception e) {
			LLMinecraft.LOGGER.warn("Could not read {}; using defaults", path, e);
		}
		config.sanitise();
		instance = config;
		config.save();
		return config;
	}

	public synchronized void save() {
		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LLMinecraft.LOGGER.warn("Could not write {}", path, e);
		}
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("llmcraft.json");
	}

	private void sanitise() {
		if (port < 1 || port > 65535) {
			port = 8765;
		}
		if (host == null || host.isBlank()) {
			host = "127.0.0.1";
		}
		if (!allowRemote && !isLoopback(host)) {
			LLMinecraft.LOGGER.warn("Refusing non-loopback host '{}' because allowRemote=false", host);
			host = "127.0.0.1";
		}
		if (authToken == null) {
			authToken = "";
		}
		if (authToken.isBlank()) {
			byte[] bytes = new byte[24];
			RANDOM.nextBytes(bytes);
			authToken = HexFormat.of().formatHex(bytes);
		}
		actionTimeoutSeconds = Math.clamp(actionTimeoutSeconds, 5, 180);
		maxActionSeconds = Math.clamp(maxActionSeconds, 5, 120);
		maxScanRadius = Math.clamp(maxScanRadius, 8, 128);
		screenshotScale = Math.clamp(screenshotScale, 1, 4);
		freezeDelayTicks = Math.clamp(freezeDelayTicks, 2, 200);
		threatRadius = Math.clamp(threatRadius, 4, 32);
		creatureRadius = Math.clamp(creatureRadius, 4, 48);
	}

	public static boolean isLoopback(String host) {
		return "127.0.0.1".equals(host)
				|| "localhost".equalsIgnoreCase(host)
				|| "::1".equals(host)
				|| "0:0:0:0:0:0:0:1".equals(host);
	}
}
