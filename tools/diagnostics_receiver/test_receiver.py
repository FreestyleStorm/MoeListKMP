import copy
import http.client
import json
from pathlib import Path
import re
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import receiver


def sample():
    return {"schema": 1, "createdMs": 1000000,
        "device": {"appVersion": "4.1.8-personal.1", "appCode": 413,
            "manufacturer": "samsung", "model": "test-device", "androidVersion": "16", "api": 36},
        "records": [{"timeMs": 999999, "event": "NANO_GENERATE", "outcome": "FAILED",
            "metrics": {"DURATION_MS": 1234}, "errorTypes": ["IllegalStateException"],
            "frames": ["com.axiel7.moelist.screens.assistant.AndroidNanoClient.generate:42"]}]}


class ReceiverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.inbox = receiver.Inbox(self.temp.name, "u" * 64, "r" * 64)
        self.server = receiver.Receiver(("127.0.0.1", 0), self.inbox)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, method, path, key=None, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        fields = {"Content-Type": "application/json"}
        if key:
            fields["Authorization"] = "Bearer " + key
        fields.update(headers or {})
        data = json.dumps(body).encode() if isinstance(body, dict) else body
        try:
            connection.request(method, path, data, fields)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_upload_read_and_delete_have_separate_access(self):
        self.assertEqual(401, self.request("POST", "/v1/reports", body=sample())[0])
        self.assertEqual(401, self.request("POST", "/v1/reports", "r" * 64, sample())[0])
        code, receipt = self.request("POST", "/v1/reports", "u" * 64, sample())
        self.assertEqual(201, code)
        self.assertRegex(receipt["id"], r"^[a-f0-9]{32}$")
        path = "/v1/reports/" + receipt["id"]
        self.assertEqual(401, self.request("GET", path, "u" * 64)[0])
        self.assertEqual((200, sample()), self.request("GET", path, "r" * 64))
        self.assertEqual(401, self.request("DELETE", path, "u" * 64)[0])
        self.assertEqual(200, self.request("DELETE", path, "r" * 64)[0])
        self.assertEqual(404, self.request("GET", path, "r" * 64)[0])

    def test_arbitrary_text_credentials_and_unknown_fields_are_rejected(self):
        mutations = [
            lambda x: x.update(prompt="private chat"),
            lambda x: x["device"].update(token="private token"),
            lambda x: x["records"][0].update(message="secret exception message"),
            lambda x: x["records"][0]["metrics"].update(DURATION_MS="Bearer secret"),
            lambda x: x["records"][0].update(errorTypes=["secret@example.com"]),
            lambda x: x["records"][0].update(frames=["/home/user/private-key"]),
            lambda x: x["records"][0].update(outcome="invented"),
            lambda x: x.update(schema=True),
        ]
        for mutate in mutations:
            value = sample()
            mutate(value)
            self.assertEqual(400, self.request("POST", "/v1/reports", "u" * 64, value)[0])
        self.assertEqual([], self.inbox.list())

    def test_body_size_format_and_duplicate_fields(self):
        self.assertEqual(413, self.request("POST", "/v1/reports", "u" * 64, b"x" * (receiver.MAX_BYTES + 1))[0])
        self.assertEqual(400, self.request("POST", "/v1/reports", "u" * 64, b'{"schema":1,"schema":1}')[0])
        self.assertEqual(400, self.request("POST", "/v1/reports", "u" * 64, b"not json")[0])
        self.assertEqual(415, self.request("POST", "/v1/reports", "u" * 64, b"x", {"Content-Type": "text/plain"})[0])
        self.assertEqual(400, self.request("POST", "/v1/reports", "u" * 64, b"x", {"Content-Encoding": "gzip"})[0])

    def test_storage_expiry_count_limit_and_rate_limit(self):
        with patch.object(receiver, "MAX_REPORTS", 3):
            ids = [self.inbox.put(sample()) for _ in range(5)]
            self.assertEqual(3, len(self.inbox.list()))
            self.assertIsNone(self.inbox.get(ids[0]))
        with self.inbox.connect() as db:
            db.execute("UPDATE reports SET received = ?", (int(time.time()) - receiver.RETENTION_SECONDS - 1,))
        self.inbox.prune()
        self.assertEqual([], self.inbox.list())
        for _ in range(5):
            self.inbox.put(sample())
        self.assertEqual(429, self.request("POST", "/v1/reports", "u" * 64, sample())[0])

    def test_path_traversal_and_public_listing_are_rejected(self):
        self.assertEqual(401, self.request("GET", "/v1/reports")[0])
        self.assertEqual(404, self.request("GET", "/v1/reports/../../secrets/read_key", "r" * 64)[0])
        self.assertEqual(200, self.request("GET", "/healthz")[0])

    def test_server_contract_matches_android_closed_vocabulary(self):
        source = (Path(__file__).resolve().parents[2] / "app/shared/src/commonMain/kotlin/com/axiel7/moelist/diagnostics/Diagnostics.kt").read_text()
        for name, field in (("DiagnosticEvent", "events"), ("DiagnosticOutcome", "outcomes"), ("DiagnosticMetric", "metrics")):
            match = re.search(r"enum class " + name + r"\s*\{([^}]+)\}", source)
            self.assertEqual([item.strip() for item in match[1].split(",") if item.strip()], receiver.CONTRACT[field])


if __name__ == "__main__":
    unittest.main()
