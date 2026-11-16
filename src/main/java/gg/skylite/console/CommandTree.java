package gg.skylite.console;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.arguments.ArgumentBoolean;
import net.minestom.server.command.builder.arguments.ArgumentEnum;
import net.minestom.server.command.builder.arguments.ArgumentLiteral;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import org.jline.reader.Candidate;
import org.jline.utils.AttributedStyle;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@NullMarked
final class CommandTree {
    static final AttributedStyle UNPARSED_STYLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);

    private static final String COMMAND_GROUP = "commands";
    private static final String SUBCOMMAND_GROUP = "subcommands";
    private static final String ARGUMENT_GROUP = "arguments";
    private static final String OPTIONAL_OPEN = "[";
    private static final String OPTIONAL_CLOSE = "]";
    private static final String REQUIRED_OPEN = "(";
    private static final String REQUIRED_CLOSE = ")";
    private static final String OR = "|";
    private static final String SEPARATOR = " ";

    private CommandTree() {
    }

    private static List<Token> tokenize(String buffer) {
        var tokens = new ArrayList<Token>();
        var index = 0;

        while (index < buffer.length()) {
            while (index < buffer.length() && buffer.charAt(index) == ' ') {
                index++;
            }

            if (index >= buffer.length()) {
                break;
            }

            var start = index;
            var quote = buffer.charAt(index);

            if (quote == '"' || quote == '\'') {
                index++;

                while (index < buffer.length() && buffer.charAt(index) != quote) {
                    index += buffer.charAt(index) == '\\' ? 2 : 1;
                }

                index = Math.min(index + 1, buffer.length());
            } else {
                while (index < buffer.length() && buffer.charAt(index) != ' ') {
                    index++;
                }
            }

            tokens.add(new Token(start, index, buffer.substring(start, index)));
        }

        return tokens;
    }

    private static String usageText(Argument<?> argument) {
        return argument instanceof ArgumentLiteral
                ? argument.getId()
                : "<" + argument.getId() + ">";
    }

    private static boolean parses(Argument<?> argument, CommandSender sender, String input) {
        try {
            argument.parse(sender, input);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasArguments(Command command) {
        return !command.getSubcommands().isEmpty()
                || command.getSyntaxes().stream().anyMatch(syntax -> syntax.getArguments().length > 0);
    }

    private static boolean isExecutable(Command command) {
        return command.getDefaultExecutor() != null
                || command.getSyntaxes().stream().anyMatch(syntax -> syntax.getArguments().length == 0);
    }

    private static String nodeKey(Argument<?> argument) {
        return argument instanceof ArgumentLiteral
                ? argument.getId()
                : argument.getClass().getName() + '\0' + argument.getId();
    }

    private static Node build(Command command) {
        var root = new Node(command.getName(), List.of(command.getNames()), null);
        root.executable = isExecutable(command);

        for (var subcommand : command.getSubcommands()) {
            root.children.putIfAbsent(subcommand.getName(), build(subcommand));
        }

        for (var syntax : command.getSyntaxes()) {
            var current = root;

            for (var argument : syntax.getArguments()) {
                var key = nodeKey(argument);
                var next = current.children.get(key);

                if (next == null) {
                    next = new Node(usageText(argument), List.of(argument.getId()), argument);
                    current.children.put(key, next);
                }

                current = next;
            }

            current.executable = true;
        }

        return root;
    }

    private static @Nullable List<String> choices(Argument<?> argument) {
        return switch (argument) {
            case ArgumentBoolean ignored -> List.of("true", "false");
            case ArgumentEnum<?> enumeration -> enumeration.entries();
            case ArgumentWord word when word.hasRestrictions() -> Arrays.asList(word.getRestrictions());
            default -> null;
        };
    }

    private static String displayText(Node node) {
        if (node.argument == null || !node.children.isEmpty()) {
            return node.usageText;
        }

        var choices = choices(node.argument);
        return choices == null
                ? node.usageText
                : REQUIRED_OPEN + String.join(OR, choices) + REQUIRED_CLOSE;
    }

    private static String smartUsage(Node node, boolean optional, boolean deep) {
        var text = displayText(node);
        var usage = optional ? OPTIONAL_OPEN + text + OPTIONAL_CLOSE : text;

        if (deep) {
            return usage;
        }

        var children = childUsage(node);
        return children == null ? usage : usage + SEPARATOR + children;
    }

    private static @Nullable String childUsage(Node node) {
        var children = node.children.values();

        if (children.isEmpty()) {
            return null;
        }

        var optional = node.executable;

        if (children.size() == 1) {
            return smartUsage(children.iterator().next(), optional, optional);
        }

        var usages = children.stream()
                .map(child -> smartUsage(child, optional, true))
                .distinct()
                .toList();

        if (usages.size() == 1) {
            var usage = usages.getFirst();
            return optional ? OPTIONAL_OPEN + usage + OPTIONAL_CLOSE : usage;
        }

        var alternatives = String.join(OR, children.stream().map(child -> child.usageText).toList());
        var open = optional ? OPTIONAL_OPEN : REQUIRED_OPEN;
        var close = optional ? OPTIONAL_CLOSE : REQUIRED_CLOSE;
        return open + alternatives + close;
    }

    private static Match descend(Context context, Node node, int index) {
        var best = new Match(index, null);

        if (index == context.cursorToken) {
            best = new Match(index, node);

            for (var child : node.children.values()) {
                context.suggest(child);
            }
        }

        if (index < context.tokens.size()) {
            for (var child : node.children.values()) {
                var end = child.matchLength(context, index);

                if (end < 0) {
                    continue;
                }

                var match = descend(context, child, end);

                if (match.consumed() > best.consumed()) {
                    var position = match.position() == null ? best.position() : match.position();
                    best = new Match(match.consumed(), position);
                }
            }
        }

        return best;
    }

    static Analysis analyze(CommandManager commandManager, CommandSender sender, String buffer, int cursor) {
        var tokens = tokenize(buffer);
        var cursorToken = tokens.size();
        var word = "";

        for (var index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);

            if (cursor >= token.start() && cursor <= token.end()) {
                cursorToken = index;
                word = buffer.substring(token.start(), cursor);
                break;
            }
        }

        var context = new Context(sender, tokens, buffer, cursorToken);
        var unparsedStart = -1;
        String usage = null;

        if (cursorToken == 0) {
            for (var command : commandManager.getCommands()) {
                var complete = hasArguments(command);

                for (var name : command.getNames()) {
                    context.suggest(name, COMMAND_GROUP, null, complete);
                }
            }
        }

        if (!tokens.isEmpty()) {
            var command = commandManager.getCommand(tokens.getFirst().text());

            if (command == null) {
                unparsedStart = tokens.getFirst().start();
            } else {
                var match = descend(context, build(command), 1);

                if (match.consumed() < tokens.size()) {
                    unparsedStart = tokens.get(match.consumed()).start();
                }

                if (match.position() != null) {
                    usage = childUsage(match.position());
                }
            }
        }

        addCallbackSuggestions(commandManager, sender, buffer.substring(0, cursor), context);
        return new Analysis(unparsedStart, List.copyOf(context.suggestions), usage, word);
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void addCallbackSuggestions(CommandManager commandManager, CommandSender sender, String input, Context context) {
        net.minestom.server.command.builder.suggestion.Suggestion suggestion;

        try {
            suggestion = commandManager.parseCommand(sender, input).suggestion(sender);
        } catch (Exception ignored) {
            return;
        }

        if (suggestion == null) {
            return;
        }

        for (var entry : suggestion.getEntries()) {
            var tooltip = entry.getTooltip();
            var description = tooltip == null ? null : PlainTextComponentSerializer.plainText().serialize(tooltip);
            context.suggest(entry.getEntry(), ARGUMENT_GROUP, description, true);
        }
    }

    private record Token(int start, int end, String text) {
    }

    record Analysis(int unparsedStart, List<Candidate> candidates, @Nullable String usage, String word) {
    }

    private record Match(int consumed, @Nullable Node position) {
    }

    private static final class Node {
        private final String usageText;
        private final List<String> names;
        private final @Nullable Argument<?> argument;
        private final Map<String, Node> children;
        private boolean executable;

        private Node(String usageText, List<String> names, @Nullable Argument<?> argument) {
            this.usageText = usageText;
            this.names = names;
            this.argument = argument;
            this.children = new LinkedHashMap<>();
        }

        private int matchLength(Context context, int index) {
            var text = context.tokens.get(index).text();

            if (this.argument == null) {
                for (var name : this.names) {
                    if (name.equals(text)) {
                        return index + 1;
                    }
                }

                return -1;
            }

            if (this.argument.useRemaining()) {
                var input = context.buffer.substring(context.tokens.get(index).start());
                return parses(this.argument, context.sender, input) ? context.tokens.size() : -1;
            }

            if (!this.argument.allowSpace()) {
                return parses(this.argument, context.sender, text) ? index + 1 : -1;
            }

            var input = new StringBuilder(text);

            for (var end = index + 1; ; end++) {
                if (parses(this.argument, context.sender, input.toString())) {
                    return end;
                }

                if (end >= context.tokens.size()) {
                    return -1;
                }

                input.append(' ').append(context.tokens.get(end).text());
            }
        }
    }

    private static final class Context {
        private final CommandSender sender;
        private final List<Token> tokens;
        private final String buffer;
        private final int cursorToken;
        private final Set<Candidate> suggestions;

        private Context(CommandSender sender, List<Token> tokens, String buffer, int cursorToken) {
            this.sender = sender;
            this.tokens = tokens;
            this.buffer = buffer;
            this.cursorToken = cursorToken;
            this.suggestions = new LinkedHashSet<>();
        }

        private void suggest(String value, String group, @Nullable String description, boolean complete) {
            this.suggestions.add(new Candidate(value, value, group, description, null, null, complete));
        }

        private void suggest(Node node) {
            var complete = !node.children.isEmpty();

            if (node.argument == null) {
                for (var name : node.names) {
                    this.suggest(name, SUBCOMMAND_GROUP, null, complete);
                }

                return;
            }

            if (node.argument instanceof ArgumentLiteral literal) {
                this.suggest(literal.getId(), ARGUMENT_GROUP, null, complete);
                return;
            }

            var choices = choices(node.argument);

            if (choices == null) {
                return;
            }

            var description = usageText(node.argument);

            for (var choice : choices) {
                this.suggest(choice, ARGUMENT_GROUP, description, complete);
            }
        }
    }
}
