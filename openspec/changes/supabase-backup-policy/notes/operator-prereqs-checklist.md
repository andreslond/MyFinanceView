# §1 Operator prereqs — step-by-step checklist

> **STATUS: CLOSED 2026-06-07.** Todos los 11 pasos resueltos (con P3-papel y P9-Kuma omitidos por decisión — ver tabla v1 cuts abajo). El recipient público committeado en `0ff85ca` y los 9 task boxes §1.1-§1.9 flipados a [x] en `tasks.md`. **Esta checklist se mantiene solo como referencia histórica / runbook de rotación** — si un secret se rota en el futuro, el operador re-ejecuta solo el paso correspondiente.
>
> **No vuelvas a correr esta checklist desde cero.** Si la próxima sesión te dice "ejecutar §1 manual", el operador ya lo hizo: verifica el banner aquí y `progress.md` → decision log "§1 operator prereqs CLOSED (2026-06-07)" antes de proponer cualquier re-trabajo.
>
> Próximo bloque tras §1 = §6 (VPS Docker build) + §8 (deploy + n8n credentials). Ver `tasks.md`.

---

> Imprime esto o ábrelo en una pestaña aparte mientras trabajas. Marca cada `[ ]` cuando termines.
> Tiempo estimado total: 30-45 min.
>
> **Valores a capturar al final:** todos los que aparezcan como `→ guarda en gestor de contraseñas como <NOMBRE>` van eventualmente a `scripts/backup/.env.local` en el VPS (§8). No los pegues en el repo ni los pierdas — son irrecuperables (las 10 GB de transacciones también lo son si pierdes la identidad age).

---

## Hoja de captura — llena conforme avances

| Variable | Valor | Origen |
|---|---|---|
| `BACKUP_R2_ACCOUNT_ID` | _________________ | Paso 4 |
| `BACKUP_R2_ACCESS_KEY_ID` | _________________ | Paso 4 |
| `BACKUP_R2_SECRET_ACCESS_KEY` | _________________ | Paso 4 |
| `BACKUP_DB_PASSWORD` | _________________ | Paso 8 |
| `MYFINANCE_BACKUP_AGE_IDENTITY` (contenido completo) | _________________ | Paso 2 |
| `MYFINANCE_BACKUP_NTFY_TOPIC` | _________________ | Paso 7 |
| `MYFINANCE_BACKUP_RESEND_API_KEY` | _________________ | Paso 6 |
| `MYFINANCE_BACKUP_ALERT_FROM` (= `alerts@datachefnow.com`) | _________________ | Paso 6 |
| `MYFINANCE_BACKUP_ALERT_TO` (operator inbox) | _________________ | Paso 6 |
| `MYFINANCE_BACKUP_RUNNER_SECRET` | _________________ | Paso 10 |

> **v1 cuts (operator decision 2026-06-01):** `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD` (replaced by Resend) and `MYFINANCE_BACKUP_KUMA_PUSH_URL` (Kuma deferred) are no longer captured — see Paso 6 + Paso 9 below.

---

## Paso 1 — Instalar `age` en Windows PC

- [ ] Abre PowerShell.
- [ ] Ejecuta:
  ```powershell
  winget install FiloSottile.age
  ```
  Alternativa si winget falla: descarga el binario de https://github.com/FiloSottile/age/releases y descomprímelo a un directorio en `PATH`.
- [ ] Confirma:
  ```powershell
  age --version
  age-keygen --version
  ```
  Ambos deben devolver una versión sin error.

---

## Paso 2 — Generar identidad age primaria

- [ ] Crea el directorio destino:
  ```powershell
  New-Item -ItemType Directory -Force "$env:USERPROFILE\.config\myfinance-backup" | Out-Null
  ```
- [ ] Genera la identidad:
  ```powershell
  age-keygen -o "$env:USERPROFILE\.config\myfinance-backup\age-identity-primary.txt"
  ```
- [ ] Captura el recipient público (la línea que empieza con `age1...`):
  ```powershell
  age-keygen -y "$env:USERPROFILE\.config\myfinance-backup\age-identity-primary.txt"
  ```
  → **Guarda el output** (línea `age1...`). Va a `scripts/backup/recipients/primary.txt` en el repo, sobreescribiendo el placeholder.
- [ ] Captura el contenido completo del archivo identidad (la línea `AGE-SECRET-KEY-1...`):
  ```powershell
  Get-Content "$env:USERPROFILE\.config\myfinance-backup\age-identity-primary.txt"
  ```
  → **Guarda como `MYFINANCE_BACKUP_AGE_IDENTITY` en gestor de contraseñas**. Va a la credential de n8n.

---

## Paso 3 — Restringir ACL + imprimir paper backup

- [ ] Restringe el archivo a solo tu usuario actual:
  ```powershell
  icacls "$env:USERPROFILE\.config\myfinance-backup\age-identity-primary.txt" /inheritance:r /grant:r "${env:USERNAME}:F"
  ```
- [ ] **Imprime una copia en papel.** Notepad → File → Print, o copia el contenido a un Word/RTF e imprime. Usa impresora USB-conectada si tienes (network printers cachean jobs).
- [ ] **Sella en sobre.** Marca el sobre `#1 MyFinance backup primary identity — DO NOT DESTROY`.
- [ ] **Físicamente colócalo en location A** (tu elección documentada: caja fuerte, firebox, cajón con llave, banco safe deposit).
- [ ] **CRÍTICO:** si pierdes esto AND el archivo del PC simultáneamente, todos los snapshots quedan irrecuperables. Es el único path catastrófico aceptado bajo v3.

---

## Paso 4 — Cloudflare R2: token + bucket lifecycle

- [ ] Abre https://dash.cloudflare.com → R2.
- [ ] Confirma que el bucket `my-finance-view-backups` existe (ya lo creaste antes).
- [ ] En el dashboard del bucket → **Settings** → **API tokens** → **Create API token**:
  - **Token name:** `myfinance-backup-runner`
  - **Permissions:** `Object Read & Write`
  - **Bucket scope:** SOLO `my-finance-view-backups` (no "All buckets")
  - **TTL:** Forever (o tu cadencia de rotación anual)
- [ ] Captura los 3 valores que muestra ONE-TIME:
  - **Access Key ID** → `BACKUP_R2_ACCESS_KEY_ID`
  - **Secret Access Key** → `BACKUP_R2_SECRET_ACCESS_KEY`
  - **Account ID** (32-char hex, sale en la URL `dash.cloudflare.com/<account-id>/...`) → `BACKUP_R2_ACCOUNT_ID`
- [ ] → **Guarda los 3 en gestor de contraseñas**.

### 1 Lifecycle policy sobre `my-finance-view-backups` (v1, recorte del 2026-06-01)

En el bucket → **Settings** → **Object lifecycle rules** → **Add rule**:

- [ ] Rule única: nombre `myfinance-daily-30d` · prefix `daily/` · delete after `30 days`

**No configures rules para `weekly/`, `monthly/`, `pre-op/`, `quarantine/`** — acumulan sin expirar en v1 (YAGNI, proyección de volumen anual < 4 GB dentro del free tier de 10 GB). Spot-check mensual de `pre-op/` está en `tasks.md §10.5`. Si la acumulación se vuelve problemática más adelante, reinstalar las 4 reglas restantes es un follow-up de 5 minutos.

Saca screenshot del dashboard final con la regla única para tu runbook (la va a referenciar `README.md §2.5.4`).

---

## Paso 5 — (skipped — ya hecho con paso 4)

---

## Paso 6 — Resend API key (v1, recorte del 2026-06-01)

> **v1 cut:** este paso reemplaza por completo el flujo de Gmail App Password → SMTP. Resend separa la identidad del remitente (dominio del sistema) de la del destinatario (inbox del operador), evita los bloqueos por "actividad sospechosa" de Google cuando el VPS cambia de IP, y unifica el transporte de email con el resto del producto futuro.

- [ ] Abre https://resend.com/api-keys.
- [ ] Confirma que el dominio `datachefnow.com` aparece como **Verified** en https://resend.com/domains (ya verificado vía Route 53 / us-east-1; si no aparece, sigue las instrucciones de Resend para añadir los registros DNS antes de continuar).
- [ ] Click **Create API Key**:
  - **Name:** `MyFinanceView backups`
  - **Permission:** **Sending access** (solo enviar — no permitas "Full access")
  - **Domain:** `datachefnow.com`
- [ ] Click **Create** → captura el valor `re_...` que Resend muestra ONE-TIME.
- [ ] → **Guarda como `MYFINANCE_BACKUP_RESEND_API_KEY` en gestor de contraseñas**.
- [ ] Decide y captura las dos direcciones del parámetro de Resend:
  - **`MYFINANCE_BACKUP_ALERT_FROM`** = `alerts@datachefnow.com` (default; debe ser en el dominio verificado).
  - **`MYFINANCE_BACKUP_ALERT_TO`** = tu inbox operador (e.g. `aftorresl01@gmail.com`).
- [ ] → **Guarda ambas en gestor de contraseñas** (no son secretas, pero quedan junto al API key).
- [ ] **Smoke test rápido (opcional):** desde PowerShell, envía un email de prueba:
  ```powershell
  $resend_key = "<tu api key>"
  $body = @{from="alerts@datachefnow.com"; to="<tu inbox>"; subject="Resend smoke from operator"; text="hola"} | ConvertTo-Json
  curl.exe -X POST https://api.resend.com/emails -H "Authorization: Bearer $resend_key" -H "Content-Type: application/json" -d $body
  ```
  Deberías ver `{"id":"..."}` en stdout y el email llegando a tu inbox en < 30 s.

---

## Paso 7 — ntfy.sh topic

- [ ] Inventa un slug 32+ chars, mezcla letras (mayúsculas + minúsculas) + dígitos. Generador rápido en PowerShell:
  ```powershell
  -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 40 | ForEach-Object {[char]$_})
  ```
- [ ] Captura el slug. → **Guarda como `MYFINANCE_BACKUP_NTFY_TOPIC` en gestor de contraseñas**.
- [ ] En tu phone instala la app **ntfy** (Play Store / App Store / F-Droid).
- [ ] En la app → **+ Add subscription** → pega `https://ntfy.sh/<tu-slug>`.
- [ ] **Smoke test:** desde otro device o PowerShell:
  ```powershell
  curl.exe -d "test from operator" "https://ntfy.sh/<tu-slug>"
  ```
  Deberías ver la notificación en tu phone en < 5s.

---

## Paso 8 — Supabase: password del rol postgres + endpoint del Session Pooler

- [ ] Abre https://supabase.com/dashboard/project/akkoqdjmmozyqdfjkabg → **Project Settings** → **Database**.
- [ ] **Connection string** o **Database password**:
  - Si recuerdas el password actual: úsalo.
  - Si NO lo recuerdas: click **Reset database password** → captura el nuevo.
    - ⚠️ Resetear invalida el password en cualquier otro lugar que lo use (el backend Spring Boot, por ejemplo). Si reseteas, también actualiza el `.env.local` del backend.
- [ ] → **Guarda como `BACKUP_DB_PASSWORD` en gestor de contraseñas**. Mismo valor que `DB_PASSWORD` del backend.
- [ ] **Confirma el Session Pooler endpoint** (debería ser `aws-0-us-west-2.pooler.supabase.com:5432`):
  - **Connect** → **Session pooler** → confirma host + port.
  - Si el host es distinto (e.g. región cambió), edita `BACKUP_DB_HOST` en `.env.example` antes de §8.

---

## Paso 9 — Uptime Kuma Push Monitor (DROPPED v1, recorte del 2026-06-01)

> **No hagas este paso.** El Uptime Kuma in-cluster dead-man-switch está diferido en v1 junto con healthchecks.io off-VPS. El monitor externo del operador en `n8n.datachefnow.com` cubre el caso host-down.
>
> **Failure mode aceptado:** un Schedule-Trigger silencioso (VPS up, n8n cron no disparó) no produce alerta in-cluster en v1. Si se observa en producción, reinstalar Kuma es un follow-up bounded.
>
> No necesitas capturar `MYFINANCE_BACKUP_KUMA_PUSH_URL`, no necesitas crear un Push Monitor, no necesitas configurar canales en Kuma para este backup. Pasa directo al Paso 10.

---

## Paso 10 — Generar el runner shared secret

- [ ] Genera 32+ chars random:
  ```powershell
  -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 48 | ForEach-Object {[char]$_})
  ```
- [ ] Captura. → **Guarda como `MYFINANCE_BACKUP_RUNNER_SECRET` en gestor de contraseñas**.
- [ ] Este secreto se usa en DOS lugares:
  - Variable `MYFINANCE_BACKUP_RUNNER_SECRET` en `scripts/backup/.env.local` del VPS (sidecar lo lee como esperado).
  - n8n credential del mismo nombre (workflows lo mandan en header `X-Runner-Secret`).
  - **Ambos valores DEBEN ser idénticos** o la auth falla con 401.

---

## Paso 11 — Actualizar `recipients/primary.txt` en el repo

- [ ] En el repo (worktree o branch local):
  ```powershell
  # Sobrescribe el placeholder con tu recipient público real del paso 2
  Set-Content scripts/backup/recipients/primary.txt -Value "<tu-line-age1...>" -NoNewline
  ```
- [ ] Verifica:
  ```powershell
  Get-Content scripts/backup/recipients/primary.txt
  ```
  Debe ser una sola línea que empiece con `age1`.
- [ ] Commit + push:
  ```powershell
  git add scripts/backup/recipients/primary.txt
  git commit -m "feat(backup): inject real primary age recipient for production"
  git push
  ```

---

## Resumen de qué tendrás al final de §1

Estado físico:
- [ ] `age-identity-primary.txt` en `%USERPROFILE%\.config\myfinance-backup\` con ACL restrictivo.
- [ ] Sobre #1 con copia impresa en location A.

Estado en gestor de contraseñas (todos los valores de la tabla de captura al inicio del documento).

Estado en cuentas externas (v1):
- [ ] Cloudflare R2 token activo + 1 lifecycle policy `myfinance-daily-30d`.
- [ ] Resend API key generado, dominio `datachefnow.com` verificado, sender + recipient capturados.
- [ ] ntfy topic suscrito en phone + smoke test verde.
- [ ] Supabase Session Pooler endpoint confirmado.

**No requerido en v1 (recortes del 2026-06-01):** Gmail App Password, Uptime Kuma Push Monitor.

Estado en repo:
- [ ] `scripts/backup/recipients/primary.txt` contiene tu recipient real (no placeholder).
- [ ] Commit + push del recipient real.

---

## Próximo paso (§8) — preview rápido para mañana

Cuando termines §1, en el VPS:

```bash
# Clona o pull el repo en el VPS (a la ruta que ya usas para n8n).
cd /path/to/MyFinanceView
git checkout feat/supabase-backup-policy-replant
git pull

# Crea el .env.local con todos los secretos capturados arriba.
cp .env.example scripts/backup/.env.local
nano scripts/backup/.env.local
# Llena cada placeholder con el valor del gestor de contraseñas.
chmod 600 scripts/backup/.env.local
chown $USER:$USER scripts/backup/.env.local

# Verifica que el network n8n_net existe.
docker network ls | grep n8n_net

# Build + up del sidecar.
docker compose -f n8n/docker-compose.yml -f scripts/backup/docker-compose.yml up -d --build myfinance-backup-runner

# Smoke check.
docker exec myfinance-backup-runner curl -fsS http://localhost:8080/healthz
# Expected: {"status":"ok","version":"1.0.0"}

docker exec myfinance-backup-runner rclone listremotes
# Expected: r2:

docker exec myfinance-backup-runner rclone lsd r2:
# Expected: listing del bucket (puede estar vacío)
```

Si todos esos comandos vuelven verdes, sigues con §8.4 (import workflows en n8n UI) → §8.4a (re-link errorWorkflow dropdown) → §9 (smoke tests) → §10 (activation).

---

## Si encuentras algo raro mañana

- Revisa `openspec/changes/supabase-backup-policy/tasks.md` §1, §8, §9, §10 (versión completa con sub-pasos).
- Revisa `scripts/backup/README.md` §2.5.3 y §2.5.4 (runbook).
- Si el sidecar no arranca, revisa logs: `docker logs myfinance-backup-runner --tail 50`.
- Si `rclone listremotes` no muestra `r2:`, revisa que las 3 vars `BACKUP_R2_*` estén bien copiadas en `.env.local` (el entrypoint shim fallará rápido con un mensaje claro).

Suerte mañana. 🚀
