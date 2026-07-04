#!/usr/bin/env python3
"""Menu bar widget: Claude usage tracker, styled after robinebers/openusage."""
import glob
import json
import os
import ssl
import subprocess
import urllib.error
import urllib.request
from datetime import datetime, timezone

import certifi
import objc
from AppKit import (
    NSApplication, NSApp, NSStatusBar, NSPopover, NSViewController, NSView,
    NSMakeRect, NSApplicationActivationPolicyAccessory, NSFont, NSColor,
    NSMutableAttributedString, NSAttributedString, NSFontAttributeName,
    NSForegroundColorAttributeName,
)
from Foundation import NSObject, NSTimer, NSMakeSize
from WebKit import WKWebView, WKWebViewConfiguration, WKUserContentController

CLAUDE_ORANGE = NSColor.colorWithRed_green_blue_alpha_(0.851, 0.471, 0.341, 1.0)  # #DA7857
CLAUDE_GLYPH = "✻"

PROJECTS_DIR = os.path.expanduser("~/.claude/projects")
REFRESH_SECONDS = 60
KEYCHAIN_SERVICE = "Claude Code-credentials"
USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
CONTEXT_WINDOW_LIMIT = 200_000


def get_access_token():
    try:
        raw = subprocess.check_output(
            ["/usr/bin/security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-w"],
            stderr=subprocess.DEVNULL,
        ).decode()
        return json.loads(raw)["claudeAiOauth"]["accessToken"]
    except (subprocess.CalledProcessError, KeyError, json.JSONDecodeError):
        return None


def fetch_account_usage():
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


def render_html(account, totals, active):
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

    return f"""
<!doctype html><html><head><meta charset="utf-8"><style>
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; padding: 18px; background: #1c1c1e; color: #fff;
    font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif;
    -webkit-user-select: none;
  }}
  .card {{ background: #2c2c2e; border-radius: 14px; padding: 16px; }}
  .header {{ display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; }}
  .header .title {{ font-size: 15px; font-weight: 700; }}
  .header .plan {{ font-size: 12px; color: #9a9a9e; margin-left: 6px; }}
  .metric {{ margin-bottom: 16px; }}
  .metric-label {{ font-size: 13px; font-weight: 600; margin-bottom: 8px; }}
  .track {{ height: 6px; border-radius: 3px; background: #48484a; overflow: hidden; }}
  .fill {{ height: 100%; background: #0a84ff; border-radius: 3px; }}
  .metric-row {{ display: flex; justify-content: space-between; font-size: 12px; margin-top: 6px; color: #d0d0d2; }}
  .muted {{ color: #8e8e93; }}
  .row {{ display: flex; justify-content: space-between; font-size: 13px; padding: 6px 0; border-top: 1px solid #3a3a3c; }}
  .footer {{ display: flex; justify-content: space-between; align-items: center; margin-top: 12px; font-size: 11px; color: #8e8e93; }}
  .footer a {{ color: #0a84ff; text-decoration: none; cursor: pointer; }}
</style></head>
<body>
  <div class="card">
    <div class="header"><span><span class="title" style="color:#DA7857">✻</span> <span class="title">Claude</span><span class="plan">Code CLI</span></span></div>
    {sections}
    {extra_rows}
    <div class="footer">
      <span>Updated {now}</span>
      <span><a onclick="window.webkit.messageHandlers.bridge.postMessage('refresh')">Refresh</a> &nbsp;·&nbsp; <a onclick="window.webkit.messageHandlers.bridge.postMessage('quit')">Quit</a></span>
    </div>
  </div>
</body></html>
"""


class ScriptMessageHandler(NSObject):
    def initWithApp_(self, app):
        self = objc.super(ScriptMessageHandler, self).init()
        self.app = app
        return self

    def userContentController_didReceiveScriptMessage_(self, controller, message):
        body = message.body()
        if body == "refresh":
            self.app.refresh()
        elif body == "quit":
            NSApp.terminate_(None)


class AppDelegate(NSObject):
    def applicationDidFinishLaunching_(self, notification):
        self.status_item = NSStatusBar.systemStatusBar().statusItemWithLength_(-1)
        self.status_item.button().setTitle_(f"{CLAUDE_GLYPH} …")
        self.status_item.button().setAction_("togglePopover:")
        self.status_item.button().setTarget_(self)

        controller = NSViewController.alloc().init()
        config = WKWebViewConfiguration.alloc().init()
        self.handler = ScriptMessageHandler.alloc().initWithApp_(self)
        content_controller = WKUserContentController.alloc().init()
        content_controller.addScriptMessageHandler_name_(self.handler, "bridge")
        config.setUserContentController_(content_controller)

        self.webview = WKWebView.alloc().initWithFrame_configuration_(
            NSMakeRect(0, 0, 400, 420), config
        )
        controller.setView_(self.webview)

        self.popover = NSPopover.alloc().init()
        self.popover.setContentSize_(NSMakeSize(400, 420))
        self.popover.setBehavior_(1)  # NSPopoverBehaviorTransient
        self.popover.setContentViewController_(controller)

        self.refresh()
        NSTimer.scheduledTimerWithTimeInterval_target_selector_userInfo_repeats_(
            REFRESH_SECONDS, self, "timerRefresh:", None, True
        )

    def timerRefresh_(self, timer):
        self.refresh()

    def refresh(self):
        totals = scan_usage()
        active = scan_active_session()
        account = fetch_account_usage()

        total_tokens = sum(totals.values())
        title_parts = [fmt_tokens(total_tokens)]
        if account and account.get("five_hour"):
            title_parts.append(f"S {account['five_hour']['utilization']:.0f}%")
        if account and account.get("seven_day"):
            title_parts.append(f"W {account['seven_day']['utilization']:.0f}%")
        text = "  ".join(title_parts)

        attributed = NSMutableAttributedString.alloc().initWithString_(f"{CLAUDE_GLYPH} {text}")
        attributed.addAttribute_value_range_(NSForegroundColorAttributeName, CLAUDE_ORANGE, (0, 1))
        self.status_item.button().setAttributedTitle_(attributed)

        html = render_html(account, totals, active)
        self.webview.loadHTMLString_baseURL_(html, None)

    def togglePopover_(self, sender):
        if self.popover.isShown():
            self.popover.performClose_(sender)
        else:
            self.popover.showRelativeToRect_ofView_preferredEdge_(
                self.status_item.button().bounds(), self.status_item.button(), 3
            )


if __name__ == "__main__":
    app = NSApplication.sharedApplication()
    app.setActivationPolicy_(NSApplicationActivationPolicyAccessory)
    delegate = AppDelegate.alloc().init()
    app.setDelegate_(delegate)
    app.run()
