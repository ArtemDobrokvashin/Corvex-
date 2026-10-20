package ru.corvex.mailpop3.server;

import org.apache.james.protocols.api.handler.ExtensibleHandler;
import org.apache.james.protocols.api.handler.ProtocolHandler;
import org.apache.james.protocols.api.handler.ProtocolHandlerChain;
import org.apache.james.protocols.api.handler.WiringException;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Ручная замена Guice-загрузчика James (ProtocolHandlerChainImpl + ProtocolHandlerLoader):
 * список хендлеров задан кодом в JamesPop3Server, а здесь выполняется та же «проводка»
 * расширяемых хендлеров, что делает James при старте: CommandDispatcher получает все
 * CommandHandler-ы, CapaCmdHandler — все CapaCapability. Без wireExtensions диспетчер
 * не знает ни одной команды и POP3 молча отвечает -ERR на всё.
 */
public class ListProtocolHandlerChain implements ProtocolHandlerChain {

    private final List<ProtocolHandler> handlers;

    public ListProtocolHandlerChain(List<ProtocolHandler> handlers) throws WiringException {
        this.handlers = List.copyOf(handlers);
        wireExtensibleHandlers();
    }

    private void wireExtensibleHandlers() throws WiringException {
        for (ProtocolHandler handler : handlers) {
            if (handler instanceof ExtensibleHandler extensible) {
                for (Class<?> marker : extensible.getMarkerInterfaces()) {
                    List<Object> extensions = new LinkedList<>();
                    for (ProtocolHandler candidate : handlers) {
                        if (marker.isInstance(candidate)) {
                            extensions.add(candidate);
                        }
                    }
                    extensible.wireExtensions(marker, extensions);
                }
            }
        }
    }

    @Override
    public <T> LinkedList<T> getHandlers(Class<T> type) {
        LinkedList<T> out = new LinkedList<>();
        for (ProtocolHandler handler : handlers) {
            if (type.isInstance(handler)) {
                out.add(type.cast(handler));
            }
        }
        return out;
    }

    @Override
    public <T> Optional<T> getFirstHandler(Class<T> type) {
        LinkedList<T> found = getHandlers(type);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    @Override
    public void destroy() {
        // Хендлеры stateless и живут весь жизненный цикл приложения.
    }
}
