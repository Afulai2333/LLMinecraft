package dev.llmcraft.client.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Small typed accessors with useful validation messages. */
public final class Args {
	private Args() {
	}

	public static JsonObject schema(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	public static String requireString(JsonObject args, String key) {
		String value = optString(args, key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing required string argument '" + key + "'.");
		}
		return value;
	}

	public static String optString(JsonObject args, String key) {
		JsonElement value = args.get(key);
		if (value == null || value.isJsonNull()) {
			return null;
		}
		return value.isJsonPrimitive() ? value.getAsString() : null;
	}

	public static int requireInt(JsonObject args, String key) {
		JsonElement value = args.get(key);
		if (value == null || value.isJsonNull()) {
			throw new IllegalArgumentException("Missing required integer argument '" + key + "'.");
		}
		return value.getAsInt();
	}

	public static Integer optInt(JsonObject args, String key) {
		JsonElement value = args.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsInt();
	}

	public static double requireDouble(JsonObject args, String key) {
		JsonElement value = args.get(key);
		if (value == null || value.isJsonNull()) {
			throw new IllegalArgumentException("Missing required number argument '" + key + "'.");
		}
		return value.getAsDouble();
	}

	public static Double optDouble(JsonObject args, String key) {
		JsonElement value = args.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsDouble();
	}

	public static boolean optBoolean(JsonObject args, String key, boolean fallback) {
		JsonElement value = args.get(key);
		return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
	}

	public static int intOr(JsonObject args, String key, int fallback) {
		JsonElement value = args.get(key);
		return value == null || value.isJsonNull() ? fallback : value.getAsInt();
	}

	public static String stringOr(JsonObject args, String key, String fallback) {
		String value = optString(args, key);
		return value == null ? fallback : value;
	}
}
