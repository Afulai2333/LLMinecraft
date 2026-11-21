package dev.llmcraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/** A bounded history of chat, system and action-bar messages. */
public final class ChatLog {
	private static final int MAX_MESSAGES = 200;
	private static final Deque<Entry> MESSAGES = new ArrayDeque<>();

	private ChatLog() {
	}

	public static void add(String kind, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		synchronized (MESSAGES) {
			MESSAGES.addLast(new Entry(System.currentTimeMillis(), kind, text));
			while (MESSAGES.size() > MAX_MESSAGES) {
				MESSAGES.removeFirst();
			}
		}
		if ("chat".equals(kind) || "system".equals(kind)) {
			JsonObject data = new JsonObject();
			data.addProperty("kind", kind);
			data.addProperty("text", text);
			EventLog.record("chat", "info", data);
		}
	}

	public static JsonArray snapshot(int limit, String kindFilter) {
		JsonArray array = new JsonArray();
		synchronized (MESSAGES) {
			int skip = Math.max(0, MESSAGES.size() - Math.max(1, limit));
			int index = 0;
			for (Entry entry : MESSAGES) {
				if (index++ < skip) {
					continue;
				}
				if (kindFilter != null && !kindFilter.isBlank()
						&& !entry.kind.equalsIgnoreCase(kindFilter)) {
					continue;
				}
				JsonObject json = new JsonObject();
				json.addProperty("at", entry.timestamp);
				json.addProperty("kind", entry.kind);
				json.addProperty("text", entry.text);
				array.add(json);
			}
		}
		return array;
	}

	public static void clear() {
		synchronized (MESSAGES) {
			MESSAGES.clear();
		}
	}

	private record Entry(long timestamp, String kind, String text) {
	}
}
