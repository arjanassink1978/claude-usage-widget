package com.claudeusage;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Platform-agnostic Claude usage logic: local token scanning, account usage API, popover HTML. */
public class UsageCore {
    public static final String USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
    public static final long CONTEXT_WINDOW_LIMIT = 200_000;
    public static final int REFRESH_SECONDS = 300;

    private static final Path PROJECTS_DIR = Paths.get(System.getProperty("user.home"), ".claude", "projects");

    public record Totals(long input, long output, long cacheRead, long cacheCreation) {
        public long sum() { return input + output + cacheRead + cacheCreation; }
        public static Totals zero() { return new Totals(0, 0, 0, 0); }
    }

    public record LimitBucket(double utilization, String resetsAt) {}

    public record AccountUsage(LimitBucket fiveHour, LimitBucket sevenDay) {}

    public record ActiveSession(long contextTokens, long limit) {}

    public static AccountUsage fetchAccountUsage(Supplier<String> tokenSupplier) {
        String token = tokenSupplier.get();
        if (token == null || token.isBlank()) return null;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(USAGE_URL))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .header("anthropic-beta", "oauth-2025-04-20")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JSONObject data = new JSONObject(resp.body());
            LimitBucket fh = parseBucket(data.optJSONObject("five_hour"));
            LimitBucket sd = parseBucket(data.optJSONObject("seven_day"));
            return new AccountUsage(fh, sd);
        } catch (Exception e) {
            return null;
        }
    }

    private static LimitBucket parseBucket(JSONObject obj) {
        if (obj == null) return null;
        double util = obj.optDouble("utilization", 0);
        String resetsAt = obj.optString("resets_at", null);
        return new LimitBucket(util, resetsAt);
    }

    public static String fmtRelative(String isoTs) {
        if (isoTs == null) return "?";
        try {
            OffsetDateTime dt = OffsetDateTime.parse(isoTs);
            Duration delta = Duration.between(Instant.now(), dt.toInstant());
            long totalMinutes = Math.max(0, delta.toMinutes());
            long days = totalMinutes / 1440;
            long hours = (totalMinutes % 1440) / 60;
            long minutes = totalMinutes % 60;
            if (days > 0) return "in " + days + "d " + hours + "h";
            if (hours > 0) return minutes > 0 ? "in " + hours + "h " + minutes + "m" : "in " + hours + "h";
            return "in " + minutes + "m";
        } catch (Exception e) {
            return "?";
        }
    }

    public static String fmtTokens(long n) {
        if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    public static Totals scanUsage() {
        long today = Instant.now().atZone(ZoneId.of("UTC")).toLocalDate().toEpochDay();
        long input = 0, output = 0, cacheRead = 0, cacheCreation = 0;

        for (Path jsonl : listSessionFiles()) {
            try (BufferedReader br = Files.newBufferedReader(jsonl)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.contains("\"usage\"")) continue;
                    JSONObject rec;
                    try {
                        rec = new JSONObject(line);
                    } catch (Exception e) {
                        continue;
                    }
                    String ts = rec.optString("timestamp", null);
                    if (ts == null) continue;
                    long recDay;
                    try {
                        recDay = OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.of("UTC")).toLocalDate().toEpochDay();
                    } catch (Exception e) {
                        continue;
                    }
                    if (recDay != today) continue;
                    JSONObject usage = rec.optJSONObject("message") != null
                            ? rec.getJSONObject("message").optJSONObject("usage") : null;
                    if (usage == null) continue;
                    input += usage.optLong("input_tokens", 0);
                    output += usage.optLong("output_tokens", 0);
                    cacheRead += usage.optLong("cache_read_input_tokens", 0);
                    cacheCreation += usage.optLong("cache_creation_input_tokens", 0);
                }
            } catch (IOException ignored) {
            }
        }
        return new Totals(input, output, cacheRead, cacheCreation);
    }

    public static ActiveSession scanActiveSession() {
        List<Path> files = listSessionFiles();
        if (files.isEmpty()) return null;
        Path latest = files.get(0);
        long latestMtime = -1;
        for (Path p : files) {
            try {
                long mtime = Files.getLastModifiedTime(p).toMillis();
                if (mtime > latestMtime) {
                    latestMtime = mtime;
                    latest = p;
                }
            } catch (IOException ignored) {
            }
        }

        JSONObject lastUsage = null;
        try (BufferedReader br = Files.newBufferedReader(latest)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.contains("\"usage\"")) continue;
                try {
                    JSONObject rec = new JSONObject(line);
                    JSONObject usage = rec.optJSONObject("message") != null
                            ? rec.getJSONObject("message").optJSONObject("usage") : null;
                    if (usage != null) lastUsage = usage;
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (lastUsage == null) return null;

        long ctx = lastUsage.optLong("input_tokens", 0)
                + lastUsage.optLong("cache_read_input_tokens", 0)
                + lastUsage.optLong("cache_creation_input_tokens", 0);
        return new ActiveSession(ctx, CONTEXT_WINDOW_LIMIT);
    }

    private static List<Path> listSessionFiles() {
        List<Path> results = new ArrayList<>();
        if (!Files.isDirectory(PROJECTS_DIR)) return results;
        try (DirectoryStream<Path> projects = Files.newDirectoryStream(PROJECTS_DIR)) {
            for (Path project : projects) {
                if (!Files.isDirectory(project)) continue;
                try (DirectoryStream<Path> sessions = Files.newDirectoryStream(project, "*.jsonl")) {
                    for (Path session : sessions) results.add(session);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return results;
    }

    public static String barHtml(double pct) {
        double clamped = Math.max(0, Math.min(100, pct));
        return "<div class=\"track\"><div class=\"fill\" style=\"width:" + clamped + "%\"></div></div>";
    }

    public static String renderHtml(AccountUsage account, Totals totals, ActiveSession active, boolean stale, String bridgeScript) {
        StringBuilder sections = new StringBuilder();
        if (account != null && account.fiveHour() != null) {
            LimitBucket fh = account.fiveHour();
            double left = 100 - fh.utilization();
            sections.append(String.format("""
                <div class="metric">
                  <div class="metric-label">Session</div>
                  %s
                  <div class="metric-row"><span>%.0f%% left</span><span class="muted">Resets %s</span></div>
                </div>""", barHtml(fh.utilization()), left, fmtRelative(fh.resetsAt())));
        }
        if (account != null && account.sevenDay() != null) {
            LimitBucket sd = account.sevenDay();
            double left = 100 - sd.utilization();
            sections.append(String.format("""
                <div class="metric">
                  <div class="metric-label">Weekly</div>
                  %s
                  <div class="metric-row"><span>%.0f%% left</span><span class="muted">Resets %s</span></div>
                </div>""", barHtml(sd.utilization()), left, fmtRelative(sd.resetsAt())));
        }
        if (sections.isEmpty()) {
            sections.append("<div class=\"metric muted\">Account usage unavailable — is Claude Code logged in?</div>");
        }

        long totalTokens = totals.sum();
        StringBuilder extraRows = new StringBuilder(String.format(
                "<div class=\"row\"><span>Today</span><span>%s tokens</span></div>", fmtTokens(totalTokens)));
        if (active != null) {
            double pct = (double) active.contextTokens() / active.limit() * 100;
            extraRows.append(String.format(
                    "<div class=\"row\"><span>Context window</span><span>%.0f%%</span></div>", pct));
        }

        String now = DateTimeFormatter.ofPattern("HH:mm:ss").format(java.time.LocalTime.now());
        String updatedLabel = "Updated " + now + (stale ? " (stale — API rate limited)" : "");

        return String.format("""
<!doctype html><html><head><meta charset="utf-8"><style>
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 16px; background: #000; color: #fff;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "SF Pro Text", sans-serif;
    -webkit-user-select: none; user-select: none;
  }
  .header { display: flex; align-items: baseline; justify-content: space-between; padding: 4px 4px 12px; }
  .header .title { font-size: 17px; font-weight: 700; }
  .header .plan { font-size: 13px; color: #9a9a9e; margin-left: 8px; }
  .header .glyph { font-size: 18px; color: #DA7857; }
  .card { background: #2c2c2e; border-radius: 16px; padding: 18px; }
  .metric { margin-bottom: 18px; }
  .metric-label { font-size: 15px; font-weight: 700; margin-bottom: 10px; }
  .track { height: 8px; border-radius: 4px; background: #545456; overflow: hidden; }
  .fill { height: 100%%; background: #0a84ff; border-radius: 4px; }
  .metric-row { display: flex; justify-content: space-between; font-size: 13px; margin-top: 8px; color: #c7c7c9; }
  .muted { color: #8e8e93; }
  .row { display: flex; justify-content: space-between; font-size: 14px; padding: 7px 0; }
  .footer { display: flex; justify-content: space-between; align-items: center; margin-top: 14px; padding: 0 4px; font-size: 12px; color: #8e8e93; }
  .footer a { color: #0a84ff; text-decoration: none; cursor: pointer; }
</style>
<script>%s</script>
</head>
<body>
  <div class="header">
    <span><span class="title">Claude</span><span class="plan">Code CLI</span></span>
    <span class="glyph">✻</span>
  </div>
  <div class="card">
    %s
    %s
  </div>
  <div class="footer">
    <span>%s</span>
    <span><a onclick="bridge('refresh')">Refresh</a> &nbsp;·&nbsp; <a onclick="bridge('quit')">Quit</a></span>
  </div>
</body></html>
""", bridgeScript, sections, extraRows, updatedLabel);
    }
}
