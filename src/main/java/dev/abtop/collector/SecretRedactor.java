package dev.abtop.collector;

/**
 * Redact common secret patterns to avoid displaying credentials in the TUI.
 * Best-effort: covers well-known prefixed tokens, not arbitrary high-entropy strings.
 */
public final class SecretRedactor {

    private static final String[] PATTERNS = {
            "sk-ant-", "sk-proj-", "sk-or-",
            "sk_live_", "sk_test_", "rk_live_", "rk_test_",
            "ghp_", "gho_", "ghs_", "ghr_", "ghu_", "github_pat_",
            "glpat-",
            "xoxb-", "xoxp-", "xoxa-", "xoxs-",
            "AKIA", "ASIA",
            "Bearer ",
    };

    private SecretRedactor() {}

    public static String redact(String s) {
        if (s == null) return null;
        String result = s;
        for (String pat : PATTERNS) {
            while (true) {
                int pos = result.indexOf(pat);
                if (pos < 0) break;
                // Skip past the pattern itself
                int end = pos + pat.length();
                // Then consume the token (non-whitespace) that follows
                while (end < result.length() && !Character.isWhitespace(result.charAt(end))) {
                    end++;
                }
                result = result.substring(0, pos) + "[REDACTED]" + result.substring(end);
            }
        }
        return result;
    }
}
