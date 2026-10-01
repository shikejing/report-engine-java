package com.github.shikejing.reportenginejava.service;

import com.github.shikejing.reportenginejava.exception.AuthorizationException;
import com.github.shikejing.reportenginejava.exception.ForbiddenException;
import com.github.shikejing.reportenginejava.model.CallerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    public static final String SCOPE_HEADER = "X-Scope";
    public static final String ROLE_HEADER = "X-Role";

    public CallerPrincipal authenticate(HttpServletRequest request) {
        String scope = normalize(request.getHeader(SCOPE_HEADER));
        String role = normalize(request.getHeader(ROLE_HEADER));
        if (scope == null || role == null) {
            throw new AuthorizationException("missing authentication headers");
        }
        if (!scope.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new AuthorizationException("invalid scope");
        }
        if (!"READER".equals(role) && !"ADMIN".equals(role)) {
            throw new AuthorizationException("invalid role");
        }
        return new CallerPrincipal(scope, role);
    }

    public void requireAdmin(CallerPrincipal caller, String resourceScope) {
        if (!"ADMIN".equals(caller.role())) {
            throw new ForbiddenException("admin role required");
        }
        requireScope(caller, resourceScope);
    }

    public void requireReader(CallerPrincipal caller, String resourceScope) {
        requireScope(caller, resourceScope);
    }

    public void requireScope(CallerPrincipal caller, String resourceScope) {
        if (!caller.scope().equals(resourceScope)) {
            throw new AuthorizationException("resource not visible in caller scope");
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
