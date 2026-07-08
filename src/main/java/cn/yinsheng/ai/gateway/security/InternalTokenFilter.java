package cn.yinsheng.ai.gateway.security;

import cn.yinsheng.ai.gateway.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalTokenFilter extends OncePerRequestFilter {
  private final GatewayProperties properties;

  public InternalTokenFilter(GatewayProperties properties) {
    this.properties = properties;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (path.startsWith("/actuator") || path.startsWith("/internal")) {
      filterChain.doFilter(request, response);
      return;
    }

    String expected = properties.apiToken();
    String authorization = request.getHeader("Authorization");
    if (expected != null && !expected.isBlank() && !"change-me".equals(expected)) {
      String actual = authorization != null && authorization.startsWith("Bearer ")
          ? authorization.substring("Bearer ".length())
          : "";
      if (!expected.equals(actual)) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
        return;
      }
    }
    filterChain.doFilter(request, response);
  }
}
