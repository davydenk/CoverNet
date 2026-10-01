#!/usr/bin/env python3
"""Append GitHub traffic (views/clones per day) and release download counts to stats/*.jsonl.

GitHub only keeps 14 days of traffic; running this daily (see .github/workflows/traffic.yml) builds a history.
Needs GITHUB_REPOSITORY and TRAFFIC_TOKEN (fine-grained PAT, repository permission "Administration: read")."""
import json, os, sys, urllib.request, datetime

repo = os.environ["GITHUB_REPOSITORY"]
token = os.environ.get("TRAFFIC_TOKEN") or os.environ.get("GITHUB_TOKEN")
hdr = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}

def get(path):
    with urllib.request.urlopen(urllib.request.Request(f"https://api.github.com/repos/{repo}{path}", headers=hdr)) as r:
        return json.load(r)

def merge(path, rows, key):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    have = {}
    if os.path.exists(path):
        for line in open(path):
            if line.strip():
                d = json.loads(line); have[d[key]] = d
    for d in rows: have[d[key]] = d          # later runs overwrite the same day (counts only grow within a day)
    with open(path, "w") as f:
        for k in sorted(have): f.write(json.dumps(have[k]) + "\n")

try:
    views = get("/traffic/views")["views"]
    clones = get("/traffic/clones")["clones"]
except Exception as e:
    print("traffic endpoints unavailable (token lacks Administration:read?):", e, file=sys.stderr)
    views, clones = [], []
merge("stats/views.jsonl", [{"day": v["timestamp"][:10], "count": v["count"], "uniques": v["uniques"]} for v in views], "day")
merge("stats/clones.jsonl", [{"day": c["timestamp"][:10], "count": c["count"], "uniques": c["uniques"]} for c in clones], "day")

today = datetime.date.today().isoformat()
dl = [{"day": today, "tag": r["tag_name"], "asset": a["name"], "downloads": a["download_count"]}
      for r in get("/releases") for a in r.get("assets", [])]
merge("stats/downloads.jsonl", [dict(d, key=f'{d["day"]}|{d["tag"]}|{d["asset"]}') for d in dl], "key")
print(f"views={len(views)} days, clones={len(clones)} days, release assets={len(dl)}")
