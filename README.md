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
| `ops-only` | `true` | Require the `consolepeek.use` permission for `/console` (ops have it by default). |
| `max-lines` | `100` | Cap on lines printed by `/console` and logins returned by `/consolepeek login`. |
| `login-match` | `logged in with entity id` | The log marker that counts as a successful login. |
| `login-max-files` | `60` | How many log files (newest first) `/consolepeek login` may read through. |

Apply changes with `/consolepeek reload` — no restart, no recompile.

## Commands

| Command | Who | What |
| --- | --- | --- |
| `/console <lines>` | everyone or ops (per `ops-only`) | Last N raw console lines. |
| `/consolepeek login <count>` | ops (`consolepeek.admin`) | Last N logins as `date time  username`. |
| `/console login <count>` | ops (`consolepeek.admin`) | Alias for `/consolepeek login`. |
| `/consolepeek reload` | ops (`consolepeek.admin`) | Reload `config.yml`. |

### /consolepeek login

Returns the most recent N logins, cleaned to just timestamp and username, e.g.:

```
---- last 3 login(s) ----
2026-07-17 11:00:00  Delta
2026-07-18 12:00:00  Echo
2026-07-18 13:00:00  Foxtrot
```

It searches backward through the logs — `latest.log` first, then the dated
`.log.gz` archives newest-first — until it has N logins or runs out of files
(bounded by `login-max-files`). Dates for archived lines come from the archive
filename; lines in `latest.log` are dated to the current day.

`/console login <count>` is a convenience alias for the same thing, and stays
op-gated even though `/console` itself may be open to everyone.

Notes:
- Only successful logins are listed. Failed/aborted attempts usually disconnect
  before authenticating and carry no username, so they can't be shown as
  `username + timestamp`; they're skipped.
- It's op-only (`consolepeek.admin`) because these lines are derived from entries
  that include player IPs. To loosen it, give the `consolepeek` command its own
  permission in `plugin.yml`.
- `latest.log` uses the current date. If the server ran across midnight without
  rotating, times before midnight will be labelled with today's date.

## Tab completion

Tab-completing `/console` or `/consolepeek` suggests only the real subcommands (`login`, `reload`) and nothing for the numeric/count argument. It deliberately does **not** fall back to suggesting online player names, which is Bukkit's default behaviour for command arguments.

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
