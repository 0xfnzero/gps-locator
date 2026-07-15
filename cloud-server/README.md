# GPS Updater Sync Server

Stores end-to-end encrypted mobile backups. The server never receives the sync key or plaintext location data.

## Run locally

```bash
npm test
npm start
npm run validate:deployment
```

`validate:deployment` requires a running Docker Engine and the Docker Compose plugin. It performs bounded preflight checks with explicit diagnostics for a missing CLI, unreachable/stopped Engine, timeout, or unavailable Compose plugin. After preflight it validates the full Compose model, builds the sync image, and parses the Caddyfile with the pinned Caddy image.

Environment variables:

- `HOST`: bind address, default `127.0.0.1`
- `PORT`: HTTP port, default `8787`
- `DATA_DIR`: persistent snapshot directory
- `MAX_BODY_BYTES`: maximum encrypted snapshot size, default 2 MiB
- `RATE_LIMIT_PER_MINUTE`: per-IP request limit, default 60
- `TRUST_PROXY`: set to `true` only when a trusted reverse proxy overwrites `X-Forwarded-For`

Invalid or unsafe values fail at startup instead of silently disabling limits. `MAX_BODY_BYTES` accepts 1 KiB through 64 MiB, and `RATE_LIMIT_PER_MINUTE` must be positive.

## Docker

```bash
docker build -t gps-updater-sync .
docker run -p 8787:8787 -v gps-sync-data:/data gps-updater-sync
```

Production deployments must place the service behind an HTTPS reverse proxy. The Android app rejects non-HTTPS endpoints. Back up the `/data` volume and restrict direct access to port 8787. Set `TRUST_PROXY=true` only when direct access is blocked and the proxy replaces, rather than appends untrusted values to, `X-Forwarded-For`. Run one server process/replica for each filesystem `DATA_DIR`; the per-snapshot lock is in-process. A multi-replica deployment needs shared conditional storage or distributed locking rather than a shared filesystem volume alone.

For an Enterprise single-node deployment with automatic HTTPS, an internal backend network, header-redacted proxy logs, persistent volumes, health checks, and guarded backup/restore tools, use [`deploy/README.md`](deploy/README.md).

`GET /health` is a liveness probe. `GET /ready` creates and removes a protected temporary file in `DATA_DIR`, returning HTTP 503 if persistent storage is not writable.

## API

`PUT`, `GET`, and `DELETE /v1/sync/{sha256-id}` use `Authorization: Bearer {sha256-auth}`. The first `PUT` binds the identifier to a second hash of the authorization value; usable Bearer credentials are not stored on disk. Bodies are AES-256-GCM encrypted envelopes generated on the device.

`GET` and successful `PUT` responses include a strong `ETag`. New clients create with `If-None-Match: *` and update with `If-Match`; the server returns HTTP 412 with `snapshot_conflict` when another device won the race. Mutations for the same sync ID are serialized in-process and use unique atomic-rename temporary files, so two concurrent requests cannot both pass the same revision check or collide on a temporary path. Existing records without a stored revision receive an ETag derived from their encrypted payload, and older clients that do not send conditional headers remain compatible.

Android snapshot schema v2 carries encrypted deletion markers. They contain entity type, opaque local ID, and deletion time inside the AES-GCM envelope, allowing another device to remove stale favorites, routes, or history instead of recreating them. The server cannot read those markers.
