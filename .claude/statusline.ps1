# Claude Code statusLine renderer.
# Reads JSON from stdin (workspace.current_dir, model.display_name,
# context_window.used_percentage and optional used_tokens / total_tokens),
# prints one line:
#   <short-cwd>  <branch>  <model>@<effort>  ctx <usedK>k/<totalK>k (N%)
#
# - Worktree-aware: when cwd is under <repo>\.claude\worktrees\<wt>\,
#   renders as "<repo-leaf> [wt:<wt>]" instead of the full path so the
#   ctx block stays visible without truncation.
# - effortLevel is read from ~/.claude/settings.json (Claude Code does
#   not pass it in the statusLine JSON).
# - ctx is colored by threshold tuned to the operator's handoff workflow:
#   green <25%, yellow 25-39%, red >=40% (>=40% means it's time to
#   /session-handoff and switch sessions).
# - Absolute tokens shown only when present in the JSON; falls back
#   gracefully to "ctx N%" alone otherwise.

$inputJson = ($input -join '')
if (-not $inputJson) {
  Write-Output ''
  return
}
$json = $inputJson | ConvertFrom-Json

$cwd          = $json.workspace.current_dir
$model        = $json.model.display_name
$used         = $json.context_window.used_percentage

$usedTokens  = $null
$totalTokens = $null
if ($json.context_window.PSObject.Properties.Name -contains 'used_tokens')  { $usedTokens  = $json.context_window.used_tokens }
if ($json.context_window.PSObject.Properties.Name -contains 'total_tokens') { $totalTokens = $json.context_window.total_tokens }

# Branch via git, suppressing errors when not in a repo.
$branch = $null
try { $branch = (& git -C "$cwd" rev-parse --abbrev-ref HEAD 2>$null) } catch {}

# effortLevel from settings.json (not in the statusLine JSON payload).
$effort = $null
$settingsPath = Join-Path $env:USERPROFILE '.claude\settings.json'
if (Test-Path $settingsPath) {
  try {
    $settings = Get-Content $settingsPath -Raw | ConvertFrom-Json
    if ($settings.PSObject.Properties.Name -contains 'effortLevel') { $effort = $settings.effortLevel }
  } catch {}
}

# Shorten cwd. Detect "<repo>\.claude\worktrees\<wt>\..." and render
# "<repo-leaf> [wt:<wt>]"; otherwise show the leaf folder name.
$cwdNorm = $cwd -replace '/', '\'
$shortCwd = Split-Path $cwd -Leaf
if ($cwdNorm -match '^(.+)\\\.claude\\worktrees\\([^\\]+)(\\.*)?$') {
  $mainRepoPath = $Matches[1]
  $wtName = $Matches[2]
  $shortCwd = (Split-Path $mainRepoPath -Leaf) + " [wt:$wtName]"
}

$parts = @($shortCwd)
if ($branch) { $parts += $branch }

$modelLabel = $model
if ($effort) { $modelLabel = "$model@$effort" }
if ($modelLabel) { $parts += $modelLabel }

# Context block with ANSI color by threshold.
if ($null -ne $used) {
  $pct = [math]::Round($used)
  $esc = [char]27
  $color = "$esc[32m"                              # green:  <25%
  if ($pct -ge 40)    { $color = "$esc[31m" }      # red:    >=40% (handoff time)
  elseif ($pct -ge 25){ $color = "$esc[33m" }      # yellow: 25-39% (prep handoff)
  $reset = "$esc[0m"

  if ($usedTokens -and $totalTokens) {
    $usedK  = [math]::Round($usedTokens  / 1000)
    $totalK = [math]::Round($totalTokens / 1000)
    $ctx = "ctx ${color}${usedK}k/${totalK}k (${pct}%)${reset}"
  } elseif ($usedTokens) {
    $usedK = [math]::Round($usedTokens / 1000)
    $ctx = "ctx ${color}${usedK}k (${pct}%)${reset}"
  } else {
    $ctx = "ctx ${color}${pct}%${reset}"
  }
  $parts += $ctx
}

$parts -join '  '
