package dev.abtop.model;

public record ChildProcess(int pid, String command, long memKb, Integer port) {
}
