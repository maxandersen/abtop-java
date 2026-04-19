package dev.abtop.collector;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared Jackson ObjectMapper singleton.
 * ObjectMapper is thread-safe and expensive to construct.
 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private Json() {}
}
