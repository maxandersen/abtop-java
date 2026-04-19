package dev.abtop.model;

public record RateLimitInfo(
        String source,
        Double fiveHourPct,
        Long fiveHourResetsAt,
        Double sevenDayPct,
        Long sevenDayResetsAt,
        Long updatedAt) {

    public static RateLimitInfo empty(String source) {
        return new RateLimitInfo(source, null, null, null, null, null);
    }
}
