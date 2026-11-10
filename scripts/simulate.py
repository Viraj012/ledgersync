#!/usr/bin/env python3
"""
LedgerSync load simulator. Python 3 standard library only, nothing to install.

Fires many signed Stripe-style webhooks at a running LedgerSync, including duplicate deliveries,
refunds that arrive before their payment, and a flaky ERP. It then waits for the sync worker, reconciles,
drains the failure queue, reconciles again, and prints a report.

    python3 scripts/simulate.py                       # defaults: 500 orders, 30% ERP failure rate
    python3 scripts/simulate.py --orders 2000 --erp-failure-rate 0.5
"""
import argparse
import hashlib
import hmac
import json
import os
import random
import secrets
import sys
import time
import urllib.error
import urllib.request
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

DEFAULT_SECRET = "whsec_local_demo_secret"


def load_secret():
    if os.environ.get("STRIPE_WEBHOOK_SECRET"):
        return os.environ["STRIPE_WEBHOOK_SECRET"]
    env_file = Path(__file__).resolve().parent.parent / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            if line.startswith("STRIPE_WEBHOOK_SECRET="):
                return line.split("=", 1)[1].strip()
    return DEFAULT_SECRET


class Api:
    def __init__(self, base_url, secret):
        self.base_url = base_url.rstrip("/")
        self.secret = secret.encode()

    def request(self, method, path, body=None, headers=None):
        data = body.encode() if isinstance(body, str) else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base_url + path, data=data, method=method,
                                     headers={"Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
                return resp.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            return e.code, None

    def get(self, path):
        return self.request("GET", path)[1]

    def webhook(self, payload):
        """Signs exactly like Stripe: HMAC-SHA256 over '{timestamp}.{payload}'."""
        ts = str(int(time.time()))
        sig = hmac.new(self.secret, f"{ts}.{payload}".encode(), hashlib.sha256).hexdigest()
        return self.request("POST", "/webhooks/stripe", payload, {"Stripe-Signature": f"t={ts},v1={sig}"})[0]


def payment_event(event_id, pi, amount):
    return json.dumps({"id": event_id, "object": "event", "type": "payment_intent.succeeded",
                       "data": {"object": {"id": pi, "object": "payment_intent", "amount": amount,
                                           "amount_received": amount, "currency": "usd",
                                           "receipt_email": f"{pi}@example.com"}}})


def refund_event(event_id, pi, refunded):
    return json.dumps({"id": event_id, "object": "event", "type": "charge.refunded",
                       "data": {"object": {"id": "ch_" + pi, "object": "charge", "payment_intent": pi,
                                           "amount_refunded": refunded, "currency": "usd"}}})


def build_orders(n, run_id, args, rng):
    """Each order is a list of deliveries (payloads, duplicates included) in the order they'll be sent."""
    plans = []
    for i in range(n):
        pi = f"pi_sim_{run_id}_{i}"
        amount = rng.randint(500, 50_000)
        events = [payment_event(f"evt_{run_id}_{i}_pay", pi, amount)]
        if rng.random() < args.refund_rate:
            refunded = amount if rng.random() < 0.5 else rng.randint(100, amount - 1)
            events.append(refund_event(f"evt_{run_id}_{i}_ref", pi, refunded))
            if rng.random() < args.out_of_order_rate:
                events.reverse()  # refund delivered before its payment
        deliveries = []
        for e in events:
            deliveries.append(e)
            if rng.random() < args.duplicate_rate:
                deliveries.extend([e] * rng.randint(1, 3))  # Stripe retrying the same event
        plans.append(deliveries)
    return plans


def deliver(api, deliveries):
    """
    Sends one order's events. A 503 means "retry later", so like Stripe we put that event back at the end
    of the queue and redeliver it after the others (e.g. after the payment it was waiting for).
    """
    queue = deque(deliveries)
    sent = deferred = 0
    while queue:
        payload = queue.popleft()
        status = api.webhook(payload)
        sent += 1
        if status == 503:
            deferred += 1
            if deferred > 50:
                raise RuntimeError("Event kept getting 503; giving up")
            queue.append(payload)
            time.sleep(0.05)
        elif status != 200:
            raise RuntimeError(f"Unexpected HTTP {status} from webhook")
    return sent, deferred


def open_jobs(stats):
    jobs = stats["syncJobs"]
    return jobs.get("PENDING", 0) + jobs.get("IN_PROGRESS", 0)


def wait_for_sync(api, timeout_s, label):
    start = time.time()
    while True:
        stats = api.get("/stats")
        remaining = open_jobs(stats)
        print(f"\r  {label}: {remaining:5d} sync jobs still open ({time.time() - start:4.0f}s)", end="", flush=True)
        if remaining == 0:
            print()
            return stats
        if time.time() - start > timeout_s:
            print("\n  Timed out waiting for the sync worker.")
            return stats
        time.sleep(1)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("--orders", type=int, default=500)
    p.add_argument("--duplicate-rate", type=float, default=0.3, help="chance an event is delivered more than once")
    p.add_argument("--refund-rate", type=float, default=0.25)
    p.add_argument("--out-of-order-rate", type=float, default=0.2, help="chance a refund arrives before its payment")
    p.add_argument("--erp-failure-rate", type=float, default=0.3)
    p.add_argument("--concurrency", type=int, default=16)
    p.add_argument("--timeout", type=int, default=300, help="seconds to wait for the worker")
    p.add_argument("--seed", type=int)
    args = p.parse_args()

    api = Api(args.base_url, load_secret())
    rng = random.Random(args.seed)
    run_id = secrets.token_hex(3)

    try:
        before = api.get("/stats")
    except urllib.error.URLError:
        sys.exit(f"LedgerSync isn't reachable at {args.base_url}. Start it with: docker compose up --build")

    print(f"LedgerSync simulation {run_id}: {args.orders} orders, ERP failure rate {args.erp_failure_rate:.0%}")
    api.request("PUT", "/mock-erp/config", {"failureRate": args.erp_failure_rate, "latencyMs": 0})

    plans = build_orders(args.orders, run_id, args, rng)
    unique_events = sum(len(set(d)) for d in plans)
    refunds = sum(1 for d in plans if any('"charge.refunded"' in e for e in d))

    print("1. Sending webhooks...")
    t0 = time.time()
    with ThreadPoolExecutor(args.concurrency) as pool:
        results = list(pool.map(lambda d: deliver(api, d), plans))
    send_s = time.time() - t0
    sent = sum(r[0] for r in results)
    deferred = sum(r[1] for r in results)
    print(f"  {sent} deliveries in {send_s:.1f}s ({sent / send_s:.0f}/s)")

    print("2. Waiting for the sync worker to push everything to the flaky ERP...")
    after_sync = wait_for_sync(api, args.timeout, "syncing")
    failed_jobs = api.get("/sync-jobs?status=FAILED&limit=1000")

    print("3. Reconciling Stripe side vs ERP side...")
    _, first_recon = api.request("POST", "/reconciliation/runs")

    print(f"4. ERP recovers; retrying {len(failed_jobs)} job(s) from the failure queue...")
    api.request("PUT", "/mock-erp/config", {"failureRate": 0.0, "latencyMs": 0})
    for job in failed_jobs:
        api.request("POST", f"/sync-jobs/{job['id']}/retry")
    final = wait_for_sync(api, args.timeout, "recovering")
    _, final_recon = api.request("POST", "/reconciliation/runs")

    def delta(path_keys, a=before, b=final):
        def dig(d):
            for k in path_keys:
                d = d.get(k, 0) if isinstance(d, dict) else 0
            return d
        return dig(b) - dig(a)

    # The DB may hold earlier runs' data (or records edited by hand); only judge this run's orders.
    def split_issues(report):
        mine = [i for i in report["issues"] if (i["externalRef"] or "").startswith(f"pi_sim_{run_id}_")]
        return len(mine), len(report["issues"]) - len(mine)

    issues_before, _ = split_issues(first_recon)
    issues_after, older_issues = split_issues(final_recon)

    orders_created = sum(final["orders"].values()) - sum(before["orders"].values())
    refunds_recorded = (delta(["orders", "PARTIALLY_REFUNDED"]) + delta(["orders", "REFUNDED"]))
    erp_invoices = delta(["erpRecords"])
    dup_sent = sent - unique_events - deferred
    dup_rejected = delta(["webhookDeliveriesSinceStart", "duplicate"])
    erp_failures = delta(["erpAttemptsSinceStart", "retry"]) + delta(["erpAttemptsSinceStart", "failed"])
    jobs_total = sum(final["syncJobs"].values()) - sum(before["syncJobs"].values())

    print()
    print("=" * 62)
    print(f" LedgerSync simulation report  (run {run_id})")
    print("=" * 62)
    print(f" Webhook deliveries sent          {sent:>8}   ({sent / send_s:.0f}/s)")
    print(f"   unique Stripe events           {unique_events:>8}")
    print(f"   duplicate deliveries           {dup_sent:>8}")
    print(f"   out-of-order (503, redelivered){deferred:>8}")
    print(f" Duplicates rejected              {dup_rejected:>8}")
    print(f" Orders created                   {orders_created:>8}   (expected {args.orders})")
    print(f"   with refunds recorded          {refunds_recorded:>8}   (expected {refunds})")
    print(f" Duplicate orders created         {orders_created - args.orders:>8}")
    print(f" ERP invoices created             {erp_invoices:>8}   (expected {args.orders})")
    print(f" ERP sync jobs                    {jobs_total:>8}")
    print(f"   failed ERP calls (retried)     {erp_failures:>8}")
    print(f"   jobs that needed a retry       {delta(['syncJobsRetried']):>8}")
    print(f"   exhausted retries -> FAILED    {len(failed_jobs):>8}   (recovered via failure queue)")
    print(f" Reconciliation issues, before    {issues_before:>8}   (run #{first_recon['run']['id']})")
    print(f" Reconciliation issues, after     {issues_after:>8}   (run #{final_recon['run']['id']})")
    if older_issues:
        print(f"   + {older_issues} issue(s) on data from earlier runs, not this one (see run #{final_recon['run']['id']})")
    consistent = (orders_created == args.orders and refunds_recorded == refunds and erp_invoices == args.orders
                  and dup_rejected == dup_sent and issues_after == 0 and open_jobs(final) == 0)
    print(f" Final consistency                {'100% ✔' if consistent else 'NOT CONSISTENT ✘':>8}")
    print("=" * 62)
    sys.exit(0 if consistent else 1)


if __name__ == "__main__":
    main()
