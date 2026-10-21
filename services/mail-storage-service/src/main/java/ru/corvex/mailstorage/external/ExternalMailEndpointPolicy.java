package ru.corvex.mailstorage.external;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.domain.ExternalMailAccount;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Защита custom IMAP/POP3/SMTP endpoints от SSRF: пользователь не должен заставить сервер
 * Corvex подключаться к loopback/link-local/cloud-metadata/приватной сети. Проверка делается
 * при сохранении, тесте и непосредственно перед каждым подключением клиента.
 */
@Component
public class ExternalMailEndpointPolicy {

    private final ExternalMailProperties props;

    public ExternalMailEndpointPolicy(ExternalMailProperties props) {
        this.props = props;
    }

    public record Endpoint(String host, int port, ExternalMailAccount.Security security, List<String> addresses) {
    }

    public Endpoint validate(String hostRaw, int port, ExternalMailAccount.Security security, boolean presetHost) {
        if (!presetHost && !props.allowCustomHosts()) {
            throw bad("Custom почтовые серверы запрещены политикой администратора");
        }
        String host = normalizeHost(hostRaw);
        if (port < 1 || port > 65535) {
            throw bad("Некорректный порт почтового сервера");
        }
        if (security == ExternalMailAccount.Security.PLAIN && !props.allowInsecurePlain()) {
            throw bad("Подключение без TLS запрещено политикой безопасности");
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw bad("Не удалось разрешить DNS-имя почтового сервера: " + host);
        }
        if (resolved.length == 0) {
            throw bad("DNS-имя почтового сервера не разрешилось: " + host);
        }
        for (InetAddress address : resolved) {
            if (isAlwaysBlocked(address) || (!props.allowPrivateNetworks() && isPrivateOrReserved(address))) {
                throw bad("Почтовый сервер указывает на запрещённый сетевой адрес");
            }
        }
        return new Endpoint(host, port, security, Arrays.stream(resolved).map(InetAddress::getHostAddress).toList());
    }

    public String normalizeHost(String raw) {
        if (raw == null || raw.isBlank()) {
            throw bad("Укажите хост почтового сервера");
        }
        String host = raw.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.contains("/") || host.contains("@") || host.contains(" ") || host.contains("_") || host.length() > 255) {
            throw bad("Некорректный хост почтового сервера");
        }
        try {
            return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw bad("Некорректный хост почтового сервера");
        }
    }

    private boolean isAlwaysBlocked(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress();
    }

    private boolean isPrivateOrReserved(InetAddress address) {
        if (address.isSiteLocalAddress()) {
            return true;
        }
        if (address instanceof Inet4Address v4) {
            byte[] b = v4.getAddress();
            int a = Byte.toUnsignedInt(b[0]);
            int c = Byte.toUnsignedInt(b[1]);
            return a == 0
                    || (a == 100 && c >= 64 && c <= 127)      // CGNAT 100.64/10
                    || (a == 192 && c == 0)                     // 192.0.0/24 + TEST-NET-1 192.0.2/24
                    || (a == 198 && (c == 18 || c == 19 || c == 51))
                    || (a == 203 && c == 0)
                    || a >= 240;
        }
        byte[] b = address.getAddress();
        return b.length == 16 && ((b[0] & (byte) 0xfe) == (byte) 0xfc // fc00::/7 unique-local
                || (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == (byte) 0xb8));
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
