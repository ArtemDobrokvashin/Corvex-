package ru.corvex.james.av;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
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
}
