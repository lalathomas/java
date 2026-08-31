package io.github.lalathomas.walletledger.common.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName()
            + ".correlationId";
    static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = correlationIdFor(request.getHeader(HEADER_NAME));
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        MDC.put(MDC_KEY, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled. Failing to clear MDC can attach one caller's identifier
            // to an unrelated request processed later by the same thread.
            MDC.remove(MDC_KEY);
        }
    }

    static String currentCorrelationId() {
        String correlationId = MDC.get(MDC_KEY);
        return correlationId == null ? "" : correlationId;
    }

    private static String correlationIdFor(String candidate) {
        if (candidate != null && SAFE_CORRELATION_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
