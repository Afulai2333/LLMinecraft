package dev.llmcraft.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.llmcraft.LLMinecraft;
import dev.llmcraft.config.LLMinecraftConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A small, dependency-free MCP Streamable HTTP server.
 *
 * <p>Endpoint: {@code POST /mcp}. JSON-RPC responses are returned as JSON, or as a single
 * server-sent event when the client only accepts {@code text/event-stream}. The server is
 * intentionally stateless apart from an optional session id so that reconnects are cheap.</p>
 */
public final class McpServer {
	public static final String PROTOCOL_VERSION = "2025-06-18";
	private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

	private static final Gson GSON = new Gson();

	private final String host;
	private final int requestedPort;
	private final String authToken;
	private final boolean requireToken;
	private final int timeoutSeconds;
	private final McpToolRegistry registry;
	private final String version;

	private HttpServer server;
	private ExecutorService executor;
	private volatile long startedAt;
	private final String sessionId = UUID.randomUUID().toString();
	private final Set<SseClient> sseClients = ConcurrentHashMap.newKeySet();

	public McpServer(LLMinecraftConfig config, McpToolRegistry registry, String version) {
		this.host = config.host;
		this.requestedPort = config.port;
		this.authToken = config.authToken == null ? "" : config.authToken;
		this.requireToken = config.requireToken;
		this.timeoutSeconds = config.actionTimeoutSeconds;
		this.registry = registry;
		this.version = version;
	}

	public synchronized void start() throws IOException {
		if (server != null) {
			return;
		}
		HttpServer created = HttpServer.create(new InetSocketAddress(host, requestedPort), 0);
		executor = Executors.newVirtualThreadPerTaskExecutor();
		created.setExecutor(executor);
		created.createContext("/mcp", this::handleMcp);
		created.createContext("/health", this::handleHealth);
		created.createContext("/", this::handleRoot);
		created.start();
		server = created;
		startedAt = System.currentTimeMillis();
		LLMinecraft.LOGGER.info("MCP server listening on http://{}:{}/mcp ({} tools)", host, port(), registry.size());
		if (requireToken) {
			LLMinecraft.LOGGER.info("MCP bearer token is required; see config/llmcraft.json");
		}
	}

	public synchronized void stop() {
		for (SseClient client : sseClients) {
			client.close();
		}
		sseClients.clear();
		if (server != null) {
			server.stop(1);
			server = null;
		}
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
		LLMinecraft.LOGGER.info("MCP server stopped");
	}

	public synchronized boolean isRunning() {
		return server != null;
	}

	/** The actual bound port (useful when port 0 was requested). */
	public int port() {
		HttpServer current = server;
		return current == null ? requestedPort : current.getAddress().getPort();
	}

	public String host() {
		return host;
	}

	public String endpoint() {
		return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port() + "/mcp";
	}

	private void handleRoot(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
			return;
		}
		String html = """
				<!doctype html>
				<html lang="en"><head><meta charset="utf-8"><title>LLMinecraft MCP</title>
				<style>body{font-family:ui-monospace,Consolas,monospace;margin:2rem;background:#111;color:#ddd}
				a{color:#7fd1ff}code{background:#222;padding:.15rem .35rem;border-radius:4px}</style></head>
				<body><h1>LLMinecraft MCP</h1>
				<p>Status: <strong>running</strong></p>
				<p>Endpoint: <code>%s</code></p>
				<p>Tools: <strong>%d</strong> &mdash; <a href="/health">health</a></p>
				<p>Add this URL as a Streamable HTTP MCP server in your agent client.</p>
				</body></html>
				""".formatted(escapeHtml(endpoint()), registry.size());
		sendText(exchange, 200, html, "text/html; charset=utf-8");
	}

	private void handleHealth(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
			return;
		}
		if (!authorised(exchange)) {
			sendJson(exchange, 401, error(null, -32001, "Unauthorized"));
			return;
		}
		JsonObject health = new JsonObject();
		health.addProperty("ok", true);
		health.addProperty("name", "llmcraft");
		health.addProperty("version", version);
		health.addProperty("endpoint", endpoint());
		health.addProperty("tools", registry.size());
		health.addProperty("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
		sendJson(exchange, 200, health);
	}

	private void handleMcp(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
		if ("OPTIONS".equals(method)) {
			exchange.getResponseHeaders().set("Allow", "POST, GET, DELETE, OPTIONS");
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
			return;
		}
		if (!authorised(exchange)) {
			sendJson(exchange, 401, error(null, -32001, "Unauthorized"));
			return;
		}
		if ("GET".equals(method)) {
			String accept = exchange.getRequestHeaders().getFirst("Accept");
			if (accept != null && accept.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
				serveEventStream(exchange);
				return;
			}
			Headers headers = exchange.getResponseHeaders();
			headers.set("Allow", "POST, GET (text/event-stream), DELETE, OPTIONS");
			sendJson(exchange, 405, error(null, -32000,
					"Use POST /mcp for JSON-RPC, or GET with Accept: text/event-stream for notifications"));
			return;
		}
		if ("DELETE".equals(method)) {
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
			return;
		}
		if (!"POST".equals(method)) {
			sendJson(exchange, 405, error(null, -32000, "Method not allowed"));
			return;
		}

		byte[] body;
		try {
			body = readBody(exchange.getRequestBody());
		} catch (IOException e) {
			sendJson(exchange, 413, error(null, -32600, "Request body too large"));
			return;
		}

		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
		} catch (JsonParseException e) {
			sendJson(exchange, 400, error(null, -32700, "Parse error: " + e.getMessage()));
			return;
		}

		if (parsed.isJsonArray()) {
			handleBatch(exchange, parsed.getAsJsonArray());
			return;
		}
		if (!parsed.isJsonObject()) {
			sendJson(exchange, 400, error(null, -32600, "Invalid Request"));
			return;
		}
		JsonObject response = handleSingle(parsed.getAsJsonObject());
		if (response == null) {
			exchange.sendResponseHeaders(202, -1);
			exchange.close();
			return;
		}
		sendRpc(exchange, response);
	}

	private void handleBatch(HttpExchange exchange, JsonArray batch) throws IOException {
		JsonArray responses = new JsonArray();
		for (JsonElement element : batch) {
			if (!element.isJsonObject()) {
				responses.add(error(null, -32600, "Invalid Request"));
				continue;
			}
			JsonObject response = handleSingle(element.getAsJsonObject());
			if (response != null) {
				responses.add(response);
			}
		}
		if (responses.isEmpty()) {
			exchange.sendResponseHeaders(202, -1);
			exchange.close();
			return;
		}
		sendRpc(exchange, responses);
	}

	/** Returns null for notifications, otherwise the JSON-RPC response object. */
	private JsonObject handleSingle(JsonObject request) {
		JsonElement id = request.get("id");
		String method = getString(request, "method");
		if (method == null) {
			return error(id, -32600, "Invalid Request: missing method");
		}
		boolean notification = id == null || id.isJsonNull();
		JsonObject params = request.has("params") && request.get("params").isJsonObject()
				? request.getAsJsonObject("params")
				: new JsonObject();

		try {
			switch (method) {
				case "initialize" -> {
					JsonObject result = new JsonObject();
					String requested = params.has("protocolVersion")
							? params.get("protocolVersion").getAsString()
							: PROTOCOL_VERSION;
					result.addProperty("protocolVersion", requested.startsWith("202") ? requested : PROTOCOL_VERSION);
					JsonObject capabilities = new JsonObject();
					JsonObject tools = new JsonObject();
					tools.addProperty("listChanged", false);
					capabilities.add("tools", tools);
					JsonObject logging = new JsonObject();
					capabilities.add("logging", logging);
					result.add("capabilities", capabilities);
					JsonObject serverInfo = new JsonObject();
					serverInfo.addProperty("name", "llmcraft");
					serverInfo.addProperty("title", "LLMinecraft");
					serverInfo.addProperty("version", version);
					result.add("serverInfo", serverInfo);
					result.addProperty("instructions", instructions());
					return notification ? null : result(id, result);
				}
				case "notifications/initialized", "notifications/cancelled",
						"notifications/roots/list_changed", "notifications/progress" -> {
					return null;
				}
				case "ping" -> {
					return notification ? null : result(id, new JsonObject());
				}
				case "logging/setLevel" -> {
					return notification ? null : result(id, new JsonObject());
				}
				case "tools/list" -> {
					JsonObject result = new JsonObject();
					result.add("tools", registry.toJson());
					return notification ? null : result(id, result);
				}
				case "tools/call" -> {
					if (notification) {
						return null;
					}
					return handleToolCall(id, params);
				}
				case "resources/list" -> {
					JsonObject result = new JsonObject();
					result.add("resources", new JsonArray());
					return notification ? null : result(id, result);
				}
				case "resources/templates/list" -> {
					JsonObject result = new JsonObject();
					result.add("resourceTemplates", new JsonArray());
					return notification ? null : result(id, result);
				}
				case "prompts/list" -> {
					JsonObject result = new JsonObject();
					result.add("prompts", new JsonArray());
					return notification ? null : result(id, result);
				}
				default -> {
					return notification ? null : error(id, -32601, "Method not found: " + method);
				}
			}
		} catch (Exception e) {
			LLMinecraft.LOGGER.warn("MCP request failed: {}", method, e);
			return notification ? null : error(id, -32603, "Internal error: " + e.getMessage());
		}
	}

	private JsonObject handleToolCall(JsonElement id, JsonObject params) {
		String name = getString(params, "name");
		if (name == null) {
			return error(id, -32602, "Invalid params: missing tool name");
		}
		if (registry.get(name) == null) {
			return error(id, -32602, "Unknown tool: " + name);
		}
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments")
				: new JsonObject();

		CompletableFuture<ToolResult> future;
		try {
			future = registry.call(name, arguments);
		} catch (Exception e) {
			return result(id, ToolResult.error("Tool failed to start: " + e.getMessage()).toJson());
		}

		try {
			ToolResult toolResult = future.get(timeoutSeconds, TimeUnit.SECONDS);
			return result(id, toolResult.toJson());
		} catch (TimeoutException e) {
			future.cancel(true);
			return result(id, ToolResult.error(
					"Timed out after " + timeoutSeconds + "s. The action may still be running; call get_state before retrying."
			).toJson());
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			LLMinecraft.LOGGER.warn("Tool {} failed", name, cause);
			return result(id, ToolResult.error("Tool failed: " + cause.getMessage()).toJson());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return result(id, ToolResult.error("Interrupted").toJson());
		}
	}

	private String instructions() {
		return """
				You are controlling a real Minecraft: Java Edition player through LLMinecraft.
				Call get_state first. Block coordinates are integers; y is the block the player stands on (feet).
				The player only reaches about 4.5 blocks: use goto before mine/place/interact on distant targets.
				Actions happen in real game time. Prefer short moves, then get_state to verify.
				In singleplayer the world may be frozen while you think; use sleep(seconds) to run it
				for a specific duration, and sleep_in_bed to skip the night in a bed.
				Valid item and block ids look like minecraft:oak_log. Use scan_blocks if you do not know what is nearby.
				If a tool returns an error, read it and adapt; do not repeat the same failing call blindly.
				""";
	}

	private boolean authorised(HttpExchange exchange) {
		if (!requireToken) {
			return true;
		}
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null) {
			header = exchange.getRequestHeaders().getFirst("X-MCP-Token");
		}
		if (header == null) {
			return false;
		}
		String presented = header.regionMatches(true, 0, "Bearer ", 0, 7)
				? header.substring(7).trim()
				: header.trim();
		return MessageDigest.isEqual(
				presented.getBytes(StandardCharsets.UTF_8),
				authToken.getBytes(StandardCharsets.UTF_8)
		);
	}

	private void serveEventStream(HttpExchange exchange) throws IOException {
		Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", "text/event-stream; charset=utf-8");
		headers.set("Cache-Control", "no-cache");
		headers.set("Connection", "keep-alive");
		addCors(headers);
		headers.set("Mcp-Session-Id", sessionId);
		exchange.sendResponseHeaders(200, 0);
		OutputStream out = exchange.getResponseBody();
		SseClient client = new SseClient(out);
		sseClients.add(client);
		client.comment("connected");
		try {
			while (!client.closed) {
				Thread.sleep(15000L);
				if (client.closed) {
					break;
				}
				client.comment("ping");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			sseClients.remove(client);
			client.close();
		}
	}

	/** Push a server-initiated MCP notification to every open SSE stream. */
	public void broadcast(JsonObject notification) {
		if (sseClients.isEmpty()) {
			return;
		}
		for (SseClient client : sseClients) {
			if (!client.send(notification)) {
				sseClients.remove(client);
			}
		}
	}

	/** Convenience wrapper for structured game events. */
	public void broadcastEvent(String type, String level, JsonObject event) {
		JsonObject data = event.deepCopy();
		if (!data.has("type")) {
			data.addProperty("type", type);
		}
		JsonObject params = new JsonObject();
		params.addProperty("level", level);
		params.addProperty("logger", "llmcraft");
		params.add("data", data);
		JsonObject notification = new JsonObject();
		notification.addProperty("jsonrpc", "2.0");
		notification.addProperty("method", "notifications/message");
		notification.add("params", params);
		broadcast(notification);
	}

	private void sendRpc(HttpExchange exchange, JsonElement json) throws IOException {
		String accept = exchange.getRequestHeaders().getFirst("Accept");
		boolean wantsSse = accept != null
				&& accept.toLowerCase(Locale.ROOT).contains("text/event-stream")
				&& !accept.toLowerCase(Locale.ROOT).contains("application/json");
		if (wantsSse) {
			String payload = "event: message\ndata: " + GSON.toJson(json) + "\n\n";
			Headers headers = exchange.getResponseHeaders();
			headers.set("Content-Type", "text/event-stream; charset=utf-8");
			headers.set("Cache-Control", "no-cache");
			addCors(headers);
			headers.set("Mcp-Session-Id", sessionId);
			byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		} else {
			sendJson(exchange, 200, json);
		}
	}

	private void sendJson(HttpExchange exchange, int status, JsonElement json) throws IOException {
		byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
		Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", "application/json; charset=utf-8");
		addCors(headers);
		headers.set("Mcp-Session-Id", sessionId);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private void sendText(HttpExchange exchange, int status, String text, String contentType) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", contentType);
		addCors(headers);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void addCors(Headers headers) {
		headers.set("Access-Control-Allow-Origin", "*");
		headers.set("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS");
		headers.set("Access-Control-Allow-Headers",
				"Content-Type, Authorization, X-MCP-Token, Mcp-Session-Id, MCP-Protocol-Version");
		headers.set("Access-Control-Expose-Headers", "Mcp-Session-Id");
	}

	private static byte[] readBody(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		int total = 0;
		while ((read = in.read(buffer)) != -1) {
			total += read;
			if (total > MAX_BODY_BYTES) {
				throw new IOException("body too large");
			}
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	private static JsonObject result(JsonElement id, JsonElement result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
		response.add("result", result);
		return response;
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		response.add("error", error);
		return response;
	}

	private static String getString(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static String escapeHtml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static final class SseClient {
		private final OutputStream out;
		private final Object lock = new Object();
		private volatile boolean closed;

		SseClient(OutputStream out) {
			this.out = out;
		}

		boolean send(JsonObject json) {
			synchronized (lock) {
				if (closed) {
					return false;
				}
				try {
					String payload = "event: message\ndata: " + GSON.toJson(json) + "\n\n";
					out.write(payload.getBytes(StandardCharsets.UTF_8));
					out.flush();
					return true;
				} catch (IOException e) {
					closed = true;
					return false;
				}
			}
		}

		void comment(String text) {
			synchronized (lock) {
				if (closed) {
					return;
				}
				try {
					out.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
					out.flush();
				} catch (IOException e) {
					closed = true;
				}
			}
		}

		void close() {
			synchronized (lock) {
				if (closed) {
					return;
				}
				closed = true;
				try {
					out.close();
				} catch (IOException ignored) {
				}
			}
		}
	}
}
