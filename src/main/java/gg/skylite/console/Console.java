package gg.skylite.console;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ansi.ANSIComponentSerializer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.MaskingCallback;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

@NullMarked
public final class Console implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Console.class);
    private static final ANSIComponentSerializer SERIALIZER = ANSIComponentSerializer.ansi();
    private static final String PROMPT = new AttributedString("> ").toAnsi();

    private final Terminal terminal;
    private final LineReader reader;
    private final ConsoleCommandSender consoleSender;
    private final Supplier<CommandSender> sender;
    private final Consumer<String> handler;
    private final Runnable exitHandler;
    private final AtomicBoolean running;
    private final @Nullable StreamCapture capture;
    private @Nullable Thread thread;

    private Console(Builder builder) throws IOException {
        this.consoleSender = new ConsoleCommandSender(this);
        this.sender = builder.sender == null ? () -> this.consoleSender : builder.sender;
        this.running = new AtomicBoolean();

        this.terminal = TerminalBuilder.builder()
                .name(builder.appName)
                .system(true)
                .dumb(true)
                .encoding(StandardCharsets.UTF_8)
                .build();

        var parser = new DefaultParser();
        parser.setEscapeChars(null);

        var readerBuilder = LineReaderBuilder.builder()
                .appName(builder.appName)
                .terminal(this.terminal)
                .parser(parser)
                .history(new DefaultHistory())
                .variable(LineReader.HISTORY_FILE, builder.historyFile)
                .variable(LineReader.HISTORY_SIZE, builder.historySize)
                .variable(LineReader.HISTORY_FILE_SIZE, builder.historySize)
                .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%M> ")
                .variable(LineReader.INDENTATION, 2)
                .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                .option(LineReader.Option.HISTORY_IGNORE_SPACE, true)
                .option(LineReader.Option.HISTORY_REDUCE_BLANKS, true)
                .option(LineReader.Option.HISTORY_BEEP, false)
                .option(LineReader.Option.BRACKETED_PASTE, true)
                .option(LineReader.Option.INSERT_TAB, false)
                .option(LineReader.Option.AUTO_MENU, true)
                .option(LineReader.Option.AUTO_LIST, true)
                .option(LineReader.Option.LIST_PACKED, true)
                .option(LineReader.Option.GROUP_PERSIST, true)
                .option(LineReader.Option.COMPLETE_IN_WORD, true)
                .option(LineReader.Option.CASE_INSENSITIVE, true)
                .option(LineReader.Option.CASE_INSENSITIVE_SEARCH, true);

        if (builder.completion) {
            readerBuilder.completer(new ConsoleCompleter(this.sender));
        }

        if (builder.highlighting) {
            readerBuilder.highlighter(new ConsoleHighlighter(this.sender));
        } else {
            readerBuilder.option(LineReader.Option.DISABLE_HIGHLIGHTER, true);
        }

        this.reader = readerBuilder.build();
        this.reader.setAutosuggestion(LineReader.SuggestionType.TAIL_TIP);
        this.handler = builder.handler == null ? line -> ConsoleCommands.execute(this.sender.get(), line) : builder.handler;
        this.exitHandler = builder.exitHandler;
        this.capture = builder.captureStandardStreams ? new StreamCapture(this.reader) : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void close() {
        this.stop();

        if (this.capture != null) {
            this.capture.close();
        }

        try {
            this.terminal.close();
        } catch (IOException exception) {
            LOGGER.debug("Failed to close the terminal", exception);
        }
    }

    public void start() {
        if (!this.running.compareAndSet(false, true)) {
            return;
        }

        var thread = Thread.ofPlatform().name("Console").daemon(true).unstarted(this::run);
        this.thread = thread;
        thread.start();
    }

    public void stop() {
        if (!this.running.compareAndSet(true, false)) {
            return;
        }

        this.saveHistory();

        if (this.thread != null) {
            this.thread.interrupt();
            this.thread = null;
        }
    }

    public void print(Component message) {
        this.print(SERIALIZER.serialize(message));
    }

    public void print(String message) {
        this.reader.printAbove(AttributedString.fromAnsi(message));
    }

    public CommandSender sender() {
        return this.sender.get();
    }

    public LineReader reader() {
        return this.reader;
    }

    public Terminal terminal() {
        return this.terminal;
    }

    public boolean running() {
        return this.running.get();
    }

    private void run() {
        while (this.running.get()) {
            String line;

            try {
                line = this.reader.readLine(PROMPT, null, (MaskingCallback) null, null);
            } catch (UserInterruptException exception) {
                if (exception.getPartialLine() == null || exception.getPartialLine().isEmpty()) {
                    break;
                }

                continue;
            } catch (EndOfFileException exception) {
                break;
            } catch (UncheckedIOException | IllegalStateException exception) {
                if (this.running.get()) {
                    LOGGER.debug("The terminal was closed while reading: {}", exception.toString());
                }

                break;
            }

            if (line == null) {
                break;
            }

            var trimmed = line.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            try {
                this.handler.accept(trimmed);
            } catch (Throwable throwable) {
                LOGGER.error("Failed to handle console input '{}'", trimmed, throwable);
            }
        }

        if (this.running.compareAndSet(true, false)) {
            this.saveHistory();
            this.exitHandler.run();
        }
    }

    private void saveHistory() {
        try {
            this.reader.getHistory().save();
        } catch (IOException exception) {
            LOGGER.debug("Failed to save the console history", exception);
        }
    }

    @NullMarked
    public static final class Builder {
        private String appName;
        private Path historyFile;
        private int historySize;
        private @Nullable Supplier<CommandSender> sender;
        private @Nullable Consumer<String> handler;
        private Runnable exitHandler;
        private boolean completion;
        private boolean highlighting;
        private boolean captureStandardStreams;

        private Builder() {
            this.appName = "minestom";
            this.historyFile = Path.of(".console_history");
            this.historySize = 500;
            this.exitHandler = MinecraftServer::stopCleanly;
            this.completion = true;
            this.highlighting = true;
            this.captureStandardStreams = true;
        }

        public Builder appName(String appName) {
            this.appName = appName;
            return this;
        }

        public Builder historyFile(Path historyFile) {
            this.historyFile = historyFile;
            return this;
        }

        public Builder historySize(int historySize) {
            this.historySize = historySize;
            return this;
        }

        public Builder sender(Supplier<CommandSender> sender) {
            this.sender = sender;
            return this;
        }

        public Builder handler(Consumer<String> handler) {
            this.handler = handler;
            return this;
        }

        public Builder exitHandler(Runnable exitHandler) {
            this.exitHandler = exitHandler;
            return this;
        }

        public Builder completion(boolean completion) {
            this.completion = completion;
            return this;
        }

        public Builder highlighting(boolean highlighting) {
            this.highlighting = highlighting;
            return this;
        }

        public Builder captureStandardStreams(boolean captureStandardStreams) {
            this.captureStandardStreams = captureStandardStreams;
            return this;
        }

        public Console build() throws IOException {
            return new Console(this);
        }
    }
}
