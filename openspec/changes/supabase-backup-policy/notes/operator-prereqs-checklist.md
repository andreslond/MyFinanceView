# §1 Operator prereqs — step-by-step checklist

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
| `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD` | _________________ | Paso 6 |
| `MYFINANCE_BACKUP_KUMA_PUSH_URL` | _________________ | Paso 9 |
| `MYFINANCE_BACKUP_RUNNER_SECRET` | _________________ | Paso 10 |

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

### 5 Lifecycle policies sobre `my-finance-view-backups`

En el bucket → **Settings** → **Object lifecycle rules** → **Add rule** para cada uno:

- [ ] Rule 1: prefix `daily/` · delete after `30 days`
- [ ] Rule 2: prefix `weekly/` · delete after `90 days`
- [ ] Rule 3: prefix `monthly/` · delete after `365 days`
- [ ] Rule 4: prefix `pre-op/` · delete after `90 days`
- [ ] Rule 5: prefix `quarantine/` · delete after `365 days`

Saca screenshot del dashboard final con los 5 rules para tu runbook (los va a referenciar `README.md §2.5.4`).

---

## Paso 5 — (skipped — ya hecho con paso 4)

---

## Paso 6 — Gmail App Password

- [ ] Asegúrate de tener 2FA activado en tu cuenta `aftorresl01@gmail.com` (requisito de App Passwords).
- [ ] Abre https://myaccount.google.com/apppasswords.
- [ ] **App name:** `MyFinanceView backups`.
- [ ] Click **Create** → captura los 16 caracteres (formato `xxxx xxxx xxxx xxxx`, sin espacios cuando lo uses).
- [ ] → **Guarda como `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD` en gestor de contraseñas**.

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

## Paso 9 — Uptime Kuma Push Monitor

- [ ] Abre tu Uptime Kuma UI (la URL que ya conoces).
- [ ] **+ Add New Monitor**:
  - **Monitor Type:** `Push`
  - **Friendly Name:** `MyFinance Daily Backup`
  - **Heartbeat Interval:** `86400` (24h en segundos)
  - **Heartbeat Retry Interval:** dejar default
  - **Retries:** `0`
  - **Heartbeat Grace Period:** `21600` (6h en segundos = grace 30h total)
- [ ] **Save** → captura la **Push URL** que Kuma genera (formato `https://<tu-kuma>/api/push/<token>?status=up&msg=OK&ping=`).
- [ ] → **Guarda como `MYFINANCE_BACKUP_KUMA_PUSH_URL` en gestor de contraseñas**.

### Notification channel (CRÍTICO — un monitor sin canal es silencioso)

- [ ] En el monitor recién creado → tab **Notifications** → **Setup Notification**.
- [ ] Crea AL MENOS UN channel. Opciones:
  - **Telegram bot** (recomendado si ya tienes uno): bot token + chat ID.
  - **Gmail SMTP**: host `smtp.gmail.com` port `587` STARTTLS user `aftorresl01@gmail.com` pass `<MYFINANCE_BACKUP_GMAIL_APP_PASSWORD>` to `aftorresl01@gmail.com`.
  - **ntfy.sh**: server `https://ntfy.sh` topic `<usa otro topic distinto del paso 7, dedicado a Kuma>`.
  - **Discord**: webhook URL.
  - **Generic webhook**.
- [ ] **Click "Test"** sobre el channel → confirma que la notificación llega (mensaje de Telegram / email / Discord / ntfy).
- [ ] Documenta qué channel usaste — va a `README.md §2.5.5` como nota.
- [ ] Attach el channel al monitor `MyFinance Daily Backup` (checkbox en la lista de canales).

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

Estado en gestor de contraseñas (los 9 valores de la tabla del inicio).

Estado en cuentas externas:
- [ ] Cloudflare R2 token activo + 5 lifecycle policies.
- [ ] Gmail App Password generado.
- [ ] ntfy topic suscrito en phone + smoke test verde.
- [ ] Supabase Session Pooler endpoint confirmado.
- [ ] Kuma Push Monitor con notification channel testeado verde.

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
