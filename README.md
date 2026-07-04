# claude-usage-widget

macOS menu bar widget showing Claude usage — Claude Code local token counts plus your account's 5-hour session and weekly limits, styled after [openusage](https://github.com/robinebers/openusage).

## What it shows

- Menu bar: today's Claude Code token count, 5-hour session %, weekly %
- Popover: session/weekly progress bars with reset countdowns, today's token total, active conversation's context-window usage

## How it works

- Local token counts come from Claude Code's session logs in `~/.claude/projects/**/*.jsonl`
- Session/weekly usage comes from `GET https://api.anthropic.com/api/oauth/usage`, using the OAuth token Claude Code already stores in the macOS Keychain (`Claude Code-credentials`) — the same token the `claude` CLI uses. Read-only: this app never sends messages or modifies your account.

## Requirements

- macOS
- Python 3
- [Claude Code](https://docs.anthropic.com/en/docs/claude-code) installed and logged in (`claude` then `/login`)

## Install

```bash
pip3 install -r requirements.txt
python3 claude_usage.py
```

## Run at login

Copy `com.arjan.claude-usage-widget.plist` (adjust paths for your machine) into `~/Library/LaunchAgents/`, then:

```bash
launchctl load ~/Library/LaunchAgents/com.arjan.claude-usage-widget.plist
```
