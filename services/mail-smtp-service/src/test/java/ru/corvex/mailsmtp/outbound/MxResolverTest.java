package ru.corvex.mailsmtp.outbound;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Cache;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MxResolverTest {

    private Resolver originalResolver;
    private Cache originalCache;

    @BeforeEach
    void saveDnsjavaDefaults() {
        originalResolver = Lookup.getDefaultResolver();
        originalCache = Lookup.getDefaultCache(DClass.IN);
    }

    @AfterEach
    void restoreDnsjavaDefaults() {
        Lookup.setDefaultResolver(originalResolver);
        Lookup.setDefaultCache(originalCache, DClass.IN);
    }

    @Test
    void sortsMxByPriorityAndStripsTrailingDots() throws Exception {
        answerWith(owner -> List.of(
                mx(owner, 30, "mx30.example.net."),
                mx(owner, 10, "mx10.example.net."),
                mx(owner, 20, "mx20.example.net.")));

        assertEquals(
                List.of("mx10.example.net", "mx20.example.net", "mx30.example.net"),
                new MxResolver().lookupMxHosts("priority.example.test"));
    }

    @Test
    void fallsBackToImplicitMxWhenResolverHasNoAnswer() throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenThrow(new IOException("test DNS unavailable"));
        install(resolver);

        assertEquals(List.of("implicit.example.test"),
                new MxResolver().lookupMxHosts("implicit.example.test"));
    }

    @Test
    void fallsBackToDomainWhenAnswerContainsNoMxRecords() throws Exception {
        answerWith(owner -> List.of(aRecord(owner)));

        assertEquals(List.of("a-only.example.test"),
                new MxResolver().lookupMxHosts("a-only.example.test"));
    }

    @Test
    void wrapsInvalidDomainSyntax() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new MxResolver().lookupMxHosts(""));

        assertInstanceOf(TextParseException.class, error.getCause());
    }

    private void answerWith(Function<Name, List<Record>> answerFactory) throws Exception {
        Resolver resolver = mock(Resolver.class);
        when(resolver.send(any(Message.class))).thenAnswer(invocation -> {
            Message query = invocation.getArgument(0);
            Message response = new Message(query.getHeader().getID());
            response.getHeader().setFlag(Flags.QR);
            response.getHeader().setFlag(Flags.RA);
            response.addRecord(query.getQuestion(), Section.QUESTION);
            for (Record record : answerFactory.apply(query.getQuestion().getName())) {
                response.addRecord(record, Section.ANSWER);
            }
            return response;
        });
        install(resolver);
    }

    private void install(Resolver resolver) {
        Lookup.setDefaultResolver(resolver);
        Lookup.setDefaultCache(new Cache(DClass.IN), DClass.IN);
    }

    private static MXRecord mx(Name owner, int priority, String target) {
        try {
            return new MXRecord(owner, DClass.IN, 300, priority, Name.fromString(target));
        } catch (TextParseException e) {
            throw new AssertionError(e);
        }
    }

    private static ARecord aRecord(Name owner) {
        try {
            return new ARecord(owner, DClass.IN, 300,
                    InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34}));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
