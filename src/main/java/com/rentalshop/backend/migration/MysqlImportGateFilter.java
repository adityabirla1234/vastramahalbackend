package com.rentalshop.backend.migration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 503 to every request, the health check included, while MysqlImportRunner is copying data. Render therefore
 * keeps the previous deployment live until the import has finished, and a failed import never receives traffic.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class MysqlImportGateFilter extends OncePerRequestFilter {

    private final MysqlImportRunner importer;

    public MysqlImportGateFilter(MysqlImportRunner importer) {
        this.importer = importer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!importer.isPending()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("Retry-After", "30");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"MAINTENANCE\",\"message\":\"The system is being updated. Please try again in a minute.\"}");
    }
}
