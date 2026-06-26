# init.ps1 — Verificacion e inicializacion del entorno del harness Uncle Bob.
#
# Lo ejecuta el agente AL COMENZAR una sesion SDD y ANTES de declarar
# cualquier feature como `done`. Si termina con [FAIL], la sesion no debe
# avanzar al TDD.
#
# No corre PIT (es caro): la prueba de mutacion la lanza el agente
# `mutation_tester` al final del ciclo de una feature.
#
# Uso: powershell.exe -NoProfile -ExecutionPolicy Bypass -File init.ps1

$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
Set-Location $root
$exit = 0

function Write-Ok($m)   { Write-Host "[OK]    $m"   -ForegroundColor Green }
function Write-Warn($m) { Write-Host "[WARN]  $m"   -ForegroundColor Yellow }
function Write-Fail($m) { Write-Host "[FAIL]  $m"   -ForegroundColor Red }

Write-Host "-- 1. Verificando entorno --------------------------------"

$java = Get-Command java -ErrorAction SilentlyContinue
if ($null -eq $java) {
  Write-Fail "java no esta en el PATH"; exit 1
}
$javaVer = (& java -version 2>&1 | Select-Object -First 1)
Write-Ok "java disponible: $javaVer"

$mvnd = Get-Command mvnd -ErrorAction SilentlyContinue
if ($null -eq $mvnd) {
  Write-Warn "mvnd no esta en el PATH (la memoria del proyecto recomienda mvnd; ./mvnw funciona como fallback)"
} else {
  Write-Ok "mvnd disponible"
}

Write-Host ""
Write-Host "-- 2. Verificando archivos base del arnes ----------------"

$baseFiles = @(
  'AGENTS.md',
  'CLAUDE.md',
  'CHECKPOINTS.md',
  'feature_list.json',
  'project-spec.md',
  'progress/current.md',
  'progress/history.md',
  'docs/uncle-bob/workflow.md',
  'docs/uncle-bob/tdd.md',
  'docs/uncle-bob/gherkin.md',
  'docs/uncle-bob/mutation-testing.md',
  'docs/uncle-bob/architecture.md',
  'docs/uncle-bob/conventions.md',
  'docs/uncle-bob/verification.md',
  '.claude/agents/craftsman_lead.md',
  '.claude/agents/spec_partner.md',
  '.claude/agents/gherkin_author.md',
  '.claude/agents/tdd_craftsman.md',
  '.claude/agents/judge.md',
  '.claude/agents/mutation_tester.md'
)
foreach ($f in $baseFiles) {
  if (Test-Path (Join-Path $root $f)) {
    Write-Ok "Existe $f"
  } else {
    Write-Fail "Falta archivo base: $f"; $exit = 1
  }
}

# El plugin de mutacion vive en el pom del backend (reemplaza a stryker.conf.json)
$pom = Join-Path $root 'backend/pom.xml'
if (Test-Path $pom) {
  if (Select-String -Path $pom -SimpleMatch 'pitest-maven' -Quiet) {
    Write-Ok "backend/pom.xml declara pitest-maven"
  } else {
    Write-Fail "backend/pom.xml no declara el plugin pitest-maven"; $exit = 1
  }
} else {
  Write-Fail "No existe backend/pom.xml"; $exit = 1
}

Write-Host ""
Write-Host "-- 3. Validando feature_list.json y escenarios -----------"

try {
  $data = Get-Content (Join-Path $root 'feature_list.json') -Raw | ConvertFrom-Json
} catch {
  Write-Fail "feature_list.json no parsea: $($_.Exception.Message)"; exit 1
}

$valid = @('pending','spec_ready','in_progress','done','blocked')
$requiresSpec = @('spec_ready','in_progress','done')
$inProgress = @($data.features | Where-Object { $_.status -eq 'in_progress' })
if ($inProgress.Count -gt 1) {
  Write-Fail "Hay $($inProgress.Count) features en in_progress (maximo 1)"; $exit = 1
}
foreach ($f in $data.features) {
  if ($valid -notcontains $f.status) {
    Write-Fail "Estado invalido en feature $($f.id): $($f.status)"; $exit = 1
  }
  if ($f.sdd -and ($requiresSpec -contains $f.status)) {
    $ff = Join-Path $root ("features/" + $f.name + ".feature")
    if (-not (Test-Path $ff)) {
      Write-Fail "feature $($f.id) ($($f.name)) en $($f.status) sin features/$($f.name).feature"; $exit = 1
    }
  }
}
if ($exit -eq 0) {
  Write-Ok "feature_list.json valido ($($data.features.Count) features)"
  Write-Ok "Escenarios .feature presentes para features sdd no-pending"
}

Write-Host ""
Write-Host "-- 4. Estado del codigo del dominio ----------------------"

$jooqGen = Join-Path $root 'backend/target/generated-sources/jooq'
$domainSrc = Join-Path $root 'backend/src/main/java/com/myfinanceview/domain'
$domainTests = Join-Path $root 'backend/src/test/java/com/myfinanceview/domain'

if (-not (Test-Path $jooqGen)) {
  Write-Warn "codegen jOOQ no ha corrido (backend/target/generated-sources/jooq ausente). El modulo no compila en frio; corre el codegen (Docker/DB) antes del TDD. Esto NO bloquea spec/Gherkin."
} else {
  Write-Ok "codegen jOOQ presente"
}

if (Test-Path $domainTests) {
  Write-Ok "Existe el espejo de tests del dominio"
} elseif (Test-Path $domainSrc) {
  Write-Warn "Existe domain/ pero aun no hay tests espejo (ninguna feature SDD cerrada todavia)"
} else {
  Write-Warn "Aun no hay codigo de dominio puro nuevo (harness recien instalado)"
}

Write-Host ""
Write-Host "-- 5. Resumen --------------------------------------------"
if ($exit -eq 0) {
  Write-Ok "Entorno del arnes coherente. Puedes empezar a trabajar."
} else {
  Write-Fail "El arnes NO esta coherente. Resuelve los errores antes de avanzar."
}
exit $exit
