package dev.llmcraft.client;

import dev.llmcraft.LLMinecraft;
import dev.llmcraft.mcp.ToolResult;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Marshals work onto the Minecraft client thread and runs one multi-tick action at a time.
 *
 * <p>HTTP worker threads must never touch the game state directly. Read-only snapshots are
 * posted with {@link #supply}; physical actions are queued as {@link ClientAction}s and ticked
 * from {@code ClientTickEvents.START_CLIENT_TICK}.</p>
 */
public final class ClientBridge {
	private static final int MAX_QUEUE = 8;
	private static final Deque<Entry> QUEUE = new ArrayDeque<>();
	private static Entry current;

	private ClientBridge() {
	}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static <T> CompletableFuture<T> supply(Supplier<T> supplier) {
		CompletableFuture<T> future = new CompletableFuture<>();
		Minecraft minecraft = mc();
		if (minecraft == null) {
			future.completeExceptionally(new IllegalStateException("Minecraft is not running"));
			return future;
		}
		minecraft.execute(() -> {
			InputLock.activate(minecraft);
			TickFreezer.unfreezeForWork(minecraft);
			try {
				future.complete(supplier.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		return future;
	}

	public static CompletableFuture<ToolResult> submit(ClientAction action, int timeoutTicks) {
		CompletableFuture<ToolResult> future = new CompletableFuture<>();
		Minecraft minecraft = mc();
		if (minecraft == null) {
			future.complete(ToolResult.error("Minecraft is not running."));
			return future;
		}
		minecraft.execute(() -> {
			InputLock.activate(minecraft);
			if (!InputLock.agentCanAct()) {
				future.complete(ToolResult.error(InputLock.userHasControl()
						? "The user has control. Wait for them to press Ctrl+C and hand control back."
						: "A user takeover is pending. Wait for F8, or call resume_control to cancel it."));
				return;
			}
			TickFreezer.unfreezeForWork(minecraft);
			if (QUEUE.size() >= MAX_QUEUE) {
				future.complete(ToolResult.error("Too many queued actions; wait a moment and retry."));
				return;
			}
			QUEUE.addLast(new Entry(action, future, Math.max(1, timeoutTicks)));
		});
		return future;
	}

	public static void tick(Minecraft minecraft) {
		if (current == null) {
			current = QUEUE.pollFirst();
			if (current == null) {
				return;
			}
			TickFreezer.unfreezeForWork(minecraft);
			// A pause screen (often opened because the game window lost focus) prevents
			// simulated input. The agent is the one driving, so unpause for it.
			if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
				minecraft.gui.setScreen(null);
			}
			try {
				ToolResult immediate = current.action.start(minecraft);
				if (immediate != null) {
					finish(immediate);
					return;
				}
			} catch (Throwable t) {
				LLMinecraft.LOGGER.warn("Action {} failed to start", current.action.name(), t);
				finish(ToolResult.error("Action failed to start: " + message(t)));
				return;
			}
		}

		if (current.future.isCancelled()) {
			abort();
			return;
		}
		current.age++;
		if (current.age > current.timeoutTicks) {
			finish(ToolResult.error("Action timed out after "
					+ String.format("%.1f", current.timeoutTicks / 20.0) + "s."));
			return;
		}

		try {
			ToolResult done = current.action.tick(minecraft);
			if (done != null) {
				finish(done);
			}
		} catch (Throwable t) {
			LLMinecraft.LOGGER.warn("Action {} failed", current.action.name(), t);
			finish(ToolResult.error("Action failed: " + message(t)));
		}
	}

	public static void cancelAll(String reason) {
		Minecraft minecraft = mc();
		if (minecraft != null) {
			minecraft.execute(() -> {
				TickFreezer.unfreezeForWork(minecraft);
				if (current != null) {
					current.action.cleanup(minecraft);
					current.future.complete(ToolResult.error(reason));
					current = null;
				}
				while (!QUEUE.isEmpty()) {
					Entry entry = QUEUE.pollFirst();
					entry.future.complete(ToolResult.error(reason));
				}
			});
		}
	}

	public static int queueSize() {
		return QUEUE.size() + (current == null ? 0 : 1);
	}

	public static String currentActionName() {
		Entry entry = current;
		return entry == null ? null : entry.action.name();
	}

	/** True while a plain wait or time-sleep is holding the agent. */
	public static boolean isWaiting() {
		String name = currentActionName();
		return "wait".equals(name) || "sleep".equals(name);
	}

	/** True when the active wait/sleep explicitly opted out of threat interruption. */
	public static boolean isWaitingForced() {
		Entry entry = current;
		return entry != null && entry.action.forced();
	}

	private static void finish(ToolResult result) {
		Entry entry = current;
		current = null;
		Minecraft minecraft = mc();
		if (entry == null) {
			return;
		}
		if (minecraft != null) {
			try {
				entry.action.cleanup(minecraft);
			} catch (Throwable t) {
				LLMinecraft.LOGGER.debug("Action cleanup failed", t);
			}
		}
		entry.future.complete(result);
	}

	private static void abort() {
		Entry entry = current;
		current = null;
		Minecraft minecraft = mc();
		if (entry == null) {
			return;
		}
		if (minecraft != null) {
			try {
				entry.action.cleanup(minecraft);
			} catch (Throwable t) {
				LLMinecraft.LOGGER.debug("Action cleanup failed", t);
			}
		}
		entry.future.cancel(false);
	}

	private static String message(Throwable t) {
		return t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
	}

	private static final class Entry {
		final ClientAction action;
		final CompletableFuture<ToolResult> future;
		final int timeoutTicks;
		int age;

		Entry(ClientAction action, CompletableFuture<ToolResult> future, int timeoutTicks) {
			this.action = action;
			this.future = future;
			this.timeoutTicks = timeoutTicks;
		}
	}
}
