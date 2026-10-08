package dev.granary.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/** 401/403 bodies shared by the filter, the entry point and the repository dispatcher. */
public final class SecurityResponses {

    private SecurityResponses() {
    }

    public static void unauthorized(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // Package managers only send credentials after a Basic challenge; browsers must not get one,
        // or they would pop up a native login dialog over the single-page app.
        if (request.getRequestURI().contains("/repository/") || !acceptsHtml(request)) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Granary\", charset=\"UTF-8\"");
        }
        write(response, "auth.required", message);
    }

    public static void forbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        write(response, "auth.forbidden", message);
    }

    private static void write(HttpServletResponse response, String code, String message) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + escape(message) + "\"}";
        response.getWriter().write(body);
        response.getWriter().flush();
    }

    private static boolean acceptsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
