# ConsolePeek

A tiny Paper plugin that adds `/console <lines>` — it reads the last N lines of
`logs/latest.log` and prints them into chat for whoever ran the command.

## Build

You need JDK matching your server's Java version (Java 25 for MC 26.1; Java 21
for 1.21.x) and Maven.

```bash
mvn clean package
```

The finished jar lands at `target/ConsolePeek.jar`.

## Install

1. Drop `ConsolePeek.jar` into your server's `plugins/` folder.
2. Restart the server (or run `/reload confirm`, though a full restart is cleaner).
3. In game or console, run `/console 20` to see the last 20 lines.

## Adjusting versions

- `pom.xml` -> `paper-api` version: the format depends on your server version.
  - 26.x (new scheme): use a build range, `[26.2.build,)` for 26.2,
    `[26.1.build,)` for 26.1.x — or pin an exact build like `26.2.build.60-beta`.
  - 1.21.11 or older: use the old `{VERSION}-R0.1-SNAPSHOT` format, e.g.
    `1.21.4-R0.1-SNAPSHOT`.
- `pom.xml` -> `maven.compiler.release`: match your server's Java version.
- `plugin.yml` -> `api-version`: if the server refuses to load the plugin over
  this value, change it to match your version.

## Notes / gotchas

- **Ops only by default.** Set `ops-only: false` in `config.yml` to open it to
  everyone. Be aware the console logs player IP addresses, stack traces, and
  admin actions, so opening it up exposes all of that to any player.

## Config

`plugins/ConsolePeek/config.yml`:

| Key | Default | Meaning |
| --- | --- | --- |
| `ops-only` | `true` | Require the `consolepeek.use` permission (ops have it by default). |
| `max-lines` | `100` | Hard cap on lines printed per command. |

Apply changes with `/consolepeek reload` — no restart, no recompile.

## Permissions

| Node | Default | Grants |
| --- | --- | --- |
| `consolepeek.use` | op | Use of `/console <lines>`. |
| `consolepeek.admin` | op | Use of `/consolepeek reload`. |

With a permissions plugin (LuckPerms etc.) you can grant `consolepeek.use` to
specific players or ranks while leaving `ops-only: true` — e.g.
`/lp group moderator permission set consolepeek.use true`.

- **Reading cost:** the command reads the whole log file each time it runs (only
  keeping the last N lines in memory). On a server with a very large latest.log
  this is a little wasteful, but fine for occasional use. If you want it to be
  cheap and real-time, the alternative is attaching a Log4j2 appender that keeps
  a rolling in-memory buffer of the last N lines — happy to provide that version.

- **Color codes:** by default Paper writes plain text to latest.log, so lines
  come through clean. If you ever see stray `§` codes from logged chat, they can
  be stripped before sending.
