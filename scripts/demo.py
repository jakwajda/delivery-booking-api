#!/usr/bin/env python3
"""Local Compose demo; --crash kills only this project's worker."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
API, ALPHA, BETA = ("http://localhost:" + str(port) for port in (8_080, 9_091, 9_092))

def http(base, path, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(base + path, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.load(response)

def wait_for(operation, predicate, timeout=60):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = operation()
            if predicate(last):
                return last
        except (OSError, ValueError):
            pass
        time.sleep(0.2)
    raise AssertionError(f"Deadline exceeded; last result: {last}")

def payload(country="PL", service_type="STANDARD"):
    return {"orderReference": "DEMO", "sender": {
        "country": "PL", "city": "Warsaw", "postalCode": "00-001", "street": "Example 1"
    }, "recipient": {
        "country": country, "city": "Example", "postalCode": "00-001", "street": "Example 2"
    }, "weightGrams": 1_234, "serviceType": service_type}

def submit(country="PL", key=None, service_type="STANDARD"):
    return http(API, "/api/v1/bookings", payload(country, service_type), key or str(uuid.uuid4()))

def booked(booking):
    result = wait_for(lambda: http(API, "/api/v1/bookings/" + booking["id"]),
                      lambda value: value["status"] in ("BOOKED", "FAILED", "REJECTED", "REQUIRES_RECONCILIATION"))
    assert result["status"] == "BOOKED", result
    result = http(API, "/api/v1/bookings/" + booking["id"] + "/result")
    assert result["trackingNumber"] and result["labelUrl"]
    return result

def compose(*arguments):
    subprocess.run(["docker", "compose", "--profile", "application", *arguments], cwd=ROOT, check=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--crash", action="store_true")
    args = parser.parse_args()
    wait_for(lambda: http(API, "/actuator/health/readiness"), lambda value: value["status"] == "UP")
    for provider in (ALPHA, BETA):
        http(provider, "/admin/scenario", {"mode": "normal", "hideLookups": 0, "delayResponseSeconds": 0})
    key = str(uuid.uuid4())
    booking = submit(key=key)
    assert submit(key=key)["id"] == booking["id"]
    booked(booking)
    print("PASS: idempotent input and confirmed result", flush=True)
    for country, provider in (("PL", ALPHA), ("DE", BETA)):
        before = http(provider, "/admin/stats")["expressShipments"]
        express_key = str(uuid.uuid4())
        express = submit(country, express_key, "EXPRESS")
        assert submit(country, express_key, "EXPRESS")["id"] == express["id"]
        booked(express)
        assert http(provider, "/admin/stats")["expressShipments"] == before + 1
    print("PASS: EXPRESS booked with both carriers, retries return the same booking", flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        bookings = list(pool.map(submit, ["PL", "DE"] * 5))
        list(pool.map(booked, bookings))
    print("PASS: 10 bookings processed by the worker pool", flush=True)
    before = http(BETA, "/admin/stats")
    http(BETA, "/admin/scenario", {"mode": "lost-response", "hideLookups": 2})
    booked(submit("DE"))
    after = http(BETA, "/admin/stats")
    assert after["shipments"] - before["shipments"] == 1
    assert after["createCalls"] - before["createCalls"] == 1
    print("PASS: Beta lost response and delayed lookup; one create and one shipment", flush=True)
    if args.crash:
        compose("up", "-d", "--no-build", "--scale", "worker=1")
        before = http(ALPHA, "/admin/stats")
        http(ALPHA, "/admin/scenario", {"mode": "normal", "delayResponseSeconds": 15})
        booking = submit()
        wait_for(lambda: http(ALPHA, "/admin/stats"), lambda value: value["shipments"] > before["shipments"])
        compose("kill", "-s", "SIGKILL", "worker")
        compose("up", "-d", "--no-build", "--scale", "worker=2")
        booked(booking)
        after = http(ALPHA, "/admin/stats")
        assert after["shipments"] - before["shipments"] == 1
        assert after["createCalls"] - before["createCalls"] == 1
        print("PASS: killed worker after external commit; another worker recovered by lookup", flush=True)
    print("Demo complete. Data retained; stop with docker compose --profile application down.", flush=True)

if __name__ == "__main__":
    main()
