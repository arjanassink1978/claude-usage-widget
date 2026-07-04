#!/usr/bin/env python3
"""macOS menu bar app: Claude usage tracker."""
import json
import os
import subprocess
import sys

import objc
from AppKit import (
    NSApplication, NSApp, NSStatusBar, NSPopover, NSViewController,
    NSMakeRect, NSApplicationActivationPolicyAccessory, NSColor,
    NSMutableAttributedString, NSFontAttributeName, NSForegroundColorAttributeName,
)
from Foundation import NSObject, NSTimer, NSMakeSize
from WebKit import WKWebView, WKWebViewConfiguration, WKUserContentController

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import core

CLAUDE_ORANGE = NSColor.colorWithRed_green_blue_alpha_(0.851, 0.471, 0.341, 1.0)  # #DA7857
CLAUDE_GLYPH = "✻"
KEYCHAIN_SERVICE = "Claude Code-credentials"


def get_access_token():
    try:
        raw = subprocess.check_output(
            ["/usr/bin/security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-w"],
            stderr=subprocess.DEVNULL,
        ).decode()
        return json.loads(raw)["claudeAiOauth"]["accessToken"]
    except (subprocess.CalledProcessError, KeyError, json.JSONDecodeError):
        return None


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

        self.last_account = None
        self.refresh()
        NSTimer.scheduledTimerWithTimeInterval_target_selector_userInfo_repeats_(
            core.REFRESH_SECONDS, self, "timerRefresh:", None, True
        )

    def timerRefresh_(self, timer):
        self.refresh()

    def refresh(self):
        totals = core.scan_usage()
        active = core.scan_active_session()
        fetched = core.fetch_account_usage(get_access_token)
        if fetched:
            self.last_account = fetched
        account = self.last_account

        total_tokens = sum(totals.values())
        title_parts = [core.fmt_tokens(total_tokens)]
        if account and account.get("five_hour"):
            title_parts.append(f"S {account['five_hour']['utilization']:.0f}%")
        if account and account.get("seven_day"):
            title_parts.append(f"W {account['seven_day']['utilization']:.0f}%")
        text = "  ".join(title_parts)

        attributed = NSMutableAttributedString.alloc().initWithString_(f"{CLAUDE_GLYPH} {text}")
        attributed.addAttribute_value_range_(NSForegroundColorAttributeName, CLAUDE_ORANGE, (0, 1))
        self.status_item.button().setAttributedTitle_(attributed)

        html = core.render_html(account, totals, active, stale=(fetched is None and account is not None))
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
