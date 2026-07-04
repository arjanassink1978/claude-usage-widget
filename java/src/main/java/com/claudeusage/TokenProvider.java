package com.claudeusage;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Reads the Claude Code OAuth access token from the OS-native credential store. */
public final class TokenProvider {
    private static final String SERVICE = "Claude Code-credentials";

    private TokenProvider() {}

    public static Supplier<String> forCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return TokenProvider::readMac;
        if (os.contains("win")) return TokenProvider::readWindows;
        return TokenProvider::readLinuxFallback;
    }

    private static String extractAccessToken(String jsonPayload) {
        try {
            JSONObject obj = new JSONObject(jsonPayload);
            JSONObject oauth = obj.optJSONObject("claudeAiOauth");
            if (oauth == null) return null;
            String token = oauth.optString("accessToken", null);
            return (token == null || token.isBlank()) ? null : token;
        } catch (Exception e) {
            return null;
        }
    }

    private static String runProcess(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String output = readAll(proc.getInputStream());
            boolean finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished || proc.exitValue() != 0) return null;
            return output;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(java.io.InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** macOS: shell out to `security`, same tool Claude Code's own credential ACL already trusts. */
    private static String readMac() {
        String raw = runProcess("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-w");
        return raw == null ? null : extractAccessToken(raw.trim());
    }

    /**
     * Windows: Claude Code is expected to store this in Windows Credential Manager under the
     * same generic-credential target name it uses on macOS Keychain (cross-platform credential
     * libraries typically keep the service name constant across OS backends). Reads it via a
     * small embedded PowerShell script calling advapi32's CredRead — unverified on real Windows,
     * see README.
     */
    private static String readWindows() {
        try {
            Path script = extractResourceToTemp("/scripts/read_credential.ps1", "read_credential.ps1");
            String raw = runProcess("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString(), SERVICE);
            return raw == null ? null : extractAccessToken(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Linux: no confirmed storage location; try a plaintext fallback file some CLIs use. */
    private static String readLinuxFallback() {
        Path fallback = Path.of(System.getProperty("user.home"), ".claude", ".credentials.json");
        if (!Files.isRegularFile(fallback)) return null;
        try {
            return extractAccessToken(Files.readString(fallback));
        } catch (Exception e) {
            return null;
        }
    }

    private static Path extractResourceToTemp(String resourcePath, String fileName) throws Exception {
        File tmp = File.createTempFile("claude-usage-", "-" + fileName);
        tmp.deleteOnExit();
        try (var in = TokenProvider.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IllegalStateException("missing resource " + resourcePath);
            Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tmp.toPath();
    }
}
