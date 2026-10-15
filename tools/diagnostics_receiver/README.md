# Private diagnostics receiver

This is a personal inbox for MoeList's structured diagnostic reports. It has no analytics SDK, chat ingestion or automatic uploads. The Android user previews each snapshot and explicitly shares it or sends it to their own HTTPS domain. An upload produces a random report ID.

## Data and access

The versioned schema admits only fixed event/outcome/metric names, numeric measurements, app/device versions, exception **types** and up to four app/ML Kit code locations per event. There are no fields for credentials, account names, titles, prompts, model responses, notes, raw exception messages, URLs or HTTP headers. Unknown fields are rejected. Upload and read/delete keys are separate; the phone receives only the upload key. Transport requires HTTPS. The phone encrypts its saved upload key using Android Keystore and keeps logs/config outside Android backups.

The app keeps at most 100 events for seven days. The receiver keeps at most 500 reports, each at most 256 KiB, for seven days, with expiry every minute and on reads/writes. Uploaded reports are not deleted by clearing the phone. VPS operators can delete a report immediately using `manage.py delete ID`. VPS snapshots/backups are outside this retention policy. Server report files are private to the OS account; this is not application-level encryption at rest. Anyone with root or equivalent VPS access can read them. Do not share the server between untrusted tenants using the same keys.

## Deploy after SSH and DNS are ready

1. Choose a dedicated subdomain, e.g. `logs.example.com`, with a Cloudflare DNS-only A record to the VPS public IPv4. Add AAAA only if that server's IPv6 is configured. Keep SSH on its original IP/hostname.
2. Inspect existing listeners before using the included proxy: `sudo ss -ltnp`. If ports 80/443 are already used, add the receiver to the existing proxy configuration instead of stopping services or running a competing proxy.
3. Install Docker Engine and the Compose plugin using [Docker's Ubuntu instructions](https://docs.docker.com/engine/install/ubuntu/). If Docker is already installed, reuse it. Docker access is effectively root-level access; use the VPS account you intend to administer the service.
4. Copy this folder to a directory owned by the SSH account. Run `python3 setup.py`, then enter your real subdomain. This generates two random local key files and a non-secret `.env` configuration. It does not print the keys, overwrite existing keys, or send them anywhere.
5. In that directory run `docker compose config --quiet`, then `docker compose up -d --build`. If using sudo for Docker, retain the `.env` generated as the directory owner. The receiver has no published host port; Caddy is its HTTPS entry point. Permit inbound TCP 80/443 in the provider firewall and existing host firewall if required. Keep the existing SSH port allowed. Do not enable or reset a firewall blindly.
6. Confirm `https://YOUR-DOMAIN/healthz` returns `{"status":"ok"}` with a valid certificate. Caddy obtains certificates using [automatic HTTPS](https://caddyserver.com/docs/automatic-https). Keep the Cloudflare record DNS-only during initial setup. If enabling the proxy later, use Full (strict), not Flexible TLS, and exempt this authenticated API from interactive browser challenges.
7. Transfer `secrets/upload_key` privately to the phone. Enter it and `https://YOUR-DOMAIN` in More → Settings → Diagnostics → Private server. Do not paste either key into a public issue, GitHub or this chat. The app stores the upload key encrypted. The read key remains on the VPS.
8. Reproduce an app problem. Tap Preview report, inspect it, then Send preview to server. Note the receipt ID. Check the receiver with `python3 manage.py list` and `python3 manage.py get REPORT_ID`. The returned content must match the preview. Verify the upload key gets HTTP 401 when attempting to read reports.

For future diagnosis, provide the report ID in the chat. An authorized session can retrieve it over SSH by running `manage.py get`. This service does not wake an assistant or notify a chat automatically. A persistent monitor is a separate feature.

## Rotation and removal

To rotate a key, generate a replacement with Python's `secrets.token_urlsafe(48)` into the corresponding private file, restart the inbox, and replace the phone's saved upload key if that key changed. Never bake keys into an APK. Remove the phone destination with Remove server. Stop the service with `docker compose down`; this preserves report data. Use `manage.py delete` for reports you intend to remove.

## Verification

`python3 -m unittest discover -s tools/diagnostics_receiver -p 'test_*.py' -v` from the repository root exercises authenticated upload/read/delete, role separation, unknown/private fields, size/format limits, path traversal, retention, storage/rate caps and the Kotlin/server schema contract. The handler's local tests use HTTP on loopback; production uses the HTTPS proxy. A real VPS certificate and real-phone upload still need deployment verification.

This lightweight service is intended for a small personal installation. It uses a bounded thread count, connection timeouts and an authenticated upload rate of 10/minute. Configure upstream protections before opening it to many users. No external telemetry provider is involved.
