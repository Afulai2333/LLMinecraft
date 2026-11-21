# LLMinecraft MCP smoke test (PowerShell 7+).
# Usage: .\scripts\mcp-smoke.ps1
param(
    [string]$Url = "http://127.0.0.1:8765/mcp",
    [string]$Token = $env:LLMCRAFT_MCP_TOKEN
)

$ErrorActionPreference = "Stop"
$headers = @{ "Accept" = "application/json, text/event-stream" }
if ($Token) { $headers["Authorization"] = "Bearer $Token" }

function Invoke-Rpc([hashtable]$Body) {
    $json = $Body | ConvertTo-Json -Depth 20 -Compress
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -Method POST -Headers $headers `
        -ContentType "application/json" -Body $json
    if ($response.Content.TrimStart().StartsWith("event:")) {
        $data = ($response.Content -split "`n" | Where-Object { $_ -like "data:*" }) -join "`n"
        return (($data -replace "^data:\s?", "") | ConvertFrom-Json)
    }
    return ($response.Content | ConvertFrom-Json)
}

Write-Host "health..." -ForegroundColor Cyan
$health = Invoke-RestMethod -Uri ($Url -replace "/mcp$", "/health") -Headers $headers
$health | ConvertTo-Json -Compress | Write-Host

Write-Host "initialize..." -ForegroundColor Cyan
$init = Invoke-Rpc @{
    jsonrpc = "2.0"; id = 1; method = "initialize"
    params = @{ protocolVersion = "2025-06-18"; capabilities = @{}; clientInfo = @{ name = "llmcraft-smoke"; version = "1" } }
}
$init.result.serverInfo | ConvertTo-Json -Compress | Write-Host

Write-Host "tools/list..." -ForegroundColor Cyan
$list = Invoke-Rpc @{ jsonrpc = "2.0"; id = 2; method = "tools/list"; params = @{} }
Write-Host ("tools: " + $list.result.tools.Count)
$list.result.tools | ForEach-Object { " - " + $_.name }

Write-Host "tools/call get_state..." -ForegroundColor Cyan
$state = Invoke-Rpc @{
    jsonrpc = "2.0"; id = 3; method = "tools/call"
    params = @{ name = "get_state"; arguments = @{} }
}
if ($state.result.isError) {
    Write-Host $state.result.content[0].text -ForegroundColor Yellow
} else {
    $text = $state.result.content[0].text
    $obj = $text | ConvertFrom-Json
    "player: {0} at {1},{2},{3} ({4})" -f $obj.player.name, $obj.player.x, $obj.player.y, $obj.player.z, $obj.world.phase | Write-Host
}
Write-Host "OK" -ForegroundColor Green
