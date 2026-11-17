"""Small LAN-free integration test: launch real HTTP endpoints locally."""
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.request import Request, urlopen
from server import Handler


class HttpIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def request(self, endpoint, payload=None):
        url = f"http://127.0.0.1:{self.port}{endpoint}"
        if payload is None:
            with urlopen(url, timeout=2) as reply:
                return json.load(reply)
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = Request(url, data=data, method="POST",
                      headers={"Content-Type": "application/json"})
        with urlopen(req, timeout=2) as reply:
            return json.load(reply)

    def test_health_plan_and_report(self):
        self.assertEqual(self.request("/health")["mode"], "demo")
        h1 = {"round": 1, "mission": "order", "category": "animals",
              "difficulty": 2, "correct": True, "hints": 0}
        p = self.request("/plan", {"round_index": 1, "history": [h1]})
        self.assertEqual((p["mission"], p["difficulty"], p["mode"]),
                         ("position", 3, "demo"))
        h = [h1, {"round": 2, "mission": "position", "category": "fruits",
                  "difficulty": 3, "correct": True, "hints": 1},
             {"round": 3, "mission": "missing", "category": "animals",
              "difficulty": 3, "correct": False, "hints": 0},
             {"round": 4, "mission": "story", "category": "stories",
              "difficulty": 2, "correct": True, "hints": 0},
             {"round": 5, "mission": "position", "category": "animals",
              "difficulty": 3, "correct": True, "hints": 0}]
        report = self.request("/report", {"history": h})
        self.assertEqual(report["mode"], "demo")
        self.assertIn("힌트 1회", report["teacher_note"])
        self.assertIn("위치 기억", report["next_activity"])
        self.assertEqual(report["next_plan"]["mission"], "position")


if __name__ == "__main__":
    unittest.main()
