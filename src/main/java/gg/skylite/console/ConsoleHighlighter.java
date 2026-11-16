package gg.skylite.console;

import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import org.jline.reader.Candidate;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jspecify.annotations.NullMarked;

import java.util.List;
import java.util.function.Supplier;

@NullMarked
final class ConsoleHighlighter implements Highlighter {
    private final Supplier<CommandSender> sender;

    ConsoleHighlighter(Supplier<CommandSender> sender) {
        this.sender = sender;
    }

    private static String commonPrefix(List<String> values) {
        var prefix = values.getFirst();

        for (var value : values) {
            var length = 0;

            while (length < prefix.length() && length < value.length() && Character.toLowerCase(prefix.charAt(length)) == Character.toLowerCase(value.charAt(length))) {
                length++;
            }

            prefix = prefix.substring(0, length);
        }

        return prefix;
    }

    private static String suggestion(CommandTree.Analysis analysis) {
        var word = analysis.word();

        if (word.isEmpty()) {
            var usage = analysis.usage();
            return usage == null ? "" : usage;
        }

        var matches = analysis.candidates().stream()
                .map(Candidate::value)
                .filter(value -> value.regionMatches(true, 0, word, 0, word.length()))
                .distinct()
                .toList();

        if (matches.isEmpty()) {
            return "";
        }

        var prefix = commonPrefix(matches);
        return prefix.length() > word.length() ? prefix.substring(word.length()) : "";
    }

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        CommandTree.Analysis analysis;

        try {
            analysis = CommandTree.analyze(
                    MinecraftServer.getCommandManager(),
                    this.sender.get(),
                    buffer,
                    reader.getBuffer().cursor());
        } catch (Throwable ignored) {
            reader.setTailTip("");
            return new AttributedString(buffer);
        }

        var limit = analysis.unparsedStart() < 0 ? buffer.length() : analysis.unparsedStart();
        var builder = new AttributedStringBuilder();
        builder.append(buffer, 0, limit);

        if (limit < buffer.length()) {
            builder.styled(CommandTree.UNPARSED_STYLE, buffer.substring(limit));
        }

        reader.setTailTip(suggestion(analysis));
        return builder.toAttributedString();
    }
}
