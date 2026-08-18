# Private Deployment

This directory provides a single-node HTTPS deployment. Caddy obtains and renews public TLS certificates, while the sync service stays on an internal Docker network with no host port.

## Prerequisites

- A Linux host with current Docker Engine and Docker Compose
- A public DNS `A` or `AAAA` record for the sync hostname
- Inbound TCP 80/443 and UDP 443 allowed through the host firewall
- Outbound access for ACME certificate issuance

Do not use this package for multiple sync replicas. Snapshot locking is in-process, so one `sync` container owns the filesystem volume.

## Configure and start

```bash
cd cloud-server/deploy
cp .env.example .env
mkdir -p backups
```

Edit `.env` with the real hostname and monitored operations email. `CADDY_MAX_BODY_SIZE` must be larger than `MAX_BODY_BYTES` to allow JSON envelope overhead. Keep `SYNC_UID` and `SYNC_GID` at 1000 for the official Node image; if the image user changes, update both values before running backup or restore tools.

```bash
docker compose config
docker compose up -d --build
docker compose ps
curl --fail https://sync.example.com/health
docker compose exec -T sync \
  node -e "fetch('http://127.0.0.1:8787/ready').then(r => process.exit(r.ok ? 0 : 1))"
```

From `cloud-server`, run `npm run validate:deployment` in release CI. It first confirms that Docker Engine and the Compose plugin respond within 15 seconds, then validates the Compose model, builds the sync image, and parses the Caddyfile with the pinned proxy image. A preflight failure is an environment failure, not evidence that the deployment files are invalid.

The `sync` container is read-only except for `/data`, runs without Linux capabilities, and is reachable only by Caddy. Caddy's default reverse-proxy handling rejects client control of the trusted forwarding address, allowing `TRUST_PROXY=true` without accepting a spoofed client address. Its JSON access log deletes Authorization and Cookie headers. The writable-storage `/ready` probe is internal-only; Caddy returns 404 for public requests to that path. Confirm these properties again after any proxy customization.

Set the Android production endpoint to the resulting origin, for example `https://sync.example.com`. Do not append `/v1/sync`; the app adds the API path.

## Back up

For a point-in-time backup, briefly stop writes before creating the archive:

```bash
docker compose stop sync
docker compose --profile tools run --rm backup
docker compose start sync
```

The command writes a timestamped archive and a `.sha256` file under `deploy/backups/`. Copy both files to encrypted off-host storage with access controls and retention appropriate to the customer agreement. The archive contains already-encrypted snapshots, authorization hashes, revisions, and timestamps, but the archive itself is not an additional encryption layer.

Test backups regularly. An untested archive is not a recovery plan.

## Restore without overwriting

The restore tool refuses a nonempty `/data` volume. Restore into a new Compose project so the current volume remains available for rollback:

```bash
docker compose -p gps-updater-sync-restore --env-file .env \
  --profile tools run --rm \
  -e RESTORE_ARCHIVE=gps-updater-sync-20260101T000000Z.tar.gz \
  restore-empty-volume

docker compose -p gps-updater-sync-restore --env-file .env up -d --build sync
docker compose -p gps-updater-sync-restore --env-file .env exec -T sync \
  node -e "fetch('http://127.0.0.1:8787/ready').then(r => process.exit(r.ok ? 0 : 1))"
```

After validation, stop the old stack without deleting its volumes, then start Caddy in the restored project during a controlled maintenance window. Keep the old volume until application-level restore and deletion behavior has been verified.

Never use `docker compose down -v` as part of a normal upgrade or recovery procedure.

## Operations

- Monitor both `/health` and `/ready`; the latter verifies that persistent storage is writable.
- Alert on repeated HTTP 5xx, 429 spikes, container restarts, certificate failures, and low disk space.
- Retain Caddy logs only as long as operationally necessary. Do not add request-body or authorization logging.
- Patch the host, Docker, Caddy image, and Node base image on a defined cadence.
- Run a backup/restore drill before customer launch and after changing storage or proxy configuration.
- Lost user sync keys cannot be recovered by the server. Enterprise support must not claim otherwise.
