"""Reads "DEP group:name:version" lines on stdin and asks OSV (osv.dev) which of them have known vulnerabilities.
Prints one line per finding and exits 1 if there are any. Network access to api.osv.dev is required.
Run with `python3 -I` (the script takes no file arguments)."""
import json
import sys
import urllib.request

BATCH = "https://api.osv.dev/v1/querybatch"
VULN = "https://api.osv.dev/v1/vulns/"


def post(url, payload):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return json.load(r)


def main():
    deps = []
    for line in sys.stdin:
        if line.startswith("DEP "):
            g, n, v = line[4:].strip().split(":")
            deps.append((f"{g}:{n}", v))
    if not deps:
        print("no dependencies were listed; nothing was checked", file=sys.stderr)
        return 2
    results = post(BATCH, {"queries": [{"package": {"ecosystem": "Maven", "name": n}, "version": v} for n, v in deps]})["results"]
    findings = []
    for (name, version), res in zip(deps, results):
        for vuln in res.get("vulns", []):
            findings.append((name, version, vuln["id"]))
    for name, version, vid in findings:
        try:
            d = get(VULN + vid)
            summary = (d.get("summary") or d.get("details") or "").strip().splitlines()[0][:110]
            fixed = sorted({e["fixed"] for a in d.get("affected", []) for r in a.get("ranges", []) for e in r.get("events", []) if "fixed" in e})
        except Exception:
            summary, fixed = "(details unavailable)", []
        print(f"{name}:{version}  {vid}  {summary}  fixed in: {', '.join(fixed) or 'unknown'}")
    print(f"checked {len(deps)} libraries, {len(findings)} known vulnerability record(s)")
    return 1 if findings else 0


sys.exit(main())
