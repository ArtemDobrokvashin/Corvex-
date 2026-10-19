package ru.corvex.mailsmtp.outbound;

import org.springframework.stereotype.Component;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** MX-резолвер получателя на dnsjava. Нет MX — implicit MX: сам домен (RFC 5321 §5.1). */
@Component
public class MxResolver {

    public List<String> lookupMxHosts(String domain) {
        try {
            Record[] records = new Lookup(domain, Type.MX).run();
            if (records == null || records.length == 0) {
                return List.of(domain);
            }
            List<MXRecord> mx = new ArrayList<>();
            for (Record r : records) {
                if (r instanceof MXRecord m) {
                    mx.add(m);
                }
            }
            if (mx.isEmpty()) {
                return List.of(domain);
            }
            mx.sort(Comparator.comparingInt(MXRecord::getPriority));
            return mx.stream()
                    .map(r -> r.getTarget().toString().replaceAll("\\.$", ""))
                    .toList();
        } catch (TextParseException e) {
            throw new IllegalStateException("Некорректный домен получателя: " + domain, e);
        }
    }
}
