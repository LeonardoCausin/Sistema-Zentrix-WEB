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
        PRODUCTS_CREATE,
        PRODUCTS_EDIT,
        PRODUCTS_DISABLE,
        MANAGE_CLIENTS,
        CLIENTS_CREATE,
        CLIENTS_EDIT,
        MANAGE_STOCK,
        STOCK_MOVE,
        MANAGE_FINANCE,
        CANCEL_SALE,
        CLOSE_CASH,
        CASH_MOVEMENT,
        MANAGE_USERS,
        USERS_CREATE,
        USERS_EDIT,
        MANAGE_PERMISSIONS,
        USERS_PERMISSIONS,
        MANAGE_SETTINGS,
        MANAGE_LICENSE,
        RESTORE_BACKUP,
        HIGH_DISCOUNT
    }

    private static final Map<Role, Set<Permission>> ROLE_PERMISSIONS = new EnumMap<>(Role.class);
    private static final Map<Permission, Set<String>> PERMISSION_KEYS = new EnumMap<>(Permission.class);

    static {
        ROLE_PERMISSIONS.put(Role.DONO, EnumSet.allOf(Permission.class));
        ROLE_PERMISSIONS.put(Role.ADMIN, EnumSet.allOf(Permission.class));
        ROLE_PERMISSIONS.put(Role.GERENTE, EnumSet.of(
                Permission.VIEW_PANEL,
                Permission.VIEW_REPORTS,
                Permission.MANAGE_PRODUCTS,
                Permission.PRODUCTS_CREATE,
                Permission.PRODUCTS_EDIT,
                Permission.PRODUCTS_DISABLE,
                Permission.MANAGE_CLIENTS,
                Permission.CLIENTS_CREATE,
                Permission.CLIENTS_EDIT,
                Permission.MANAGE_STOCK,
                Permission.STOCK_MOVE,
                Permission.MANAGE_FINANCE,
                Permission.CANCEL_SALE,
                Permission.CLOSE_CASH,
                Permission.CASH_MOVEMENT,
                Permission.HIGH_DISCOUNT
        ));
        ROLE_PERMISSIONS.put(Role.CAIXA, EnumSet.of(
                Permission.VIEW_PANEL,
                Permission.MANAGE_CLIENTS,
                Permission.CLIENTS_CREATE,
                Permission.CLIENTS_EDIT,
                Permission.CASH_MOVEMENT
        ));
        ROLE_PERMISSIONS.put(Role.ESTOQUISTA, EnumSet.of(
                Permission.VIEW_PANEL,
                Permission.MANAGE_PRODUCTS,
                Permission.PRODUCTS_CREATE,
                Permission.PRODUCTS_EDIT,
                Permission.MANAGE_STOCK,
                Permission.STOCK_MOVE
        ));
        ROLE_PERMISSIONS.put(Role.CONSULTA, EnumSet.of(
                Permission.VIEW_PANEL,
                Permission.VIEW_REPORTS
        ));
        ROLE_PERMISSIONS.put(Role.FINANCEIRO, EnumSet.of(
                Permission.VIEW_PANEL,
                Permission.VIEW_REPORTS,
                Permission.MANAGE_FINANCE
        ));

        map(Permission.VIEW_PANEL,
                "dashboard.visualizar",
                "vendas.visualizar",
                "caixa.visualizar",
                "produtos.visualizar",
                "estoque.visualizar",
                "clientes.visualizar",
                "funcionarios.visualizar",
                "financeiro.visualizar",
                "configuracoes.visualizar"
        );
        map(Permission.VIEW_REPORTS, "relatorios.visualizar");
        map(Permission.MANAGE_PRODUCTS, "produtos.criar", "produtos.editar", "produtos.desativar");
        map(Permission.PRODUCTS_CREATE, "produtos.criar");
        map(Permission.PRODUCTS_EDIT, "produtos.editar");
        map(Permission.PRODUCTS_DISABLE, "produtos.desativar");
        map(Permission.MANAGE_CLIENTS, "clientes.criar", "clientes.editar");
        map(Permission.CLIENTS_CREATE, "clientes.criar");
        map(Permission.CLIENTS_EDIT, "clientes.editar");
        map(Permission.MANAGE_STOCK, "estoque.movimentar");
        map(Permission.STOCK_MOVE, "estoque.movimentar");
        map(Permission.MANAGE_FINANCE, "financeiro.editar");
        map(Permission.CANCEL_SALE, "vendas.cancelar");
        map(Permission.CLOSE_CASH, "caixa.fechar");
        map(Permission.CASH_MOVEMENT, "caixa.sangria", "caixa.suprimento", "caixa.abrir");
        map(Permission.MANAGE_USERS, "funcionarios.criar", "funcionarios.editar");
        map(Permission.USERS_CREATE, "funcionarios.criar");
        map(Permission.USERS_EDIT, "funcionarios.editar");
        map(Permission.MANAGE_PERMISSIONS, "funcionarios.permissoes");
        map(Permission.USERS_PERMISSIONS, "funcionarios.permissoes");
        map(Permission.MANAGE_SETTINGS, "configuracoes.editar");
        map(Permission.MANAGE_LICENSE, "configuracoes.editar");
        map(Permission.RESTORE_BACKUP, "backups.restaurar");
        map(Permission.HIGH_DISCOUNT, "vendas.cancelar");
    }

    public boolean can(Permission permission) {
        return AuthContext.current()
                .map(session -> can(session.role(), session.permissions(), permission))
                .orElse(false);
    }

    public boolean can(String role, Permission permission) {
        return can(role, Set.of(), permission);
    }

    public boolean can(String role, Set<String> customPermissions, Permission permission) {
        Role normalizedRole = normalizeRole(role);
        if (normalizedRole == Role.DONO) {
            return true;
        }
        if (customPermissions != null && !customPermissions.isEmpty()) {
            if (customPermissions.contains("*") || customPermissions.contains("admin")) {
                return true;
            }
            for (String key : PERMISSION_KEYS.getOrDefault(permission, Set.of())) {
                if (customPermissions.contains(key)) {
                    return true;
                }
            }
            return false;
        }
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
            case "FINANCEIRO", "FINANCE" -> Role.FINANCEIRO;
            default -> Role.CONSULTA;
        };
    }

    private String normalize(String value) {
        String text = value == null ? "" : value.trim().toUpperCase();
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private static void map(Permission permission, String... keys) {
        PERMISSION_KEYS.put(permission, Set.of(keys));
    }

    public enum Role {
        DONO,
        ADMIN,
        GERENTE,
        CAIXA,
        ESTOQUISTA,
        FINANCEIRO,
        CONSULTA
    }
}
