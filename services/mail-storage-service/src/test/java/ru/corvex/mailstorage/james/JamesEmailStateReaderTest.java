package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JamesEmailStateReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JamesJmapReadClient client = mock(JamesJmapReadClient.class);
    private final JamesEmailStateReader reader = new JamesEmailStateReader(client, mapper);

    @Test void asksForNoEmailIdsAndRequiresBoundAccount() throws Exception {
        when(client.read(anyString(), eq("Email/get"), any())).thenReturn(mapper.readTree(
                "{\"accountId\":\"account-1\",\"state\":\"s1\",\"list\":[],\"notFound\":[]}"));
        assertEquals("s1", reader.current("token", "account-1"));
        verify(client).read(eq("token"), eq("Email/get"), argThat(args ->
                args.path("accountId").asText().equals("account-1") && args.path("ids").isArray() && args.path("ids").isEmpty()));
        assertThrows(IOException.class, () -> reader.current("token", "other-account"));
    }

    @Test void malformedOrMissingFieldsFailClosed() throws Exception {
        for (String json : new String[]{"{}", "{\"accountId\":\"a\",\"state\":\"\",\"list\":[],\"notFound\":[]}",
                "{\"accountId\":\"a\",\"state\":\"s\",\"list\":[{}],\"notFound\":[]}",
                "{\"accountId\":\"a\",\"state\":\"s\",\"list\":[],\"notFound\":[\"x\"]}"}) {
            when(client.read(anyString(), eq("Email/get"), any())).thenReturn(mapper.readTree(json));
            assertThrows(IOException.class, () -> reader.current("token", "a"));
        }
    }
}
