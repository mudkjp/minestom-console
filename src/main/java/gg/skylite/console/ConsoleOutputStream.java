package gg.skylite.console;

import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jspecify.annotations.NullMarked;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@NullMarked
final class ConsoleOutputStream extends OutputStream {
    private final LineReader reader;
    private final ByteArrayOutputStream buffer;

    ConsoleOutputStream(LineReader reader) {
        this.reader = reader;
        this.buffer = new ByteArrayOutputStream(256);
    }

    @Override
    public synchronized void write(int value) {
        if (value == '\n') {
            this.print();
            return;
        }

        if (value == '\r') {
            return;
        }

        this.buffer.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        for (var index = 0; index < length; index++) {
            this.write(bytes[offset + index]);
        }
    }

    @Override
    public synchronized void close() {
        if (this.buffer.size() > 0) {
            this.print();
        }
    }

    private void print() {
        var line = this.buffer.toString(StandardCharsets.UTF_8);
        this.buffer.reset();
        this.reader.printAbove(AttributedString.fromAnsi(line));
        this.reader.getTerminal().flush();
    }
}
