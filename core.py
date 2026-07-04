"""Platform-agnostic Claude usage logic: local token scanning, account usage API, popover HTML."""
import glob
import json
import os
import ssl
import urllib.error
import urllib.request
from datetime import datetime, timezone

import certifi

PROJECTS_DIR = os.path.expanduser("~/.claude/projects")
USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
CONTEXT_WINDOW_LIMIT = 200_000
REFRESH_SECONDS = 300


def fetch_account_usage(get_access_token):
    """get_access_token: platform-specific callable returning the Claude Code OAuth token or None."""
    token = get_access_token()
    if not token:
        return None
    req = urllib.request.Request(
        USAGE_URL,
        headers={"Authorization": f"Bearer {token}", "anthropic-beta": "oauth-2025-04-20"},
    )
    ctx = ssl.create_default_context(cafile=certifi.where())
    try:
        with urllib.request.urlopen(req, timeout=5, context=ctx) as resp:
            data = json.loads(resp.read().decode())
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        return None
    return {"five_hour": data.get("five_hour"), "seven_day": data.get("seven_day")}


def fmt_relative(iso_ts):
    try:
        dt = datetime.fromisoformat(iso_ts)
    except (TypeError, ValueError):
        return "?"
    delta = dt - datetime.now(timezone.utc)
    total_minutes = max(0, int(delta.total_seconds() // 60))
    days, rem = divmod(total_minutes, 1440)
    hours, minutes = divmod(rem, 60)
    if days:
        return f"in {days}d {hours}h"
    if hours:
        return f"in {hours}h {minutes}m" if minutes else f"in {hours}h"
    return f"in {minutes}m"


def fmt_tokens(n):
    if n >= 1_000_000:
        return f"{n / 1_000_000:.2f}M"
    if n >= 1_000:
        return f"{n / 1_000:.1f}K"
    return str(n)


def scan_usage():
    today = datetime.now(timezone.utc).date()
    totals = {"input": 0, "output": 0, "cache_read": 0, "cache_creation": 0}
    for path in glob.glob(os.path.join(PROJECTS_DIR, "*", "*.jsonl")):
        try:
            with open(path, "r", errors="ignore") as fh:
                for line in fh:
                    if '"usage"' not in line:
                        continue
                    try:
                        rec = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    ts = rec.get("timestamp")
                    if not ts:
                        continue
                    try:
                        dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
                    except ValueError:
                        continue
                    if dt.date() != today:
                        continue
                    usage = rec.get("message", {}).get("usage")
                    if not usage:
                        continue
                    totals["input"] += usage.get("input_tokens", 0)
                    totals["output"] += usage.get("output_tokens", 0)
                    totals["cache_read"] += usage.get("cache_read_input_tokens", 0)
                    totals["cache_creation"] += usage.get("cache_creation_input_tokens", 0)
        except OSError:
            continue
    return totals


def scan_active_session():
    files = glob.glob(os.path.join(PROJECTS_DIR, "*", "*.jsonl"))
    if not files:
        return None
    latest = max(files, key=os.path.getmtime)
    last_usage = None
    try:
        with open(latest, "r", errors="ignore") as fh:
            for line in fh:
                if '"usage"' not in line:
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    continue
                usage = rec.get("message", {}).get("usage")
                if usage:
                    last_usage = usage
    except OSError:
        return None
    if not last_usage:
        return None
    ctx = (
        last_usage.get("input_tokens", 0)
        + last_usage.get("cache_read_input_tokens", 0)
        + last_usage.get("cache_creation_input_tokens", 0)
    )
    return ctx, CONTEXT_WINDOW_LIMIT


def bar_html(pct):
    pct = max(0, min(100, pct))
    return f'<div class="track"><div class="fill" style="width:{pct}%"></div></div>'


def render_html(account, totals, active, stale=False, bridge_script="function bridge(msg){window.webkit.messageHandlers.bridge.postMessage(msg);}"):
    sections = ""
    if account and account.get("five_hour"):
        fh = account["five_hour"]
        left = 100 - fh["utilization"]
        sections += f"""
        <div class="metric">
          <div class="metric-label">Session</div>
          {bar_html(fh["utilization"])}
          <div class="metric-row"><span>{left:.0f}% left</span><span class="muted">Resets {fmt_relative(fh["resets_at"])}</span></div>
        </div>"""
    if account and account.get("seven_day"):
        sd = account["seven_day"]
        left = 100 - sd["utilization"]
        sections += f"""
        <div class="metric">
          <div class="metric-label">Weekly</div>
          {bar_html(sd["utilization"])}
          <div class="metric-row"><span>{left:.0f}% left</span><span class="muted">Resets {fmt_relative(sd["resets_at"])}</span></div>
        </div>"""
    if not sections:
        sections = '<div class="metric muted">Account usage unavailable — is Claude Code logged in?</div>'

    total_tokens = sum(totals.values())
    extra_rows = f"""
        <div class="row"><span>Today</span><span>{fmt_tokens(total_tokens)} tokens</span></div>"""
    if active:
        ctx_tokens, limit = active
        pct = ctx_tokens / limit * 100
        extra_rows += f"""
        <div class="row"><span>Context window</span><span>{pct:.0f}%</span></div>"""

    now = datetime.now().strftime("%H:%M:%S")
    updated_label = f"Updated {now}" + (" (stale — API rate limited)" if stale else "")

    return f"""
<!doctype html><html><head><meta charset="utf-8"><style>
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; padding: 18px; background: #1c1c1e; color: #fff;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "SF Pro Text", sans-serif;
    -webkit-user-select: none; user-select: none;
  }}
  .card {{ background: #2c2c2e; border-radius: 16px; padding: 20px; }}
  .header {{ display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; }}
  .header .title {{ font-size: 18px; font-weight: 700; }}
  .header .plan {{ font-size: 13px; color: #9a9a9e; margin-left: 8px; }}
  .metric {{ margin-bottom: 20px; }}
  .metric-label {{ font-size: 15px; font-weight: 600; margin-bottom: 10px; }}
  .track {{ height: 8px; border-radius: 4px; background: #48484a; overflow: hidden; }}
  .fill {{ height: 100%; background: #0a84ff; border-radius: 4px; }}
  .metric-row {{ display: flex; justify-content: space-between; font-size: 14px; margin-top: 8px; color: #d0d0d2; }}
  .muted {{ color: #8e8e93; }}
  .row {{ display: flex; justify-content: space-between; font-size: 15px; padding: 8px 0; border-top: 1px solid #3a3a3c; }}
  .footer {{ display: flex; justify-content: space-between; align-items: center; margin-top: 16px; font-size: 12px; color: #8e8e93; }}
  .footer a {{ color: #0a84ff; text-decoration: none; cursor: pointer; }}
</style>
<script>{bridge_script}</script>
</head>
<body>
  <div class="card">
    <div class="header"><span><span class="title" style="color:#DA7857">✻</span> <span class="title">Claude</span><span class="plan">Code CLI</span></span></div>
    {sections}
    {extra_rows}
    <div class="footer">
      <span>{updated_label}</span>
      <span><a onclick="bridge('refresh')">Refresh</a> &nbsp;·&nbsp; <a onclick="bridge('quit')">Quit</a></span>
    </div>
  </div>
</body></html>
"""
