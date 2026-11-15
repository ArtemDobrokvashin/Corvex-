package ru.corvex.mailstorage.maildomain;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.ResolverConfig;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * dnsjava-реализация с явным таймаутом на один запрос. NXDOMAIN и «нет записей
 * такого типа» возвращаются как пустой список; SERVFAIL, отказ резолвера и
 * таймаут — как {@link DnsRecordSource.DnsLookupException}, чтобы проверка не
 * выдала «записи нет» вместо «проверить не удалось».
 */
public class DnsjavaRecordSource implements DnsRecordSource {

    private final Duration timeout;

    public DnsjavaRecordSource() {
        this(Duration.ofSeconds(3));
    }

    DnsjavaRecordSource(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public List<String> mxHosts(String domain) {
        List<String> hosts = new ArrayList<>();
        for (MXRecord record : lookup(domain, Type.MX, MXRecord.class)) {
            hosts.add(stripDot(record.getTarget().toString()).toLowerCase(Locale.ROOT));
        }
        return List.copyOf(hosts);
    }

    @Override
    public List<String> txt(String name) {
        List<String> values = new ArrayList<>();
        for (TXTRecord record : lookup(name, Type.TXT, TXTRecord.class)) {
            values.add(String.join("", record.getStrings()));
        }
        return List.copyOf(values);
    }

    @Override
    public List<String> cnameTargets(String name) {
        List<String> values = new ArrayList<>();
        for (CNAMERecord record : lookup(name, Type.CNAME, CNAMERecord.class)) {
            values.add(stripDot(record.getTarget().toString()).toLowerCase(Locale.ROOT));
        }
        return List.copyOf(values);
    }

    @Override
    public List<String> addresses(String name) {
        List<String> values = new ArrayList<>();
        for (ARecord record : lookup(name, Type.A, ARecord.class)) {
            values.add(record.getAddress().getHostAddress());
        }
        return List.copyOf(values);
    }

    private <T extends Record> List<T> lookup(String name, int type, Class<T> expected) {
        Name query;
        try {
            query = Name.fromString(name.endsWith(".") ? name : name + ".");
        } catch (TextParseException e) {
            throw new DnsRecordSource.DnsLookupException("Некорректное DNS-имя");
        }
        Lookup lookup = new Lookup(query, type, DClass.IN);
        lookup.setResolver(resolver());
        // null создаёт отдельный временный кэш на один запрос: общий кэш
        // процесса мог бы вернуть данные прошлых суток.
        lookup.setCache(null);
        Record[] records = lookup.run();
        int result = lookup.getResult();
        if (result == Lookup.HOST_NOT_FOUND || result == Lookup.TYPE_NOT_FOUND) {
            return List.of();
        }
        if (result != Lookup.SUCCESSFUL || records == null) {
            throw new DnsRecordSource.DnsLookupException("DNS-ответ недоступен, код " + result);
        }
        List<T> values = new ArrayList<>(records.length);
        for (Record record : records) {
            if (expected.isInstance(record)) {
                values.add(expected.cast(record));
            }
        }
        return List.copyOf(values);
    }

    private Resolver resolver() {
        InetSocketAddress server = ResolverConfig.getCurrentConfig().server();
        try {
            SimpleResolver resolver = server == null ? new SimpleResolver() : new SimpleResolver(server);
            resolver.setTimeout(timeout);
            return resolver;
        } catch (UnknownHostException e) {
            throw new DnsRecordSource.DnsLookupException("Резолвер DNS не настроен");
        }
    }

    private static String stripDot(String value) {
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }
}
