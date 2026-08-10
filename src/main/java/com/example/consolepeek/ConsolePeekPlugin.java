package com.example.consolepeek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public final class ConsolePeekPlugin extends JavaPlugin {

    public static final String USE_PERMISSION = "consolepeek.use";
    public static final String ADMIN_PERMISSION = "consolepeek.admin";

    private static final List<String> CONSOLE_SUBS = List.of("login");
    private static final List<String> CONSOLEPEEK_SUBS = List.of("login", "reload");

    private static final Path LOGS_DIR = Path.of("logs");
    private static final Path LATEST_LOG = LOGS_DIR.resolve("latest.log");

    // Rotated archives look like 2026-07-18-3.log.gz (or .log if uncompressed).
    private static final Pattern ARCHIVE =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.log(?:\\.gz)?");
    // The [HH:mm:ss] timestamp at the start of a console line.
    private static final Pattern TIME =
            Pattern.compile("\\[(\\d{1,2}:\\d{2}:\\d{2})");
    // The player name sits immediately before the [/ip:port] token on a login line.
    private static final Pattern LOGIN_USER =
            Pattern.compile("([\\w.]{1,32})\\[/[^\\]]*] logged in with entity id");

    private record LoginEvent(String date, String time, String user) {}

    private record LogFile(Path path, String dateStr, boolean gz, LocalDate date, int index) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        register("console");
        register("consolepeek");
    }

    private void register(String name) {
        final PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        cmd.setExecutor(this);
        // Setting our own completer stops Bukkit's default from suggesting
        // online player names for these commands' arguments.
        cmd.setTabCompleter(this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("consolepeek")) {
            return handleAdmin(sender, args);
        }
        return handleConsole(sender, args);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        // Always return a (possibly empty) list, never null: returning null lets
        // Bukkit fall back to suggesting online player names, which we don't want.
        final boolean isPeek = command.getName().equalsIgnoreCase("consolepeek");
        if (args.length == 1) {
            return prefixMatches(isPeek ? CONSOLEPEEK_SUBS : CONSOLE_SUBS, args[0]);
        }
        return List.of();
    }

    private static List<String> prefixMatches(List<String> options, String typed) {
        final String p = typed.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    // ---- /consolepeek <sub> (ops only via consolepeek.admin) ---------------

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(Component.text(
                    "ConsolePeek config reloaded. ops-only is now "
                            + getConfig().getBoolean("ops-only", true) + ".", NamedTextColor.GREEN));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("login")) {
            return handleLogin(sender, args[1]);
        }
        sender.sendMessage(Component.text(
                "Usage: /consolepeek reload  |  /consolepeek login <count>", NamedTextColor.RED));
        return true;
    }

    // ---- /consolepeek login <count> ----------------------------------------

    private boolean handleLogin(CommandSender sender, String rawCount) {
        final int count = parseCount(sender, rawCount);
        if (count < 0) {
            return true;
        }

        final String marker = getConfig().getString("login-match", "logged in with entity id")
                .toLowerCase(Locale.ROOT);
        final int maxFiles = Math.max(1, getConfig().getInt("login-max-files", 60));

        final List<LoginEvent> logins;
        try {
            logins = collectRecentLogins(count, marker, maxFiles);
        } catch (IOException e) {
            sender.sendMessage(Component.text(
                    "Error reading the logs: " + e.getMessage(), NamedTextColor.RED));
            return true;
        }

        if (logins.isEmpty()) {
            sender.sendMessage(Component.text(
                    "No logins found in the logs I could read.", NamedTextColor.YELLOW));
            return true;
        }

        sender.sendMessage(Component.text(
                "---- last " + logins.size() + " login(s) ----", NamedTextColor.GRAY));
        for (LoginEvent e : logins) {
            sender.sendMessage(Component.text(e.date() + " " + e.time() + "  ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(e.user(), NamedTextColor.AQUA)));
        }
        return true;
    }

    /**
     * Walks log files newest-first (latest.log, then dated archives) parsing
     * login events, stopping once at least {@code count} have been gathered or
     * {@code maxFiles} files have been read, then returns the most recent
     * {@code count} in chronological order.
     */
    private List<LoginEvent> collectRecentLogins(int count, String marker, int maxFiles)
            throws IOException {
        final Deque<LoginEvent> collected = new ArrayDeque<>();
        final List<LogFile> files = discoverLogFilesNewestFirst();

        int scanned = 0;
        for (LogFile lf : files) {
            if (scanned >= maxFiles) {
                break;
            }
            scanned++;

            final List<LoginEvent> events = parseLogins(lf, marker); // chronological
            // Prepend this (older) file's block ahead of what we already have.
            for (int i = events.size() - 1; i >= 0; i--) {
                collected.addFirst(events.get(i));
            }
            if (collected.size() >= count) {
                break;
            }
        }

        final List<LoginEvent> all = new ArrayList<>(collected);
        final int from = Math.max(0, all.size() - count);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    private List<LogFile> discoverLogFilesNewestFirst() throws IOException {
        final List<LogFile> result = new ArrayList<>();
        if (Files.isReadable(LATEST_LOG)) {
            result.add(new LogFile(LATEST_LOG, LocalDate.now().toString(), false,
                    LocalDate.now(), Integer.MAX_VALUE));
        }
        if (!Files.isDirectory(LOGS_DIR)) {
            return result;
        }

        final List<LogFile> archives = new ArrayList<>();
        final List<Path> paths;
        try (var stream = Files.list(LOGS_DIR)) {
            paths = stream.toList();
        }
        for (Path p : paths) {
            final Matcher m = ARCHIVE.matcher(p.getFileName().toString());
            if (!m.matches()) {
                continue;
            }
            archives.add(new LogFile(p, m.group(1), p.getFileName().toString().endsWith(".gz"),
                    LocalDate.parse(m.group(1)), Integer.parseInt(m.group(2))));
        }
        archives.sort(Comparator.comparing((LogFile f) -> f.date())
                .thenComparingInt(LogFile::index).reversed());
        result.addAll(archives);
        return result;
    }

    private List<LoginEvent> parseLogins(LogFile lf, String marker) throws IOException {
        final List<LoginEvent> out = new ArrayList<>();
        try (BufferedReader reader = openReader(lf)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.toLowerCase(Locale.ROOT).contains(marker)) {
                    continue;
                }
                final Matcher um = LOGIN_USER.matcher(line);
                if (!um.find()) {
                    continue;
                }
                out.add(new LoginEvent(lf.dateStr(), extractTime(line), um.group(1)));
            }
        }
        return out;
    }

    private BufferedReader openReader(LogFile lf) throws IOException {
        InputStream in = Files.newInputStream(lf.path());
        if (lf.gz()) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static String extractTime(String line) {
        final Matcher tm = TIME.matcher(line);
        return tm.find() ? tm.group(1) : "??:??:??";
    }

    // ---- /console <lines> --------------------------------------------------

    private boolean handleConsole(CommandSender sender, String[] args) {
        // /console login <count> is an alias for /consolepeek login <count>.
        // It keeps the stricter admin gate, since login output is IP-derived.
        if (args.length >= 1 && args[0].equalsIgnoreCase("login")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage(Component.text(
                        "You don't have permission to view logins.", NamedTextColor.RED));
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage(Component.text(
                        "Usage: /console login <count>", NamedTextColor.RED));
                return true;
            }
            return handleLogin(sender, args[1]);
        }

        final boolean opsOnly = getConfig().getBoolean("ops-only", true);
        if (opsOnly && !sender.hasPermission(USE_PERMISSION)) {
            sender.sendMessage(Component.text(
                    "You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(Component.text(
                    "Usage: /console <lines>  |  /console login <count>", NamedTextColor.RED));
            return true;
        }
        final int count = parseCount(sender, args[0]);
        if (count < 0) {
            return true;
        }
        if (!Files.isReadable(LATEST_LOG)) {
            sender.sendMessage(Component.text(
                    "Couldn't find or read logs/latest.log.", NamedTextColor.RED));
            return true;
        }
        final Deque<String> tail;
        try {
            tail = tailMatching(count, line -> true);
        } catch (IOException e) {
            sender.sendMessage(Component.text(
                    "Error reading the log: " + e.getMessage(), NamedTextColor.RED));
            return true;
        }
        sender.sendMessage(Component.text(
                "---- last " + tail.size() + " console line(s) ----", NamedTextColor.GRAY));
        for (String line : tail) {
            sender.sendMessage(Component.text(line, NamedTextColor.WHITE));
        }
        return true;
    }

    // ---- shared helpers ----------------------------------------------------

    private int parseCount(CommandSender sender, String raw) {
        final int requested;
        try {
            requested = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text(
                    "\"" + raw + "\" is not a whole number.", NamedTextColor.RED));
            return -1;
        }
        if (requested < 1) {
            sender.sendMessage(Component.text("Ask for at least 1.", NamedTextColor.RED));
            return -1;
        }
        final int maxLines = Math.max(1, getConfig().getInt("max-lines", 100));
        if (requested > maxLines) {
            sender.sendMessage(Component.text(
                    "(capped at " + maxLines + ")", NamedTextColor.DARK_GRAY));
        }
        return Math.min(requested, maxLines);
    }

    private Deque<String> tailMatching(int count, Predicate<String> filter) throws IOException {
        final Deque<String> tail = new ArrayDeque<>(count + 1);
        if (!Files.isReadable(LATEST_LOG)) {
            return tail;
        }
        try (BufferedReader reader = Files.newBufferedReader(LATEST_LOG, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!filter.test(line)) {
                    continue;
                }
                tail.addLast(line);
                if (tail.size() > count) {
                    tail.pollFirst();
                }
            }
        }
        return tail;
    }
}
