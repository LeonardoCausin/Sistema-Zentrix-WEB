package br.com.zentrix.web.service;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LocalAdminAccessService {
    @Value("${zentrix.local-admin.enabled:true}")
    private boolean enabled;

    @Value("${zentrix.local-admin.allow-private-networks:true}")
    private boolean allowPrivateNetworks;

    public void requireLocal(HttpServletRequest request) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Painel local desativado.");
        }
        String remoteAddress = remoteAddress(request);
        if (!isAllowedAddress(remoteAddress)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Painel local permitido apenas na rede do servidor.");
        }
    }

    private String remoteAddress(HttpServletRequest request) {
        String remote = request == null ? "" : request.getRemoteAddr();
        if (isLoopback(remote)) {
            String forwarded = firstForwardedFor(request);
            if (!forwarded.isBlank()) {
                return forwarded;
            }
        }
        return remote == null ? "" : remote.trim();
    }

    private String firstForwardedFor(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return "";
        }
        return forwarded.split(",", 2)[0].trim();
    }

    private boolean isAllowedAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String address = value.trim();
        if (isLoopback(address)) {
            return true;
        }
        return allowPrivateNetworks && isPrivateAddress(address);
    }

    private boolean isLoopback(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String address = value.trim();
        return "127.0.0.1".equals(address)
                || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address)
                || "localhost".equalsIgnoreCase(address);
    }

    private boolean isPrivateAddress(String value) {
        try {
            byte[] bytes = InetAddress.getByName(value).getAddress();
            if (bytes.length == 4) {
                int first = bytes[0] & 0xff;
                int second = bytes[1] & 0xff;
                return first == 10
                        || (first == 172 && second >= 16 && second <= 31)
                        || (first == 192 && second == 168)
                        || (first == 169 && second == 254);
            }
            if (bytes.length == 16) {
                int first = bytes[0] & 0xff;
                return (first & 0xfe) == 0xfc || first == 0xfe;
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
