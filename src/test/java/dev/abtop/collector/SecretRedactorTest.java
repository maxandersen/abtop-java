package dev.abtop.collector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SecretRedactor — covers all known token patterns.
 */
class SecretRedactorTest {

    @Test
    void redactsAnthropicKeys() {
        assertEquals("[REDACTED]", SecretRedactor.redact("sk-ant-abc123def"));
        assertEquals("key=[REDACTED]", SecretRedactor.redact("key=sk-ant-abc123def"));
    }

    @Test
    void redactsGithubTokens() {
        assertEquals("[REDACTED]", SecretRedactor.redact("ghp_xxxx1234567890abcdefghij"));
        assertEquals("[REDACTED]", SecretRedactor.redact("github_pat_xxxx"));
    }

    @Test
    void redactsStripeKeys() {
        assertEquals("[REDACTED]", SecretRedactor.redact("sk_live_51abc123"));
        assertEquals("[REDACTED]", SecretRedactor.redact("rk_test_xyz"));
    }

    @Test
    void redactsSlackTokens() {
        assertEquals("[REDACTED]", SecretRedactor.redact("xoxb-123-456-abc"));
    }

    @Test
    void redactsAwsKeys() {
        assertEquals("[REDACTED]", SecretRedactor.redact("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void redactsBearerToken() {
        assertEquals("[REDACTED]", SecretRedactor.redact("Bearer eyJhbGciOiJIUzI1NiJ9.abc"));
    }

    @Test
    void preservesNonSecretStrings() {
        assertEquals("hello world", SecretRedactor.redact("hello world"));
        assertEquals("cargo build --release", SecretRedactor.redact("cargo build --release"));
    }

    @Test
    void handlesNull() {
        assertNull(SecretRedactor.redact(null));
    }

    @Test
    void redactsMultipleSecrets() {
        String input = "keys: sk-ant-abc123 and ghp_xyz456";
        String result = SecretRedactor.redact(input);
        assertEquals("keys: [REDACTED] and [REDACTED]", result);
    }

    @Test
    void redactsInMiddleOfCommand() {
        // "Bearer abc123token'" is one whitespace-delimited token, so the whole thing
        // from "Bearer" to the next whitespace gets redacted
        String cmd = "curl -H 'Authorization: Bearer abc123token' https://api.example.com";
        String result = SecretRedactor.redact(cmd);
        assertTrue(result.contains("[REDACTED]"));
        // The token after Bearer should be consumed into the redaction
        assertFalse(result.contains("abc123token"),
                "Token after Bearer should be redacted, got: " + result);
    }
}
