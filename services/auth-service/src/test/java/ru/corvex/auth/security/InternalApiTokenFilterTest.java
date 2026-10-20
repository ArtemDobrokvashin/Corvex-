package ru.corvex.auth.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalApiTokenFilterTest {

    @Test
    void failsClosedWhenTokenIsNotConfigured() throws Exception {
        var response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        new InternalApiTokenFilter("").doFilter(request(null), response, chain(called));

        assertEquals(503, response.getStatus());
        assertFalse(called.get());
    }

    @Test
    void rejectsWrongTokenAndAcceptsCorrectToken() throws Exception {
        var filter = new InternalApiTokenFilter("expected-secret");

        var rejected = new MockHttpServletResponse();
        AtomicBoolean rejectedCalled = new AtomicBoolean();
        filter.doFilter(request("wrong"), rejected, chain(rejectedCalled));
        assertEquals(401, rejected.getStatus());
        assertFalse(rejectedCalled.get());

        var accepted = new MockHttpServletResponse();
        AtomicBoolean acceptedCalled = new AtomicBoolean();
        filter.doFilter(request("expected-secret"), accepted, chain(acceptedCalled));
        assertEquals(200, accepted.getStatus());
        assertTrue(acceptedCalled.get());
    }

    private static MockHttpServletRequest request(String token) {
        var request = new MockHttpServletRequest("GET", "/internal/users");
        if (token != null) {
            request.addHeader(InternalApiTokenFilter.HEADER, token);
        }
        return request;
    }

    private static FilterChain chain(AtomicBoolean called) {
        return (request, response) -> called.set(true);
    }
}
