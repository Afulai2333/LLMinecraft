package dev.llmcraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-initiated game events. Each event is pushed to connected MCP SSE clients and kept
 * in a bounded ring so a client that was disconnected can catch up with {@code get_events}.
 */
public final class EventLog {
	private static final int MAX_EVENTS = 300;
	private static final AtomicLong SEQUENCE = new AtomicLong();
	private static final Deque<JsonObject> EVENTS = new ArrayDeque<>();

	private EventLog() {
	}

	public static void record(String type, String level, JsonObject data) {
		JsonObject event = new JsonObject();
		event.addProperty("id", SEQUENCE.incrementAndGet());
		event.addProperty("type", type);
		event.addProperty("level", level);
		event.addProperty("time", System.currentTimeMillis());
		event.add("data", data == null ? new JsonObject() : data);
		synchronized (EVENTS) {
			EVENTS.addLast(event);
			while (EVENTS.size() > MAX_EVENTS) {
				EVENTS.removeFirst();
			}
		}
		McpServerManager.broadcastEvent(type, level, event);
	}

	public static long latestId() {
		return SEQUENCE.get();
	}

	public static JsonArray since(long sinceId, int limit) {
		JsonArray array = new JsonArray();
		synchronized (EVENTS) {
			for (JsonObject event : EVENTS) {
				if (event.get("id").getAsLong() <= sinceId) {
					continue;
				}
				array.add(event.deepCopy());
				if (array.size() >= limit) {
					break;
				}
			}
		}
		return array;
	}
}
