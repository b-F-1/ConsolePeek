package com.example.consolepeek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

public final class ConsolePeekPlugin extends JavaPlugin {

    public static final String USE_PERMISSION = "consolepeek.use";

    // Paper writes the console to logs/latest.log relative to the server's working dir.
    private static final Path LOG_FILE = Path.of("logs", "latest.log");

    @Override
    public void onEnable() {
        // Writes config.yml into the plugin folder on first run; won't overwrite later.
        saveDefaultConfig();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (command.getName().equalsIgnoreCase("consolepeek")) {
            return handleAdmin(sender, args);
        }
        return handleConsole(sender, args);
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(Component.text("Usage: /consolepeek reload", NamedTextColor.RED));
            return true;
        }
        reloadConfig();
        sender.sendMessage(Component.text(
                "ConsolePeek config reloaded. ops-only is now "
                        + getConfig().getBoolean("ops-only", true) + ".", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleConsole(CommandSender sender, String[] args) {

        // The switch: when ops-only is true, require the permission (default: op).
        // When false, anyone can run it.
        final boolean opsOnly = getConfig().getBoolean("ops-only", true);
        if (opsOnly && !sender.hasPermission(USE_PERMISSION)) {
            sender.sendMessage(Component.text(
                    "You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(Component.text("Usage: /console <lines>", NamedTextColor.RED));
            return true;
        }

        final int requested;
        try {
            requested = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text(
                    "\"" + args[0] + "\" is not a whole number.", NamedTextColor.RED));
            return true;
        }

        if (requested < 1) {
            sender.sendMessage(Component.text("Ask for at least 1 line.", NamedTextColor.RED));
            return true;
        }

        final int maxLines = Math.max(1, getConfig().getInt("max-lines", 100));
        final int count = Math.min(requested, maxLines);

        if (!Files.isReadable(LOG_FILE)) {
            sender.sendMessage(Component.text(
                    "Couldn't find or read logs/latest.log.", NamedTextColor.RED));
            return true;
        }

        // Read the whole file but keep only the last `count` lines in memory.
        final Deque<String> tail = new ArrayDeque<>(count + 1);
        try (BufferedReader reader = Files.newBufferedReader(LOG_FILE, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.addLast(line);
                if (tail.size() > count) {
                    tail.pollFirst();
                }
            }
        } catch (IOException e) {
            sender.sendMessage(Component.text(
                    "Error reading the log: " + e.getMessage(), NamedTextColor.RED));
            return true;
        }

        if (requested > maxLines) {
            sender.sendMessage(Component.text(
                    "(capped at " + maxLines + " lines)", NamedTextColor.DARK_GRAY));
        }
        sender.sendMessage(Component.text(
                "---- last " + tail.size() + " console line(s) ----", NamedTextColor.GRAY));
        for (String line : tail) {
            sender.sendMessage(Component.text(line, NamedTextColor.WHITE));
        }
        return true;
    }
}
