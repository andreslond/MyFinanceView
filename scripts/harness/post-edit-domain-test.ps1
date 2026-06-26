# post-edit-domain-test.ps1 — Hook PostToolUse (Edit|Write) del harness Uncle Bob.
#
# Acotado al dominio puro: SOLO corre tests cuando el archivo editado esta
# bajo backend/src/.../domain/** (prod o test). Para cualquier otro archivo
# (frontend, infra, docs, controllers, repos) es un no-op instantaneo.
#
# Best-effort y NO bloqueante: siempre sale con codigo 0. Si mvnd no esta o
# el modulo no compila (codegen jOOQ pendiente), imprime una nota corta y
# sigue. El proyecto retiro deliberadamente un hook intrusivo antes; este se
# mantiene barato y vetable (borralo de .claude/settings.json si molesta).

$ErrorActionPreference = 'SilentlyContinue'

# El payload del hook llega por stdin como JSON.
$raw = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }

try { $payload = $raw | ConvertFrom-Json } catch { exit 0 }

$path = $null
if ($payload.tool_input) {
  if ($payload.tool_input.file_path) { $path = [string]$payload.tool_input.file_path }
  elseif ($payload.tool_input.path)  { $path = [string]$payload.tool_input.path }
}
if (-not $path) { exit 0 }

$norm = $path -replace '\\','/'
# Solo nos importa el dominio puro Java.
if ($norm -notmatch '/com/myfinanceview/domain/' -or $norm -notmatch '\.java$') { exit 0 }

$mvnd = Get-Command mvnd -ErrorAction SilentlyContinue
if ($null -eq $mvnd) {
  Write-Host "[harness] (edit en domain) mvnd no esta en PATH; salto los tests del dominio."
  exit 0
}

# Localiza el backend desde la raiz del repo (este script vive en scripts/harness/).
$backend = Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'backend'
if (-not (Test-Path $backend)) { exit 0 }

Push-Location $backend
$out = & mvnd -q -o test "-Dtest=com.myfinanceview.domain.**" 2>&1
Pop-Location

$tail = ($out | Select-Object -Last 3) -join "`n"
Write-Host "[harness] tests del dominio (edit en domain):"
Write-Host $tail
exit 0
