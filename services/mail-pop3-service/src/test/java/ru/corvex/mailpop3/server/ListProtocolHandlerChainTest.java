package ru.corvex.mailpop3.server;

import org.apache.james.protocols.api.handler.ExtensibleHandler;
import org.apache.james.protocols.api.handler.ProtocolHandler;
import org.apache.james.protocols.api.handler.WiringException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListProtocolHandlerChainTest {

    @Test
    void wiresOnlyHandlersImplementingDeclaredMarkerInOriginalOrder() throws Exception {
        RecordingExtensibleHandler extensible = new RecordingExtensibleHandler();
        MarkerHandler first = new MarkerHandler();
        PlainHandler plain = new PlainHandler();
        MarkerHandler second = new MarkerHandler();

        new ListProtocolHandlerChain(List.of(extensible, first, plain, second));

        assertEquals(List.of(first, second), extensible.wired.get(Marker.class));
    }

    @Test
    void looksUpHandlersByTypeAndReturnsFirstMatch() throws Exception {
        PlainHandler plain = new PlainHandler();
        MarkerHandler marker = new MarkerHandler();
        ListProtocolHandlerChain chain = new ListProtocolHandlerChain(List.of(plain, marker));

        LinkedList<Marker> markers = chain.getHandlers(Marker.class);
        assertEquals(List.of(marker), markers);
        assertSame(plain, chain.getFirstHandler(PlainHandler.class).orElseThrow());
        assertTrue(chain.getFirstHandler(Runnable.class).isEmpty());
    }

    @Test
    void snapshotOfHandlersIsIndependentFromSourceList() throws Exception {
        List<ProtocolHandler> source = new ArrayList<>();
        PlainHandler plain = new PlainHandler();
        source.add(plain);
        ListProtocolHandlerChain chain = new ListProtocolHandlerChain(source);

        source.clear();

        assertEquals(List.of(plain), chain.getHandlers(PlainHandler.class));
    }

    @Test
    void destroyIsSafeNoOp() throws Exception {
        ListProtocolHandlerChain chain = new ListProtocolHandlerChain(List.of(new PlainHandler()));

        assertDoesNotThrow(() -> chain.destroy());
    }

    private interface Marker extends ProtocolHandler {
    }

    private static final class PlainHandler implements ProtocolHandler {
    }

    private static final class MarkerHandler implements Marker {
    }

    private static final class RecordingExtensibleHandler implements ProtocolHandler, ExtensibleHandler {
        private final Map<Class<?>, List<?>> wired = new LinkedHashMap<>();

        @Override
        public List<Class<?>> getMarkerInterfaces() {
            return List.of(Marker.class);
        }

        @Override
        public void wireExtensions(Class<?> interfaceName, List<?> extension) throws WiringException {
            wired.put(interfaceName, List.copyOf(extension));
        }
    }
}
