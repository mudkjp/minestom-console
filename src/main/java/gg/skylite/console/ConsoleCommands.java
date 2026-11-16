package gg.skylite.console;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.CommandResult;
import org.jspecify.annotations.NullMarked;

import java.util.Arrays;
import java.util.Comparator;

@NullMarked
final class ConsoleCommands {
    private static final int SUGGESTION_LIMIT = 3;

    private ConsoleCommands() {
    }

    static void execute(CommandSender sender, String line) {
        var commandManager = MinecraftServer.getCommandManager();
        var result = commandManager.execute(sender, line);

        if (result.getType() != CommandResult.Type.UNKNOWN) {
            return;
        }

        var name = line.split(" ", 2)[0].toLowerCase();
        sender.sendMessage(Component.text("Unknown command: " + name, NamedTextColor.RED));

        var tolerance = Math.max(1, name.length() / 3);
        var closest = commandManager.getCommands().stream()
                .flatMap(command -> Arrays.stream(command.getNames()))
                .filter(candidate -> distance(name, candidate.toLowerCase()) <= tolerance)
                .sorted(Comparator.comparingInt(candidate -> distance(name, candidate.toLowerCase())))
                .limit(SUGGESTION_LIMIT)
                .toList();

        if (!closest.isEmpty()) {
            sender.sendMessage(Component.text("Did you mean: " + String.join(", ", closest), NamedTextColor.GRAY));
        }
    }

    private static int distance(String from, String to) {
        var previous = new int[to.length() + 1];
        var current = new int[to.length() + 1];

        for (var column = 0; column <= to.length(); column++) {
            previous[column] = column;
        }

        for (var row = 1; row <= from.length(); row++) {
            current[0] = row;

            for (var column = 1; column <= to.length(); column++) {
                var substitution = from.charAt(row - 1) == to.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + substitution);
            }

            var swap = previous;
            previous = current;
            current = swap;
        }

        return previous[to.length()];
    }
}
