package com.nexuspay.common.api;

import com.nexuspay.common.id.UuidV7;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Establishes the correlation ID for every request.
 * <p>
 * One of the invariants in {@code CLAUDE.md} is that a transaction is traceable
 * end to end — API, database, Kafka, export files, data hub — by a single ID.
 * That only works if the ID is minted at the very edge and never regenerated
 * downstream, so this filter runs before anything else can log a line.
 * <p>
 * A caller-supplied ID is honoured, which lets an acquirer's trace ID flow
 * through our system unchanged.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UuidV7.generate().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled. Leaving the value behind would stamp the next
            // request with the previous request's ID, which is worse than none.
            MDC.remove(MDC_KEY);
        }
    }

    /** The current request's correlation ID, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
