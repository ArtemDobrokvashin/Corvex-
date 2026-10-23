package ru.corvex.auth.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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

    @Test
    void filtersOnlyExactInternalPrefixAndPassesPublicPaths() throws Exception {
        var filter = new InternalApiTokenFilter(null);

        assertFalse(filter.shouldNotFilter(request("/internal", null)));
        assertFalse(filter.shouldNotFilter(request("/internal/users", null)));
        assertTrue(filter.shouldNotFilter(request("/internal-other", null)));
        assertTrue(filter.shouldNotFilter(request("/api/v1/auth/me", null)));

        var response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request("/api/v1/auth/me", null), response, chain(called));
        assertTrue(called.get());
        assertEquals(200, response.getStatus());
    }

    @Test
    void missingTokenReturnsProblemDetails() throws Exception {
        var filter = new InternalApiTokenFilter(" expected-secret ");
        var response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request(null), response, chain(called));

        assertFalse(called.get());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        assertTrue(response.getContentAsString().contains("Invalid internal API token"));
    }

    private static MockHttpServletRequest request(String token) {
        return request("/internal/users", token);
    }

    private static MockHttpServletRequest request(String uri, String token) {
        var request = new MockHttpServletRequest("GET", uri);
        if (token != null) {
            request.addHeader(InternalApiTokenFilter.HEADER, token);
        }
        return request;
    }

    private static FilterChain chain(AtomicBoolean called) {
        return (request, response) -> called.set(true);
    }
}
