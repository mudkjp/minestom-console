package gg.skylite.console;

import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

@NullMarked
final class ConsoleCompleter implements Completer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleCompleter.class);

    private final Supplier<CommandSender> sender;

    ConsoleCompleter(Supplier<CommandSender> sender) {
        this.sender = sender;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        CommandTree.Analysis analysis;

        try {
            analysis = CommandTree.analyze(
                    MinecraftServer.getCommandManager(),
                    this.sender.get(),
                    line.line(),
                    line.cursor());
        } catch (Throwable throwable) {
            LOGGER.debug("The command manager is not available yet", throwable);
            return;
        }

        candidates.addAll(analysis.candidates());
    }
}
