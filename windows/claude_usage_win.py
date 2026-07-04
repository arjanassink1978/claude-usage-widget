#!/usr/bin/env python3
"""Windows system tray app: Claude usage tracker.

EXPERIMENTAL: built and packaged via CI, not verified on a real Windows
machine. Claude Code stores its OAuth token in the OS-native credential
store on every platform (macOS Keychain, confirmed working; Windows
Credential Manager / Linux libsecret, assumed by symmetry but unverified).
If token lookup fails, see README's Windows troubleshooting section.
"""
import json
import os
import sys
import threading
import time

import keyring
import pystray
import webview
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import core

KEYRING_SERVICE = "Claude Code-credentials"
BRIDGE_JS = (
    "function bridge(msg){"
    "if(msg==='refresh'){pywebview.api.refresh();}"
    "else if(msg==='quit'){pywebview.api.quit();}"
    "}"
)


def get_access_token():
    candidates = [os.environ.get("USERNAME"), os.environ.get("USER"), ""]
    for account in [c for c in candidates if c is not None]:
        try:
            raw = keyring.get_password(KEYRING_SERVICE, account)
        except Exception:
            raw = None
        if raw:
            try:
                return json.loads(raw)["claudeAiOauth"]["accessToken"]
            except (KeyError, json.JSONDecodeError, TypeError):
                continue

    fallback_paths = [
        os.path.join(os.environ.get("USERPROFILE", ""), ".claude", ".credentials.json"),
        os.path.expanduser("~/.claude/.credentials.json"),
    ]
    for path in fallback_paths:
        if os.path.isfile(path):
            try:
                with open(path) as fh:
                    return json.load(fh)["claudeAiOauth"]["accessToken"]
            except (KeyError, json.JSONDecodeError, OSError):
                continue
    return None


def make_icon_image():
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    orange = (218, 120, 87, 255)
    cx, cy, r = size // 2, size // 2, size // 2 - 6
    for i in range(6):
        import math
        angle = math.pi * i / 3
        x = cx + r * math.cos(angle)
        y = cy + r * math.sin(angle)
        draw.line([(cx, cy), (x, y)], fill=orange, width=8)
    return img


class AppState:
    def __init__(self):
        self.window = None
        self.icon = None
        self.last_account = None
        self.visible = False

    def refresh(self):
        totals = core.scan_usage()
        active = core.scan_active_session()
        fetched = core.fetch_account_usage(get_access_token)
        if fetched:
            self.last_account = fetched
        account = self.last_account

        html = core.render_html(
            account, totals, active,
            stale=(fetched is None and account is not None),
            bridge_script=BRIDGE_JS,
        )
        if self.window:
            self.window.load_html(html)

        if self.icon:
            parts = []
            if account and account.get("five_hour"):
                parts.append(f"Session {account['five_hour']['utilization']:.0f}%")
            if account and account.get("seven_day"):
                parts.append(f"Weekly {account['seven_day']['utilization']:.0f}%")
            self.icon.title = " · ".join(parts) if parts else "Claude usage"

    def toggle_window(self, icon=None, item=None):
        if self.visible:
            self.window.hide()
        else:
            self.window.show()
        self.visible = not self.visible

    def quit(self, icon=None, item=None):
        if self.icon:
            self.icon.stop()
        os._exit(0)


class Api:
    def __init__(self, state):
        self.state = state

    def refresh(self):
        self.state.refresh()

    def quit(self):
        self.state.quit()


def refresh_loop(state):
    while True:
        try:
            state.refresh()
        except Exception:
            pass
        time.sleep(core.REFRESH_SECONDS)


def main():
    state = AppState()
    api = Api(state)

    initial_html = core.render_html(None, {"input": 0, "output": 0, "cache_read": 0, "cache_creation": 0}, None, bridge_script=BRIDGE_JS)
    window = webview.create_window(
        "Claude Usage", html=initial_html, width=400, height=440,
        hidden=True, on_top=True, js_api=api,
    )
    state.window = window

    icon = pystray.Icon(
        "claude-usage",
        make_icon_image(),
        "Claude usage",
        menu=pystray.Menu(
            pystray.MenuItem("Open", state.toggle_window, default=True),
            pystray.MenuItem("Refresh", lambda i, it: state.refresh()),
            pystray.MenuItem("Quit", state.quit),
        ),
    )
    state.icon = icon

    threading.Thread(target=icon.run, daemon=True).start()
    threading.Thread(target=refresh_loop, args=(state,), daemon=True).start()

    webview.start()


if __name__ == "__main__":
    main()
