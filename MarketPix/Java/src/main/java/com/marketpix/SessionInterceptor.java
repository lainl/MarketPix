package com.marketpix;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Rejects API calls without a valid session token. The token is sent as {@code Authorization: Bearer
 * <token>}; GET requests may pass it as {@code ?token=} instead, because image previews and download
 * links cannot set headers.
 */
public class SessionInterceptor implements HandlerInterceptor {

    private final SessionService sessions;

    public SessionInterceptor(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (CorsUtils.isPreFlightRequest(request) || sessions.touch(token(request)).isPresent()) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"message\":\"Your session has expired. Please log in again.\"}");
        return false;
    }

    public static String token(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return "GET".equals(request.getMethod()) ? request.getParameter("token") : null;
    }
}
