"""Local-only fictional carrier. SQLite preserves effects across process restarts."""
import json
import os
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

CARRIER = os.environ.get("CARRIER", "alpha")
DB = os.environ.get("SIM_DB", "/data/shipments.db")
os.makedirs(os.path.dirname(DB), exist_ok=True)
lock = threading.Lock()
scenario = {"mode": "normal", "hideLookups": 0}
with sqlite3.connect(DB) as db:
    db.execute("CREATE TABLE IF NOT EXISTS shipments(id INTEGER PRIMARY KEY, reference TEXT NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS calls(kind TEXT NOT NULL)")
    if "service_type" not in {row[1] for row in db.execute("PRAGMA table_info(shipments)")}:
        db.execute("ALTER TABLE shipments ADD COLUMN service_type TEXT NOT NULL DEFAULT 'STANDARD'")

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never log address payloads.

    def respond(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if status == 429:
            self.send_header("Retry-After", "2")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        data = json.loads(self.read_body())
        with lock:
            if self.path == "/admin/scenario":
                scenario.update(data)
                self.respond(200, scenario)
                return
            expected = "/shipments" if CARRIER == "alpha" else "/consignments"
            if self.path != expected:
                self.respond(404, {})
                return
            mode = scenario["mode"]
            if mode in ("reject", "unavailable", "rate-limit", "auth"):
                self.respond({"reject": 422, "unavailable": 503, "rate-limit": 429, "auth": 401}[mode], {})
                return
            if CARRIER == "alpha":
                service_type = data.get("serviceType", "STANDARD")
            else:
                service_type = {"REGULAR": "STANDARD", "PRIORITY": "EXPRESS"}.get(data.get("serviceLevel", "REGULAR"))
            if service_type not in ("STANDARD", "EXPRESS"):
                self.respond(422, {"code": "UNSUPPORTED_SERVICE"})
                return
            reference = data["clientReference" if CARRIER == "alpha" else "reference"]
            with sqlite3.connect(DB) as db:
                db.execute("INSERT INTO calls VALUES ('CREATE')")
                row = db.execute("SELECT id FROM shipments WHERE reference=? ORDER BY id LIMIT 1", (reference,)).fetchone()
                if CARRIER == "beta" or row is None:
                    cursor = db.execute("INSERT INTO shipments(reference, service_type) VALUES (?, ?)", (reference, service_type))
                    number = cursor.lastrowid
                else:
                    number = row[0]
            # Commit the external effect before losing the response.
            delay = scenario.pop("delayResponseSeconds", 0)
            drop = mode == "lost-response"
            if drop:
                scenario["mode"] = "normal"
        if delay:
            time.sleep(min(delay, 30))
        if drop:
            self.close_connection = True
            return
        try:
            self.respond(201, self.result(number))
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        with lock:
            if self.path == "/health":
                self.respond(200, {"status": "UP"})
                return
            with sqlite3.connect(DB) as db:
                if self.path == "/admin/stats":
                    self.respond(200, {
                        "shipments": db.execute("SELECT count(*) FROM shipments").fetchone()[0],
                        "expressShipments": db.execute("SELECT count(*) FROM shipments WHERE service_type='EXPRESS'").fetchone()[0],
                        "createCalls": db.execute("SELECT count(*) FROM calls WHERE kind='CREATE'").fetchone()[0]})
                    return
                if scenario["mode"] == "unavailable":
                    self.respond(503, {})
                    return
                if scenario.get("hideLookups", 0) > 0:
                    scenario["hideLookups"] -= 1
                    self.respond(404, {})
                    return
                url = urlparse(self.path)
                reference = (url.path.removeprefix("/shipments/by-reference/") if CARRIER == "alpha"
                             else parse_qs(url.query).get("reference", [""])[0])
                row = db.execute("SELECT id FROM shipments WHERE reference=? ORDER BY id LIMIT 1", (reference,)).fetchone()
                self.respond(200 if row else 404, self.result(row[0]) if row else {})

    def result(self, number):
        tracking = CARRIER.upper() + "-" + str(number)
        label = "https://labels.example/" + tracking
        return ({"trackingNumber": tracking, "labelUrl": label} if CARRIER == "alpha"
                else {"number": tracking, "document": label})

    def read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            return self.rfile.read(int(self.headers.get("Content-Length", "0")))
        chunks = []
        while True:
            size = int(self.rfile.readline().split(b";", 1)[0].strip(), 16)
            if size == 0:
                while self.rfile.readline().strip():
                    pass
                return b"".join(chunks)
            chunks.append(self.rfile.read(size))
            self.rfile.read(2)

ThreadingHTTPServer(("0.0.0.0", 8_080), Handler).serve_forever()
