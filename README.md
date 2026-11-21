# LLMinecraft

A Fabric mod for **Minecraft: Java Edition 26.3** that lets a large language model play the
game by calling **MCP tools**. The mod embeds a local MCP server in the client and maps 34
tools onto a real player: see the world, walk, mine, build, craft, fight, eat, chat and take
screenshots. Any MCP-capable agent (Claude Desktop, Cursor, a custom loop) can connect; no
external bridge process is required for HTTP-native clients.

```text
Agent / LLM  -- MCP Streamable HTTP -->  LLMinecraft (Fabric client)
                                              |
                                              +-- get_state / scan_* / screenshot
                                              +-- goto / move / look / jump
                                              +-- mine / place / use / attack
                                              +-- craft / equip / chat / sleep
                                              |
                                         real Minecraft player
```

## Features

- **Local MCP server** on `http://127.0.0.1:8765/mcp` with JSON-RPC, tool discovery,
  authentication and health endpoint.
- **34 tools** covering perception, movement, block interaction, inventory, crafting,
  combat, chat, screenshots and full GUI/menu operation.
- **Menus are playable by the agent**: main menu, onboarding, world list, world creation,
  options, container screens and dialog buttons, via widget indexes or GUI coordinates.
- **Real actions, not commands.** Movement uses the vanilla input system, mining uses
  `MultiPlayerGameMode`, crafting uses the recipe book, attacks respect cooldowns.
- **Pathfinding** that walks, steps up, drops and avoids lava/fire/cactus.
- **Auto tool selection** and **auto camera aiming** for mining/placing.
- **Screenshots as MCP image content** for vision-capable models.
- **In-game controls**: `/llmcraft status|start|stop|restart|token|tools|freeze`.
- **Server-pushed events**: world join, damage, low health, death, respawn, day/night,
  threats, chat and control handovers are pushed over the MCP SSE stream even when the model
  is not polling. `get_events` provides the same history for clients without SSE.
- **Creature and food awareness**: all living creatures are scanned, food animals are
  classified, and a `food_nearby` event is pushed when hunger drops. This is in addition to
  `scan_entities` / `attack`.
- **Threat awareness**: hostile mobs are scanned continuously and shown in `get_state`;
  warnings appear in the action bar and `read_chat`. Idle auto-defense uses a sword/axe,
  never hits creepers. Mining holds the real attack key, so survival break progress works.
- **Input lock + human handover**: once the agent calls a tool, physical keyboard/mouse
  input is locked. The OS cursor is released (not grabbed), so the window does not trap the
  mouse and other programs remain usable. The agent can request a handover, which shows an
  Agree/Refuse dialog; only Agree unlocks input, and Ctrl+C returns control to the agent.
- **Idle tick freeze**: while the model is thinking, the integrated server pauses so mobs,
  hunger, weather and daylight do not advance between tool calls. Any tool call resumes it.
- **Safe defaults**: loopback bind, optional bearer token, chat toggle, action timeouts.
- Works in **singleplayer and on any multiplayer server** (the client is the player; the
  server does not need the mod).

## Requirements

| Component | Version |
| --- | --- |
| Minecraft Java Edition | 26.3 |
| Fabric Loader | 0.20.1+ |
| Fabric API | 0.166.2+26.3 |
| Java | 25 |

## Install

1. Install Fabric Loader for Minecraft 26.3.
2. Put `llmcraft-0.1.0.jar` and `fabric-api-0.166.2+26.3.jar` into `.minecraft/mods/`.
3. Launch the game and join a world.
4. Look for this log line:

   ```text
   MCP server listening on http://127.0.0.1:8765/mcp (34 tools)
   ```

5. Check in game with `/llmcraft status`.

The jar is at `build/libs/llmcraft-0.1.0.jar`.

## Build from source

```bash
cd LLMinecraft
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

On Windows:

```powershell
$env:JAVA_HOME = 'D:\jdk-25'
.\gradlew.bat build
```

The dev client can be launched with a test world:

```powershell
.\gradlew.bat runClient --args="--quickPlaySingleplayer LLMTest"
```

## Configuration

`config/llmcraft.json` is created on first launch:

```json
{
  "enabled": true,
  "host": "127.0.0.1",
  "port": 8765,
  "authToken": "<random hex>",
  "requireToken": false,
  "allowRemote": false,
  "actionTimeoutSeconds": 45,
  "maxActionSeconds": 30,
  "allowChat": true,
  "showChatFeedback": true,
  "threatDetection": true,
  "autoDefend": true,
  "threatRadius": 16,
  "creatureDetection": true,
  "creatureRadius": 24,
  "freezeWhenIdle": true,
  "freezeDelayTicks": 10,
  "screenshots": true,
  "screenshotScale": 2,
  "maxScanRadius": 64
}
```

- `enabled`: start the MCP listener with the client.
- `host`/`port`: bind address. Non-loopback hosts are forced back to loopback unless
  `allowRemote` is true.
- `authToken` / `requireToken`: require `Authorization: Bearer <token>` on every request.
  `/llmcraft token` prints the token in game.
- `actionTimeoutSeconds`: how long the HTTP request waits for a tool. Longer walks should
  be split.
- `maxActionSeconds`: cap for a single movement/mining action.
- `showChatFeedback`: print MCP start/stop messages to the player.
- `threatDetection`: scan for hostile mobs every 0.5s; `get_state` exposes `threats`
  (`level`, nearest hostiles, distance, health, targeting). Warnings go to the action bar
  and `read_chat` as `kind="threat"`.
- `autoDefend`: when the agent is idle and a non-creeper hostile is within ~3 blocks, use a
  sword/axe and attack every 12 ticks. Creepers are only warned about, never hit.
- `threatRadius`: scan radius, 4鈥?2, default 16.
- `creatureDetection`: detect all non-player living creatures (cows, pigs, sheep, chickens,
  fish, villagers, etc.) in `get_state.creatures`; food animals are also exposed as
  `food_sources`. When hunger is 鈮?4, a `food_nearby` event is pushed automatically.
- `creatureRadius`: creature/food scan radius, 4鈥?8, default 24.
- `freezeWhenIdle`: pause the integrated server when no tool is running (singleplayer /
  LAN host only). This keeps the world still while the model reasons. Disable if a human is
  playing in the same world. Toggle in game with `/llmcraft freeze`.
- `freezeDelayTicks`: quiet ticks before freezing; 10 ticks = 0.5s.
- `screenshots`: allow the `screenshot` tool; `screenshotScale` downscales.
- `maxScanRadius`: hard cap for `scan_blocks`.

Restart after editing: `/llmcraft restart`.

## Connect an agent

### HTTP-native MCP client

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

### stdio-only MCP client

Use the Node bridge from the companion skill:

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "node",
      "args": ["path/to/LLMinecraft-skill/scripts/mcp-client.mjs"],
      "env": { "LLMCRAFT_MCP_URL": "http://127.0.0.1:8765/mcp" }
    }
  }
}
```

See [LLMinecraft-skill/references/connecting.md](https://github.com/Afulai2333/LLMinecraft-skill/blob/main/references/connecting.md) for details and troubleshooting.

### Manual smoke test

```bash
curl -s http://127.0.0.1:8765/health
curl -s -X POST http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

## Tools

| Tool | Purpose |
| --- | --- |
| `get_state` | full player/world/inventory/view snapshot |
| `scan_blocks` | find blocks by id, name or tag |
| `scan_entities` | find nearby entities |
| `get_block` | inspect one block |
| `inventory` | read all slots |
| `gui_state` | read the open GUI, container slots, cursor and widgets |
| `gui_click` | click a container slot or GUI button |
| `gui_type` | type into the focused GUI field (optionally replace) |
| `gui_key` | press Escape/Enter/Tab/arrows in the current screen |
| `gui_scroll` | scroll lists such as the world list or options |
| `look` | rotate camera to yaw/pitch, point or entity |
| `move` | hold forward/back/left/right for seconds |
| `jump` | jump in place or forward |
| `goto` | A* walk to x/z (optional y) |
| `mine` | break a block with the best hotbar tool |
| `place` | place a block against a support |
| `use` | right-click block/entity/item (eat, open, interact) |
| `attack` | attack an entity, respecting cooldown |
| `select_item` | select hotbar slot |
| `equip` | move a matching item to the hotbar |
| `craft` | craft via the recipe book (2脳2 or open 3脳3) |
| `drop` | drop held item |
| `chat` | send chat or a command |
| `read_chat` | recent chat/system/actionbar messages |
| `request_user_takeover` | open an Agree/Refuse dialog asking the human to take over |
| `control_status` | read who controls the player and whether input is locked |
| `get_events` | read pushed game events since an id |
| `resume_control` | cancel a pending takeover request |
| `screenshot` | capture the frame as an MCP image |
| `wait` | let game time pass |
| `sleep` | run the world for a specific duration (freeze suspended) |
| `sleep_in_bed` | sleep in a nearby bed to skip the night |
| `close_screen` | close a GUI/container |
| `respawn` | respawn when dead |

Full schemas: [LLMinecraft-skill/references/tools.md](https://github.com/Afulai2333/LLMinecraft-skill/blob/main/references/tools.md), or call `tools/list`.

## Architecture

```text
src/main/java/dev/llmcraft/
  LLMinecraft                 mod entrypoint
  config/LLMinecraftConfig    JSON config
  mcp/McpServer               HTTP + JSON-RPC + SSE content negotiation
  mcp/McpToolRegistry         tool registry and tools/list rendering
  mcp/ToolResult              MCP content result (text + image)

src/client/java/dev/llmcraft/client/
  LLMinecraftClient           events, commands, chat log
  ClientBridge                client-thread marshalling + action queue
  ClientAction                one multi-tick action contract
  McpServerManager            listener lifecycle
  actions/                    movement, mining, placing, crafting, combat...
  tools/ClientTools           tool definitions and argument schemas
  tools/StateSnapshot         perception JSON
  util/Pathfinder, BlockUtil, InputController, ItemUtil
```

**Threading.** MCP HTTP handlers never touch game state. Read tools are posted to the
client thread with `Minecraft.execute`. Physical actions are queued and ticked from
`ClientTickEvents.START_CLIENT_TICK`; one action runs at a time. The HTTP call waits for a
`CompletableFuture` with a configurable timeout. This is why a tool can take seconds and why
calls do not compose automatically.

**Why the client?** A client-side mod can use the real input pipeline, `MultiPlayerGameMode`,
the recipe book, screenshots and the local player, and it works on servers that do not have
the mod. A server-side bot would need a fake connection and would not see the rendered world.

## Security

The MCP endpoint controls a real game account and chat. Defaults bind to loopback and do
not require a token, which is convenient for local agents. If anything other than a local
process can reach the port:

1. Set `requireToken` to `true`.
2. Use a long random `authToken`.
3. Keep the endpoint behind SSH/VPN; do not port-forward it to the internet.
4. Consider `allowChat=false` and `screenshots=false` for unattended runs.

The bearer token is stored in plain text in `config/llmcraft.json`; protect that file.

## Limitations

- `goto` does not mine, bridge, open doors, swim long distances or handle complex parkour.
- `craft` relies on the recipe book and shift-click; it cannot automate arbitrary container
  layouts or furnaces.
- Screenshots include the HUD and chat and can be large.
- Chat/commands are subject to server permissions and moderation.
- There is no anti-cheat bypass: the mod acts as the player, and servers may still reject
  impossible movements.

## License

MIT. Not an official Minecraft product. Not approved by or associated with Mojang.
