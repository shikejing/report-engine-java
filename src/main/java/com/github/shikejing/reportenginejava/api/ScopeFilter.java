package com.github.shikejing.reportenginejava.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 作用域鉴权过滤器。
 *
 * <p>所有 /api 请求必须携带 {@code X-Scope} 头（作用域即租户）。
 * 读取、修改、删除定义以及渲染都按作用域隔离：
 * <ul>
 *   <li>请求只带得见本作用域的定义 / 作业；</li>
 *   <li>越权访问其他作用域的资源一律返回 404（与「不存在」相同），
 *       不泄漏其他作用域是否存在该资源。</li>
 * </ul>
 */
@Component
@Order(1)
public class ScopeFilter extends OncePerRequestFilter {

    public static final String SCOPE_ATTRIBUTE = "scope";
    public static final String SCOPE_HEADER = "X-Scope";
    private static final Pattern SCOPE_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String scope = request.getHeader(SCOPE_HEADER);
        if (scope == null || !SCOPE_PATTERN.matcher(scope).matches()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"missing or invalid X-Scope header\"}");
            return;
        }
        request.setAttribute(SCOPE_ATTRIBUTE, scope);
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}
