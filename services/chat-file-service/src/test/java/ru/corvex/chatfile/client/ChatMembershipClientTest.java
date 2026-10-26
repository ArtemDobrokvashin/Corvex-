package ru.corvex.chatfile.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chatfile.security.InternalTokenGuard;

import java.lang.reflect.Constructor;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChatMembershipClientTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String URL = "http://chat-service:8088/internal/chat/rooms/"
            + ROOM_ID + "/members/" + USER_ID;

    private MockRestServiceServer server;
    private ChatMembershipClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://chat-service:8088");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ChatMembershipClient(
                builder.build(),
                new InternalTokenGuard("internal-secret"),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void membershipSendsInternalTokenAndParsesResponse() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(InternalTokenGuard.HEADER, "internal-secret"))
                .andRespond(withSuccess(membershipJson(true), MediaType.APPLICATION_JSON));

        ChatMembershipClient.Membership result = client.membership(ROOM_ID, USER_ID);

        assertTrue(result.member());
        assertEquals("ADMIN", result.role());
        assertTrue(result.roomModerator());
        assertFalse(result.mutedNow());
        server.verify();
    }

    @Test
    void requireMemberRejectsNonMember() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(membershipJson(false), MediaType.APPLICATION_JSON));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> client.requireMember(ROOM_ID, USER_ID));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        server.verify();
    }

    @Test
    void upstreamProblemDetailAndStatusArePreserved() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"detail\":\"CORVEX_INTERNAL_API_TOKEN не настроен\"}"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> client.membership(ROOM_ID, USER_ID));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertEquals("CORVEX_INTERNAL_API_TOKEN не настроен", error.getReason());
        server.verify();
    }

    @Test
    void springIsDirectedToTheConfigurationConstructor() throws Exception {
        Constructor<ChatMembershipClient> constructor = ChatMembershipClient.class.getConstructor(
                String.class, InternalTokenGuard.class, ObjectMapper.class);

        assertTrue(constructor.isAnnotationPresent(Autowired.class));
    }

    private String membershipJson(boolean member) {
        return "{"
                + "\"roomId\":\"" + ROOM_ID + "\","
                + "\"userId\":\"" + USER_ID + "\","
                + "\"member\":" + member + ","
                + "\"role\":" + (member ? "\"ADMIN\"" : "null") + ","
                + "\"mutedUntil\":null,"
                + "\"roomReadOnly\":false"
                + "}";
    }
}
