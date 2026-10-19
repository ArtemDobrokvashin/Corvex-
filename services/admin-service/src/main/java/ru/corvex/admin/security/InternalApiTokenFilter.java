package ru.corvex.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Защита /internal/** общим сервисным токеном в дополнение к сетевой изоляции.
 * Fail closed: без CORVEX_INTERNAL_API_TOKEN внутренний API отвечает 503,
 * а не молча работает в небезопасном режиме.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalApiTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Corvex-Internal-Token";

    private final String expectedToken;

    public InternalApiTokenFilter(@Value("${corvex.internal.api-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.equals("/internal") && !uri.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedToken.isBlank()) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "Internal API token is not configured");
            return;
        }
        String provided = request.getHeader(HEADER);
        boolean valid = provided != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            reject(response, HttpStatus.UNAUTHORIZED, "Invalid internal API token");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\"}");
    }
}
