#requires -Version 5.1
<#
.SYNOPSIS
    MyFinanceView preflight -- session-start repo-state report.

.DESCRIPTION
    Invoked by the Claude Code SessionStart hook (`.claude/settings.json`).
    Produces a deterministic, single-screen report tagged
    [OK] / [WARN] / [FAIL] / [SKIP] covering:

      - active change count under openspec/changes/ (excluding archive/)
      - ./mvnw -q compile exit status (fast compile, NOT verify)
      - working tree cleanliness (git status --porcelain empty?)
      - current branch + last commit hash + subject
      - per-active-change presence of proposal.md / design.md / tasks.md / progress.md
      - last verified Supabase backup timestamp (R2 status/last-success.json via rclone;
        gracefully [SKIP]s when rclone is not installed)

    Exit code = count of [FAIL] lines emitted. [WARN] and [SKIP] are NOT failures.
    See openspec/changes/harness-progress-tracking/design.md Decision 6 for rationale.

    Designed to run on stock Windows PowerShell 5.1 AND PowerShell 7+ -- no
    PS7-only syntax (no ??=, no ternary, no -AsHashtable on ConvertFrom-Json).

.NOTES
    Project: MyFinanceView -- process tooling, no Supabase writes.
    Spec:    openspec/changes/harness-progress-tracking/specs/harness-progress-tracking/spec.md
#>

[CmdletBinding()]
param()

# Always operate from the repo root regardless of caller's CWD. The script
# lives at <repo>\scripts\preflight.ps1, so Split-Path twice = repo root.
$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location -Path $repoRoot

# ---------------------------------------------------------------------------
# Counters (script scope so Write-Check can update them)
# ---------------------------------------------------------------------------
$script:OkCount   = 0
$script:WarnCount = 0
$script:FailCount = 0
$script:SkipCount = 0

# ---------------------------------------------------------------------------
# Write-Check -- emit a tagged line + update counters.
# Format: "[TAG]    <label> -- <detail>"  (tag column padded to 8 chars)
# ---------------------------------------------------------------------------
function Write-Check {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true)]
        [ValidateSet('OK','WARN','FAIL','SKIP')]
        [string]$Tag,

        [Parameter(Mandatory=$true)]
        [string]$Label,

        [string]$Detail = ''
    )

    $tagField = ('[' + $Tag + ']').PadRight(8)

    if ([string]::IsNullOrEmpty($Detail)) {
        Write-Output ("{0}{1}" -f $tagField, $Label)
    } else {
        Write-Output ("{0}{1} -- {2}" -f $tagField, $Label, $Detail)
    }

    switch ($Tag) {
        'OK'   { $script:OkCount++ }
        'WARN' { $script:WarnCount++ }
        'FAIL' { $script:FailCount++ }
        'SKIP' { $script:SkipCount++ }
    }
}

# ---------------------------------------------------------------------------
# Check 1 -- active changes count
# ---------------------------------------------------------------------------
function Test-ActiveChanges {
    $changesDir = Join-Path $repoRoot 'openspec\changes'
    if (-not (Test-Path $changesDir)) {
        Write-Check -Tag 'SKIP' -Label 'active changes' -Detail "openspec/changes/ not found"
        return
    }

    $active = Get-ChildItem -Path $changesDir -Directory -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -ne 'archive' }
    $count = @($active).Count

    if ($count -eq 0) {
        Write-Check -Tag 'OK' -Label 'active changes' -Detail 'no active changes'
    } elseif ($count -eq 1) {
        Write-Check -Tag 'OK' -Label 'active changes' -Detail "1 active ($($active[0].Name))"
    } else {
        $names = ($active | ForEach-Object { $_.Name }) -join ', '
        Write-Check -Tag 'WARN' -Label 'active changes' `
            -Detail "$count active changes -- confirm intentional parallel work ($names)"
    }
}

# ---------------------------------------------------------------------------
# Check 2 -- mvn compile (fast -- NOT verify; project rule base-standards.md §5)
# ---------------------------------------------------------------------------
function Test-MvnCompile {
    $mvnw = Join-Path $repoRoot 'mvnw.cmd'
    if (-not (Test-Path $mvnw)) {
        $mvnw = Join-Path $repoRoot 'mvnw'
        if (-not (Test-Path $mvnw)) {
            Write-Check -Tag 'SKIP' -Label 'mvn compile' -Detail "mvnw/mvnw.cmd not found at repo root"
            return
        }
    }

    # Capture both streams to a temp file; we want the stderr tail on failure.
    $tmpOut = [IO.Path]::GetTempFileName()
    $tmpErr = [IO.Path]::GetTempFileName()
    try {
        $proc = Start-Process -FilePath $mvnw -ArgumentList @('-q','compile') `
                              -WorkingDirectory $repoRoot -NoNewWindow -Wait -PassThru `
                              -RedirectStandardOutput $tmpOut -RedirectStandardError $tmpErr
        if ($proc.ExitCode -eq 0) {
            Write-Check -Tag 'OK' -Label 'mvn compile'
        } else {
            $errTail = ''
            if (Test-Path $tmpErr) {
                $lines = Get-Content $tmpErr -ErrorAction SilentlyContinue
                if ($lines) {
                    $tail = $lines | Select-Object -Last 5
                    $errTail = ($tail -join ' | ').Trim()
                }
            }
            if ([string]::IsNullOrEmpty($errTail)) {
                # Fall back to stdout tail -- mvn often writes the failure summary there with -q
                if (Test-Path $tmpOut) {
                    $lines = Get-Content $tmpOut -ErrorAction SilentlyContinue
                    if ($lines) {
                        $tail = $lines | Select-Object -Last 5
                        $errTail = ($tail -join ' | ').Trim()
                    }
                }
            }
            if ([string]::IsNullOrEmpty($errTail)) { $errTail = "exit code $($proc.ExitCode)" }
            Write-Check -Tag 'FAIL' -Label 'mvn compile failed' -Detail $errTail
        }
    } catch {
        Write-Check -Tag 'FAIL' -Label 'mvn compile' -Detail "could not invoke mvnw: $($_.Exception.Message)"
    } finally {
        Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue
        Remove-Item $tmpErr -Force -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------
# Check 3 -- working tree cleanliness (WARN, not FAIL -- in-progress work is normal)
# ---------------------------------------------------------------------------
function Test-WorkingTree {
    try {
        $porcelain = & git status --porcelain 2>$null
    } catch {
        Write-Check -Tag 'SKIP' -Label 'working tree' -Detail 'git not available'
        return
    }

    if ([string]::IsNullOrEmpty($porcelain)) {
        Write-Check -Tag 'OK' -Label 'working tree clean'
        return
    }

    $lines = @($porcelain -split "`n" | Where-Object { $_ -ne '' })
    $count = $lines.Count
    $first3 = ($lines | Select-Object -First 3) -join '; '
    Write-Check -Tag 'WARN' -Label 'working tree' `
        -Detail "$count modified/untracked files -- $first3"
}

# ---------------------------------------------------------------------------
# Check 4 -- branch + last commit (informational only)
# ---------------------------------------------------------------------------
function Test-BranchAndCommit {
    try {
        $branch = (& git branch --show-current 2>$null).Trim()
        if ([string]::IsNullOrEmpty($branch)) { $branch = '(detached HEAD)' }
        $commit = (& git log -1 --format='%h %s' 2>$null).Trim()
        if ([string]::IsNullOrEmpty($commit)) { $commit = '(no commits yet)' }
        Write-Check -Tag 'OK' -Label 'git head' -Detail "$branch @ $commit"
    } catch {
        Write-Check -Tag 'SKIP' -Label 'git head' -Detail 'git not available'
    }
}

# ---------------------------------------------------------------------------
# Check 5 -- per-active-change artefact presence
# ---------------------------------------------------------------------------
function Test-ChangeArtefacts {
    $changesDir = Join-Path $repoRoot 'openspec\changes'
    if (-not (Test-Path $changesDir)) { return }

    $active = Get-ChildItem -Path $changesDir -Directory -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -ne 'archive' }
    if (@($active).Count -eq 0) { return }

    $required = @('proposal.md','design.md','tasks.md','progress.md')
    foreach ($change in $active) {
        $missing = @()
        foreach ($file in $required) {
            $path = Join-Path $change.FullName $file
            if (-not (Test-Path $path)) { $missing += $file }
        }
        if ($missing.Count -eq 0) {
            Write-Check -Tag 'OK' -Label "$($change.Name) artefacts" -Detail 'complete'
        } else {
            Write-Check -Tag 'FAIL' -Label "$($change.Name) missing" -Detail ($missing -join ', ')
        }
    }
}

# ---------------------------------------------------------------------------
# Check 6 -- last verified Supabase backup (rclone optional)
# ---------------------------------------------------------------------------
function Test-BackupFreshness {
    $rclone = Get-Command rclone -ErrorAction SilentlyContinue
    if ($null -eq $rclone) {
        Write-Check -Tag 'SKIP' -Label 'supabase backup freshness' `
            -Detail 'rclone not installed -- backup freshness check skipped (install rclone to enable)'
        return
    }

    $remotePath = 'r2:my-finance-view-backups/status/last-success.json'
    try {
        $json = & rclone cat $remotePath 2>$null
        if ([string]::IsNullOrEmpty($json)) {
            Write-Check -Tag 'FAIL' -Label 'supabase backup freshness' `
                -Detail "could not read $remotePath -- R2 unreachable or object missing"
            return
        }
        $obj = $json | ConvertFrom-Json
        $tsString = $obj.timestamp
        if ([string]::IsNullOrEmpty($tsString)) {
            Write-Check -Tag 'WARN' -Label 'supabase backup freshness' `
                -Detail "last-success.json missing 'timestamp' field"
            return
        }
        $ts = [DateTimeOffset]::Parse($tsString).UtcDateTime
        $ageHours = [int]([DateTime]::UtcNow - $ts).TotalHours
        if ($ageHours -le 24) {
            Write-Check -Tag 'OK' -Label 'supabase backup' -Detail "last backup $ageHours h ago"
        } else {
            Write-Check -Tag 'WARN' -Label 'supabase backup' `
                -Detail "last backup $ageHours h ago -- > 24 h"
        }
    } catch {
        Write-Check -Tag 'FAIL' -Label 'supabase backup freshness' `
            -Detail "rclone present but call failed: $($_.Exception.Message)"
    }
}

# ---------------------------------------------------------------------------
# Run all checks
# ---------------------------------------------------------------------------
Test-ActiveChanges
Test-MvnCompile
Test-WorkingTree
Test-BranchAndCommit
Test-ChangeArtefacts
Test-BackupFreshness

# Summary line -- deterministic format the operator (and Claude) can scan.
Write-Output ("=== preflight: {0} passes, {1} warns, {2} skips, {3} fails ===" `
    -f $script:OkCount, $script:WarnCount, $script:SkipCount, $script:FailCount)

exit $script:FailCount
