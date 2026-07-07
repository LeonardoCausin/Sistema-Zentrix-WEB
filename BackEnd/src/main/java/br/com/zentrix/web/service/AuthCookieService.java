package br.com.zentrix.web.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthCookieService {
    private String cookieName = "ZENTRIX_AUTH";
    private String sameSite = "Lax";
    private boolean secure = false;
    private Duration maxAge = Duration.ofHours(8);

    @Value("${zentrix.auth.cookie.name:ZENTRIX_AUTH}")
    public void setCookieName(String cookieName) {
        if (cookieName != null && !cookieName.isBlank()) {
            this.cookieName = cookieName.trim();
        }
    }

    @Value("${zentrix.auth.cookie.same-site:Lax}")
    public void setSameSite(String sameSite) {
        if (sameSite != null && !sameSite.isBlank()) {
            this.sameSite = sameSite.trim();
        }
    }

    @Value("${zentrix.auth.cookie.secure:false}")
    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    @Value("${zentrix.auth.cookie.max-age-minutes:480}")
    public void setMaxAgeMinutes(long minutes) {
        this.maxAge = Duration.ofMinutes(Math.max(5, minutes));
    }

    public String readToken(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void writeToken(HttpServletResponse response, String token) {
        if (response == null || token == null || token.isBlank()) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie(token)
                .maxAge(maxAge)
                .build()
                .toString());
    }

    public void clearToken(HttpServletResponse response) {
        if (response == null) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie("")
                .maxAge(Duration.ZERO)
                .build()
                .toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/");
    }
}
