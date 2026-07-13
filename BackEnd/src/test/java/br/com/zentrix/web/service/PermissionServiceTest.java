package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.zentrix.web.service.PermissionService.Permission;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PermissionServiceTest {
    private final PermissionService permissionService = new PermissionService();

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void customPermissionsLimitRoleAccess() {
        AuthContext.set(new AuthTokenService.SessionToken(
                "admin",
                "Administrador",
                "ADMIN",
                "tenant-1",
                Set.of("clientes.criar"),
                Instant.now(),
                Instant.now().plusSeconds(300)
        ));

        assertTrue(permissionService.can(Permission.CLIENTS_CREATE));
        assertFalse(permissionService.can(Permission.PRODUCTS_EDIT));
    }

    @Test
    void emptyCustomPermissionsFallBackToRole() {
        AuthContext.set(new AuthTokenService.SessionToken(
                "caixa",
                "Caixa",
                "CAIXA",
                "tenant-1",
                Set.of(),
                Instant.now(),
                Instant.now().plusSeconds(300)
        ));

        assertTrue(permissionService.can(Permission.CASH_MOVEMENT));
        assertFalse(permissionService.can(Permission.PRODUCTS_EDIT));
    }

    @Test
    void financeRoleCanManageFinanceWithoutUserAdministration() {
        AuthContext.set(new AuthTokenService.SessionToken(
                "financeiro",
                "Financeiro",
                "FINANCEIRO",
                "tenant-1",
                Set.of(),
                Instant.now(),
                Instant.now().plusSeconds(300)
        ));

        assertTrue(permissionService.can(Permission.MANAGE_FINANCE));
        assertFalse(permissionService.can(Permission.MANAGE_USERS));
    }

    @Test
    void masterAdminHasFullAdministrativeAccess() {
        AuthContext.set(new AuthTokenService.SessionToken(
                "master",
                "Master",
                "MASTER_ADMIN",
                "tenant-1",
                Set.of(),
                Instant.now(),
                Instant.now().plusSeconds(300)
        ));

        assertTrue(permissionService.can(Permission.MANAGE_SETTINGS));
        assertTrue(permissionService.can(Permission.RESTORE_BACKUP));
        assertTrue(permissionService.can(Permission.MANAGE_USERS));
    }
}
