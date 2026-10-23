package ru.corvex.admin.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalApiTokenFilterTest {

    @Test
    void nonInternalPathsAreNotFiltered() throws ServletException, IOException {
        InternalApiTokenFilter filter = new InternalApiTokenFilter(null);
        MockHttpServletRequest request = request("/api/v1/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        assertTrue(filter.shouldNotFilter(request));
        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void exactAndNestedInternalPathsAreFiltered() {
        InternalApiTokenFilter filter = new InternalApiTokenFilter("secret");

        assertFalse(filter.shouldNotFilter(request("/internal")));
        assertFalse(filter.shouldNotFilter(request("/internal/users/1/access")));
        assertTrue(filter.shouldNotFilter(request("/internal-other")));
    }

    @Test
    void missingConfiguredTokenFailsClosedWithProblemDetails() throws ServletException, IOException {
        InternalApiTokenFilter filter = new InternalApiTokenFilter("  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request("/internal/users/1/access"), response, chain);

        assertNull(chain.getRequest());
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentType().startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        assertEquals(StandardCharsets.UTF_8.name(), response.getCharacterEncoding());
        assertTrue(response.getContentAsString().contains("Internal API token is not configured"));
    }

    @Test
    void correctTokenWithConfiguredWhitespaceTrimmedPasses() throws ServletException, IOException {
        InternalApiTokenFilter filter = new InternalApiTokenFilter(" secret ");
        MockHttpServletRequest request = request("/internal/users/1/access");
        request.addHeader(InternalApiTokenFilter.HEADER, "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    @Test
    void missingOrWrongTokenReturnsUnauthorizedProblemDetails() throws ServletException, IOException {
        assertUnauthorized(null);
        assertUnauthorized("wrong-secret");
    }

    private void assertUnauthorized(String token) throws ServletException, IOException {
        InternalApiTokenFilter filter = new InternalApiTokenFilter("secret");
        MockHttpServletRequest request = request("/internal/users/1/access");
        if (token != null) {
            request.addHeader(InternalApiTokenFilter.HEADER, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Invalid internal API token"));
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
