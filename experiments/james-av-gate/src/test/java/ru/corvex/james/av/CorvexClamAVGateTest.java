package ru.corvex.james.av;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.lang.reflect.Proxy;
import java.util.Map;
import jakarta.mail.MessagingException;
import org.apache.mailet.MailetConfig;
import java.nio.charset.StandardCharsets;
import org.apache.james.clamav.ClamAVScan;
import org.junit.jupiter.api.Test;

/** Unit tests only; Maven classpath is not the James image classloader. */
class CorvexClamAVGateTest {
    private ByteArrayInputStream reply(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test void recognizesOnlyExactCleanAndValidFound() throws Exception {
        assertFalse(CorvexClamAVGate.parseReply(reply("stream: OK\0")));
        assertTrue(CorvexClamAVGate.parseReply(reply("stream: Eicar-Test FOUND\0")));
        for (String bad : new String[]{"stream: OK", "stream: BAD OK\0", "stream:  FOUND\0", "stream: ERROR\0", ""}) {
            assertThrows(IOException.class, () -> CorvexClamAVGate.parseReply(reply(bad)));
        }
        assertThrows(IOException.class, () -> CorvexClamAVGate.parseReply(reply("x".repeat(4096))));
    }

    @Test void emptyAddressListFailsClosedBeforeNetworkAccess() throws Exception {
        CorvexClamAVGate gate = new CorvexClamAVGate();
        var setAddresses = ClamAVScan.class.getDeclaredMethod("setAddresses", InetAddress[].class);
        setAddresses.setAccessible(true);
        setAddresses.invoke(gate, (Object) new InetAddress[0]);
        assertThrows(IOException.class, () -> gate.hasVirus(new ByteArrayInputStream(new byte[]{1})));
    }
    private static MailetConfig config(Map<String, String> parameters) {
        return (MailetConfig) Proxy.newProxyInstance(MailetConfig.class.getClassLoader(),
            new Class<?>[]{MailetConfig.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getInitParameter" -> parameters.get(args[0]);
                case "getInitParameterNames" -> parameters.keySet().iterator();
                case "getMailetName" -> "CorvexClamAVGate";
                default -> throw new AssertionError("Unexpected MailetConfig method: " + method.getName());
            });
    }

    @Test void invalidConfigurationFailsInitializationRatherThanContinuing() throws Exception {
        for (Map<String, String> invalid : java.util.List.of(
            Map.<String, String>of(), Map.of("host", " "),
            Map.of("host", "127.0.0.1", "port", "0"),
            Map.of("host", "127.0.0.1", "port", "65536"),
            Map.of("host", "127.0.0.1", "port", "+3310"),
            Map.of("host", "127.0.0.1", "maxPings", "1"))) {
            assertThrows(MessagingException.class, () -> new CorvexClamAVGate().init(config(invalid)));
        }
        CorvexClamAVGate gate = new CorvexClamAVGate();
        gate.init(config(Map.of("host", "127.0.0.1", "port", "12345")));
        assertEquals(12345, gate.getPort());
    }

}
