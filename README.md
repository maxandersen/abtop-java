# jabtop

**Like htop, but for your AI coding agents.**

See every Claude Code, Codex CLI, pi.dev, and opencode session at a glance — token usage, context window %, rate limits, child processes, open ports, and more.

Java port of [abtop](https://github.com/graykode/abtop) (Rust). Built with [Quarkus](https://quarkus.io) + [TamboUI](https://tamboui.dev).

## Install

### Native Binary (no JVM needed)

**macOS (Apple Silicon):**
```bash
curl -Lo abtop https://github.com/maxandersen/jabtop/releases/download/early-access/abtop-macos-aarch64
chmod +x abtop
```

**Linux (amd64):**
```bash
curl -Lo abtop https://github.com/maxandersen/jabtop/releases/download/early-access/abtop-linux-amd64
chmod +x abtop
```

### Uber JAR (Java 21+)

```bash
curl -Lo abtop.jar https://github.com/maxandersen/jabtop/releases/download/early-access/jabtop-0.0.0-SNAPSHOT.jar
java -jar abtop.jar
```

## Usage

```bash
abtop              # Launch TUI
abtop --once       # Print snapshot and exit
abtop --setup      # Install StatusLine hook for Claude rate limits
```

## Supported Agents

| Agent | Badge | Data Source |
|-------|-------|-------------|
| Claude Code | `*CC` | `~/.claude/sessions/` + JSONL transcripts |
| Codex CLI | `>CD` | `~/.codex/state_5.sqlite` or JSONL |
| pi.dev | `»PI` | `~/.pi/agent/sessions/` JSONL |
| opencode | `■OC` | `~/.local/share/opencode/opencode.db` |

## Keybindings

| Key | Action |
|-----|--------|
| `↑`/`↓` or `k`/`j` | Select session |
| `g` | Jump to session pane (tmux / zellij) |
| `x` | Kill selected session |
| `X` | Kill all orphan ports |
| `t` | Cycle theme |
| `r` | Force refresh |
| `q` | Quit |

## Building

```bash
./mvnw package -DskipTests          # uber-jar
java -jar target/jabtop-*-runner.jar

./mvnw package -Dnative -DskipTests # native image (GraalVM 21)
./target/jabtop-*-runner
```

## License

MIT
