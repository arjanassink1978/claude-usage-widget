# claude-usage-widget

System tray widget showing Claude usage — Claude Code local token counts plus your account's 5-hour session and weekly limits, styled after [openusage](https://github.com/robinebers/openusage).

One Java codebase, packaged as a native installer for macOS and Windows.

## What it shows

- Tray tooltip: today's Claude Code token count, 5-hour session %, weekly %
- Popover: session/weekly progress bars with reset countdowns, today's token total, active conversation's context-window usage

## How it works

- Local token counts come from Claude Code's session logs in `~/.claude/projects/**/*.jsonl`
- Session/weekly usage comes from `GET https://api.anthropic.com/api/oauth/usage`, using the OAuth token Claude Code already stores in the OS-native credential store — the same token the `claude` CLI uses. Read-only: this app never sends messages or modifies your account.
- macOS: reads the token via `security find-generic-password`, same as the `claude` CLI's own Keychain entry.
- Windows: reads the token from Windows Credential Manager via an embedded PowerShell script (`CredRead`), assuming Claude Code stores it under the same generic-credential target name as macOS. **Unverified on a real Windows machine** — see caveat below.

### Windows caveat

The Windows credential lookup is a best-effort port of the confirmed-working macOS approach; it hasn't been tested on real Windows. If the popover shows "Account usage unavailable", the token likely lives somewhere this app isn't looking yet — open an issue with details.

## Download

Grab the latest build from [Releases](../../releases):

- **macOS**: `Claude Usage-*.dmg` — unsigned build, so on first launch right-click the app → **Open** to bypass Gatekeeper's "unidentified developer" warning.
- **Windows**: `Claude Usage-*.exe` installer.

## Requirements

- [Claude Code](https://docs.anthropic.com/en/docs/claude-code) installed and logged in (`claude` then `/login`)
- Java 21+ only if running from source or building yourself (the packaged installers bundle their own runtime)

## Run from source

```bash
cd java
mvn -q -DskipTests package
java -jar target/claude-usage-widget-*.jar
```

(the shaded jar excludes `original-*.jar`)

## Build an installer yourself

```bash
cd java
./build_installer.sh
```

Produces a `.dmg` on macOS or `.exe` on Windows (via `jpackage`; Windows needs the [WiX Toolset](https://wixtoolset.org/) installed).

## Releases

Push a tag like `v0.1.0` — GitHub Actions builds installers for both platforms and attaches them to a release.

## Project layout

```
java/
  pom.xml
  src/main/java/com/claudeusage/
    UsageCore.java     — token scanning, usage API call, popover HTML (platform-agnostic)
    TokenProvider.java — OS-specific credential lookup
    TrayApp.java        — AWT SystemTray + JavaFX WebView popup
  src/main/resources/scripts/read_credential.ps1 — Windows Credential Manager reader
```
