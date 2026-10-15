"""Private, bounded diagnostic inbox. Run behind the included HTTPS reverse proxy."""
from contextlib import contextmanager
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BYTES = 262144
MAX_REPORTS = 500
RETENTION_SECONDS = 7 * 86400
ID_PATTERN = re.compile(r"[a-f0-9]{32}\Z")
KEY_PATTERN = re.compile(r"[A-Za-z0-9_-]{32,128}\Z")
CONTRACT = json.loads(Path(__file__).with_name("contract.json").read_text())


def integer(value, minimum, maximum):
    return type(value) is int and minimum <= value <= maximum


def exact_fields(value, fields):
    if type(value) is not dict or set(value) != set(fields):
        raise ValueError("Invalid fields")


def validate_report(value):
    exact_fields(value, ("schema", "createdMs", "device", "records"))
    if type(value["schema"]) is not int or value["schema"] != 1:
        raise ValueError("Unsupported report schema")
    if not integer(value["createdMs"], 0, 9_000_000_000_000_000):
        raise ValueError("Invalid report timestamp")
    device = value["device"]
    exact_fields(device, ("appVersion", "appCode", "manufacturer", "model", "androidVersion", "api"))
    for field in ("appVersion", "manufacturer", "model", "androidVersion"):
        if type(device[field]) is not str or not re.fullmatch(r"[A-Za-z0-9 ._()+-]{1,80}", device[field]):
            raise ValueError("Invalid device field")
    if not integer(device["appCode"], 1, 2_147_483_647) or not integer(device["api"], 26, 200):
        raise ValueError("Invalid device version")
    if type(value["records"]) is not list or len(value["records"]) > 100:
        raise ValueError("Too many events")
    for record in value["records"]:
        exact_fields(record, ("timeMs", "event", "outcome", "metrics", "errorTypes", "frames"))
        if not integer(record["timeMs"], 0, 9_000_000_000_000_000):
            raise ValueError("Invalid event timestamp")
        if record["event"] not in CONTRACT["events"] or record["outcome"] not in CONTRACT["outcomes"]:
            raise ValueError("Unknown event")
        if type(record["metrics"]) is not dict or any(
            key not in CONTRACT["metrics"] or not integer(val, -1, 1_000_000_000)
            for key, val in record["metrics"].items()
        ):
            raise ValueError("Invalid numeric metrics")
        if type(record["errorTypes"]) is not list or len(record["errorTypes"]) > 4 or any(
            type(item) is not str or not re.fullmatch(r"[A-Za-z0-9_]{0,100}", item) for item in record["errorTypes"]
        ):
            raise ValueError("Invalid error types")
        if type(record["frames"]) is not list or len(record["frames"]) > 4 or any(
            type(item) is not str or len(item) > 160 or not re.fullmatch(
                r"com\.(?:axiel7\.moelist|google\.mlkit)\.[A-Za-z0-9_.$:<>-]+", item
            ) for item in record["frames"]
        ):
            raise ValueError("Invalid code locations")
    return value


def decode_report(body):
    def unique_object(pairs):
        result = {}
        for key, val in pairs:
            if key in result:
                raise ValueError("Duplicate JSON field")
            result[key] = val
        return result
    return validate_report(json.loads(body, object_pairs_hook=unique_object))


class Inbox:
    def __init__(self, directory, upload_key, read_key):
        if not KEY_PATTERN.fullmatch(upload_key) or not KEY_PATTERN.fullmatch(read_key) or upload_key == read_key:
            raise ValueError("Distinct strong keys are required")
        self.upload_key = upload_key
        self.read_key = read_key
        self.lock = threading.Lock()
        self.window_start = time.monotonic()
        self.uploads_in_window = 0
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.database = self.directory / "reports.sqlite3"
        with self.lock, self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS reports (id TEXT PRIMARY KEY, received INTEGER NOT NULL, body TEXT NOT NULL)")
        self.database.chmod(0o600)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.database, timeout=10)
        try:
            db.execute("PRAGMA secure_delete = ON")
            with db:
                yield db
        finally:
            db.close()

    def expire(self, db):
        db.execute("DELETE FROM reports WHERE received <= ?", (int(time.time()) - RETENTION_SECONDS,))

    def prune(self):
        with self.lock, self.connect() as db:
            self.expire(db)

    def put(self, report):
        with self.lock, self.connect() as db:
            now = time.monotonic()
            if now - self.window_start >= 60:
                self.window_start, self.uploads_in_window = now, 0
            if self.uploads_in_window >= 10:
                return None
            self.uploads_in_window += 1
            self.expire(db)
            report_id = secrets.token_hex(16)
            db.execute("INSERT INTO reports VALUES (?, ?, ?)", (report_id, int(time.time()), json.dumps(report, separators=(",", ":"))))
            db.execute("DELETE FROM reports WHERE id IN (SELECT id FROM reports ORDER BY received DESC, rowid DESC LIMIT -1 OFFSET ?)", (MAX_REPORTS,))
            return report_id

    def list(self):
        with self.lock, self.connect() as db:
            self.expire(db)
            return [{"id": row[0], "received": row[1]} for row in db.execute("SELECT id, received FROM reports ORDER BY received DESC, rowid DESC LIMIT 50")]

    def get(self, report_id):
        if not ID_PATTERN.fullmatch(report_id):
            return None
        with self.lock, self.connect() as db:
            self.expire(db)
            row = db.execute("SELECT body FROM reports WHERE id = ?", (report_id,)).fetchone()
            return json.loads(row[0]) if row else None

    def delete(self, report_id):
        if not ID_PATTERN.fullmatch(report_id):
            return False
        with self.lock, self.connect() as db:
            return db.execute("DELETE FROM reports WHERE id = ?", (report_id,)).rowcount > 0


class Receiver(ThreadingHTTPServer):
    daemon_threads = True
    # Bound socket concurrency as well as stored report count.
    def __init__(self, address, inbox):
        self.inbox = inbox
        self.slots = threading.BoundedSemaphore(16)
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        # The default implementation prints client addresses and exception messages.
        import sys
        print("Diagnostic request failed", file=sys.stderr)


class Handler(BaseHTTPRequestHandler):
    server_version = "MoeListInbox"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, *_args):
        pass  # Do not persist IPs, paths, headers, keys or request bodies in access logs.

    def reply(self, code, value):
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def authorized(self, key):
        headers = self.headers.get_all("Authorization", [])
        expected = "Bearer " + key
        return len(headers) == 1 and hmac.compare_digest(headers[0].encode(), expected.encode())

    def do_POST(self):
        if self.path != "/v1/reports":
            return self.reply(404, {"error": "not_found"})
        if not self.authorized(self.server.inbox.upload_key):
            return self.reply(401, {"error": "unauthorized"})
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Encoding") or len(lengths) != 1:
            return self.reply(400, {"error": "invalid_body"})
        if self.headers.get_content_type() != "application/json":
            return self.reply(415, {"error": "json_required"})
        try:
            length = int(lengths[0])
            if not 1 <= length <= MAX_BYTES:
                return self.reply(413, {"error": "size_limit"})
            body = self.rfile.read(length)
            if len(body) != length:
                return self.reply(400, {"error": "incomplete_body"})
            report = decode_report(body)
        except (ValueError, TypeError, KeyError, RecursionError, UnicodeError):
            return self.reply(400, {"error": "invalid_report"})
        report_id = self.server.inbox.put(report)
        if report_id is None:
            return self.reply(429, {"error": "rate_limit"})
        self.reply(201, {"id": report_id})

    def do_GET(self):
        if self.path == "/healthz":
            return self.reply(200, {"status": "ok"})
        if not self.authorized(self.server.inbox.read_key):
            return self.reply(401, {"error": "unauthorized"})
        if self.path == "/v1/reports":
            return self.reply(200, {"reports": self.server.inbox.list()})
        if self.path.startswith("/v1/reports/"):
            report = self.server.inbox.get(self.path.removeprefix("/v1/reports/"))
            if report is not None:
                return self.reply(200, report)
        self.reply(404, {"error": "not_found"})

    def do_DELETE(self):
        if not self.authorized(self.server.inbox.read_key):
            return self.reply(401, {"error": "unauthorized"})
        if self.path.startswith("/v1/reports/") and self.server.inbox.delete(self.path.removeprefix("/v1/reports/")):
            return self.reply(200, {"deleted": True})
        self.reply(404, {"error": "not_found"})


def serve():
    os.umask(0o077)
    inbox = Inbox(os.environ.get("REPORT_DIRECTORY", "/data"),
        Path(os.environ.get("UPLOAD_KEY_FILE", "/run/secrets/upload_key")).read_text().strip(),
        Path(os.environ.get("READ_KEY_FILE", "/run/secrets/read_key")).read_text().strip())
    server = Receiver((os.environ.get("LISTEN_HOST", "127.0.0.1"), 8765), inbox)
    def cleanup():
        while True:
            inbox.prune()
            time.sleep(60)
    threading.Thread(target=cleanup, daemon=True).start()
    server.serve_forever()


if __name__ == "__main__":
    serve()
