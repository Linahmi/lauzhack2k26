param(
    [string]$HostAddress = '127.0.0.1',
    [int]$Port = 8787,
    [string[]]$AllowClient = @()
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:GEMINI_API_KEY)) {
    throw 'GEMINI_API_KEY is unavailable in this terminal. Run this script from the PowerShell terminal where you configured it. Do not save the key in a file.'
}
if ([string]::IsNullOrWhiteSpace($env:GEMINI_LIVE_MODEL)) {
    throw 'Set GEMINI_LIVE_MODEL to a project-accessible native-audio Live model. Use: python .\speech-token-service\server.py --models'
}
$routesServerArgs = @((Join-Path $PSScriptRoot 'server.py'), '--host', $HostAddress, '--port', "$Port")
foreach ($routesClient in $AllowClient) { $routesServerArgs += @('--allow-client', $routesClient) }
python @routesServerArgs
