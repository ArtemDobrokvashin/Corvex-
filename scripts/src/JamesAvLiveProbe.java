import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Separate JVM, real clamd. Does not enqueue mail or call James spool. */
public final class JamesAvLiveProbe {
    private static Object newGate(String host, String port) throws Exception {
        Class<?> configType = Class.forName("org.apache.mailet.MailetConfig");
        Class<?> gateType = Class.forName("ru.corvex.james.av.CorvexClamAVGate");
        Map<String, String> parameters = Map.of("host", host, "port", port);
        Object config = Proxy.newProxyInstance(configType.getClassLoader(), new Class<?>[]{configType},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getInitParameter": return parameters.get(arguments[0]);
                        case "getInitParameterNames": return parameters.keySet().iterator();
                        case "getMailetName": return "CorvexClamAVGate";
                        default: throw new IllegalStateException("Unexpected config call: " + method.getName());
                    }
                });
        Object gate = gateType.getDeclaredConstructor().newInstance();
        gateType.getMethod("init", configType).invoke(gate, config);
        return gate;
    }

    private static boolean scan(Object gate, byte[] payload) throws Exception {
        return (Boolean) gate.getClass().getMethod("hasVirus", java.io.InputStream.class)
                .invoke(gate, new ByteArrayInputStream(payload));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No arguments expected");
        Object gate = newGate("clamav", "3310");
        try {
            byte[] clean = "Corvex synthetic scanner connectivity check".getBytes(StandardCharsets.US_ASCII);
            byte[] eicar = String.join("", "X5O!P%@AP[4\\PZX54(P^)7CC)7}$",
                    "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);
            if (scan(gate, clean)) throw new IllegalStateException("Synthetic clean content reported infected");
            System.out.println("PASS: separate gate instance + real ClamAV: clean => CLEAN");
            if (!scan(gate, eicar)) throw new IllegalStateException("EICAR was not detected");
            System.out.println("PASS: separate gate instance + real ClamAV: EICAR => INFECTED");
        } finally {
            gate.getClass().getMethod("destroy").invoke(gate);
        }
        Object unavailable = newGate("127.0.0.1", "1");
        try {
            try {
                scan(unavailable, "synthetic".getBytes(StandardCharsets.US_ASCII));
                throw new IllegalStateException("Unexpected scanner connection on loopback port 1");
            } catch (InvocationTargetException e) {
                if (!(e.getCause() instanceof IOException)) throw e;
                System.out.println("PASS: separate gate instance + unavailable local endpoint => IO failure");
            }
        } finally {
            unavailable.getClass().getMethod("destroy").invoke(unavailable);
        }
        System.out.println("NOT TESTED: live spool delivery, quarantine, real clamd outage, archive completeness or JMAP/IMAP bypass");
    }
}
