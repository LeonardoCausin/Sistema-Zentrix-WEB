package br.com.zentrix.web.service;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PermissionService {
    public enum Permission {
        VIEW_PANEL,
        VIEW_REPORTS,
        MANAGE_PRODUCTS,
        MANAGE_STOCK,
        MANAGE_FINANCE,
        CANCEL_SALE,
        CLOSE_CASH,
        CASH_MOVEMENT,
        MANAGE_USERS,
        MANAGE_PERMISSIONS,
        MANAGE_SETTINGS,
        MANAGE_LICENSE,
        RESTORE_BACKUP,
        HIGH_DISCOUNT
    }

    private static final Map<Role, Set<Permission>> ROLE_PERMISSIONS = new EnumMap<>(Role.class);

    static {
        ROLE_PERMISSIONS.put(Role.DONO, EnumSet.allOf(Permission.class));
        ROLE_PERMISSIONS.put(Role.ADMIN, EnumSet.allOf(Permission.class));
        ROLE_PERMISSIONS.put(Role.GERENTE, EnumSet.of(
                Permission.VIEW_PANEL,
                Permission.VIEW_REPORTS,
                Permission.MANAGE_PRODUCTS,
                Permission.MANAGE_STOCK,
                Permission.MANAGE_FINANCE,
                Permission.CANCEL_SALE,
                Permission.CLOSE_CASH,
                Permission.CASH_MOVEMENT,
                Permission.HIGH_DISCOUNT
        ));
        ROLE_PERMISSIONS.put(Role.CAIXA, EnumSet.of(
                Permission.VIEW_PANEL,
                Permission.CASH_MOVEMENT
        ));
        ROLE_PERMISSIONS.put(Role.ESTOQUISTA, EnumSet.of(
                Permission.VIEW_PANEL,
                Permission.MANAGE_PRODUCTS,
                Permission.MANAGE_STOCK
        ));
        ROLE_PERMISSIONS.put(Role.CONSULTA, EnumSet.of(
                Permission.VIEW_PANEL,
                Permission.VIEW_REPORTS
        ));
    }

    public boolean can(Permission permission) {
        return AuthContext.current()
                .map(session -> can(session.role(), permission))
                .orElse(false);
    }

    public boolean can(String role, Permission permission) {
        Role normalizedRole = normalizeRole(role);
        return ROLE_PERMISSIONS.getOrDefault(normalizedRole, Set.of()).contains(permission);
    }

    public void require(Permission permission) {
        if (!can(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não tem permissão para executar esta ação.");
        }
    }

    public Role normalizeRole(String role) {
        String value = normalize(role);
        return switch (value) {
            case "DONO", "OWNER" -> Role.DONO;
            case "ADMIN", "ADMINISTRADOR", "ADMINISTRATOR" -> Role.ADMIN;
            case "GERENTE", "MANAGER" -> Role.GERENTE;
            case "CAIXA", "OPERADOR", "OPERATOR" -> Role.CAIXA;
            case "ESTOQUISTA", "STOCK" -> Role.ESTOQUISTA;
            default -> Role.CONSULTA;
        };
    }

    private String normalize(String value) {
        String text = value == null ? "" : value.trim().toUpperCase();
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    public enum Role {
        DONO,
        ADMIN,
        GERENTE,
        CAIXA,
        ESTOQUISTA,
        CONSULTA
    }
}
