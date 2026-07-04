# claude-usage-widget

Menu bar / system tray widget showing Claude usage — Claude Code local token counts plus your account's 5-hour session and weekly limits, styled after [openusage](https://github.com/robinebers/openusage).

## What it shows

- Pinned indicator: today's Claude Code token count, 5-hour session %, weekly %
- Popover: session/weekly progress bars with reset countdowns, today's token total, active conversation's context-window usage

## How it works

- Local token counts come from Claude Code's session logs in `~/.claude/projects/**/*.jsonl`
- Session/weekly usage comes from `GET https://api.anthropic.com/api/oauth/usage`, using the OAuth token Claude Code already stores in the OS-native credential store — the same token the `claude` CLI uses. Read-only: this app never sends messages or modifies your account.

## Download

Grab the latest build from [Releases](../../releases):

- **macOS**: `ClaudeUsage.dmg` — unsigned build, so on first launch right-click the app → **Open** to bypass Gatekeeper's "unidentified developer" warning.
- **Windows**: `ClaudeUsage-Windows.zip` — extract and run `ClaudeUsage.exe`. **Experimental** — see caveat below.

### Windows caveat

Claude Code stores its OAuth token in the macOS Keychain on macOS, confirmed working here. On Windows it's expected to land in Windows Credential Manager (same cross-platform pattern), but this hasn't been verified on a real Windows machine — the Windows build is CI-compiled only. If the tray icon shows "Account usage unavailable", the token likely lives somewhere this app isn't looking yet. Open an issue with details and it can be fixed.

## Requirements

- [Claude Code](https://docs.anthropic.com/en/docs/claude-code) installed and logged in (`claude` then `/login`)

## Run from source

**macOS:**
```bash
pip3 install -r mac/requirements.txt
python3 mac/claude_usage_mac.py
```

**Windows:**
```bash
pip install -r windows/requirements.txt
python windows/claude_usage_win.py
```

## Build installers yourself

**macOS DMG:**
```bash
cd mac && ./build_dmg.sh
```

**Windows EXE:**
```bash
cd windows
pyinstaller --name ClaudeUsage --windowed --add-data "../core.py;." --paths .. claude_usage_win.py
```

## Run at login (macOS)

Copy `mac/com.example.claude-usage-widget.plist` (adjust the paths inside for your machine) into `~/Library/LaunchAgents/`, then:

```bash
launchctl load ~/Library/LaunchAgents/com.example.claude-usage-widget.plist
```

## Releases

Push a tag like `v0.1.0` — GitHub Actions builds the DMG and Windows EXE and attaches them to a release.
