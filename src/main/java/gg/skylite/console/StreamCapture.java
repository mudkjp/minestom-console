package gg.skylite.console;

import org.jline.reader.LineReader;
import org.jspecify.annotations.NullMarked;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@NullMarked
final class StreamCapture implements AutoCloseable {
    private final PrintStream output;
    private final PrintStream error;
    private final PrintStream stream;

    StreamCapture(LineReader reader) {
        this.output = System.out;
        this.error = System.err;
        this.stream = new PrintStream(new ConsoleOutputStream(reader), true, StandardCharsets.UTF_8);
        System.setOut(this.stream);
        System.setErr(this.stream);
    }

    @Override
    public void close() {
        this.stream.flush();
        System.setOut(this.output);
        System.setErr(this.error);
    }
}
