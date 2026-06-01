#!/usr/bin/env sh
# docker-entrypoint.sh — materialize the rclone [r2] remote from BACKUP_R2_*
# env vars at container startup, then exec the original command.
#
# Rationale (B2 fix from replant adversarial review, 2026-06-01):
# The Dockerfile installs rclone but the image had no rclone config and the
# compose file did not bind-mount /etc/rclone.conf from the host. Without
# a defined `r2:` remote, every `rclone copy`/`rclone cat`/`rclone lsf` call
# from server.js and from the bash workers fails with
# "didn't find section in config file". This shim closes that gap WITHOUT
# requiring a host-side rclone.conf bind-mount (more portable across
# operators / future projects with their own buckets).
#
# rclone reads RCLONE_CONFIG_<NAME>_<KEY> env vars as on-the-fly remote
# definitions; see https://rclone.org/docs/#environment-variables.

set -eu

if [ -z "${BACKUP_R2_ACCOUNT_ID:-}" ]; then
    echo "ERROR: BACKUP_R2_ACCOUNT_ID is required to materialize the rclone r2 remote" >&2
    echo "       Set it in scripts/backup/.env.local on the VPS." >&2
    exit 1
fi
if [ -z "${BACKUP_R2_ACCESS_KEY_ID:-}" ]; then
    echo "ERROR: BACKUP_R2_ACCESS_KEY_ID is required" >&2
    exit 1
fi
if [ -z "${BACKUP_R2_SECRET_ACCESS_KEY:-}" ]; then
    echo "ERROR: BACKUP_R2_SECRET_ACCESS_KEY is required" >&2
    exit 1
fi

export RCLONE_CONFIG_R2_TYPE=s3
export RCLONE_CONFIG_R2_PROVIDER=Cloudflare
export RCLONE_CONFIG_R2_ACCESS_KEY_ID="${BACKUP_R2_ACCESS_KEY_ID}"
export RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="${BACKUP_R2_SECRET_ACCESS_KEY}"
export RCLONE_CONFIG_R2_ENDPOINT="https://${BACKUP_R2_ACCOUNT_ID}.r2.cloudflarestorage.com"
export RCLONE_CONFIG_R2_ACL=private

# Smoke-check: confirm rclone now resolves the r2 remote. This is a local
# config check (no network call); catches misspelled env vars at container
# start, BEFORE n8n's first call would have failed silently.
if ! rclone listremotes 2>/dev/null | grep -q '^r2:$'; then
    echo "ERROR: rclone could not register the r2 remote — check BACKUP_R2_* env vars" >&2
    rclone listremotes >&2 || true
    exit 1
fi

exec "$@"
