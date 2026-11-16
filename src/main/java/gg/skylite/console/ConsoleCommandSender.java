package gg.skylite.console;

import net.kyori.adventure.text.Component;
import net.minestom.server.command.ConsoleSender;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class ConsoleCommandSender extends ConsoleSender {
    private final Console console;

    ConsoleCommandSender(Console console) {
        this.console = console;
    }

    @Override
    public void sendMessage(String message) {
        this.console.print(message);
    }

    @Override
    public void sendMessage(Component message) {
        this.console.print(message);
    }
}
