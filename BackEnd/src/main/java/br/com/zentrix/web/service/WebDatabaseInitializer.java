package br.com.zentrix.web.service;

import br.com.zentrix.web.config.DatabaseConfig.DatabaseSettings;
import jakarta.annotation.PostConstruct;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(WebDatabaseInitializer.class);
    private static final String LEGACY_TENANT_ID = "legacy";
    private static final String LEGACY_DEVICE_ID = "legacy-device";
    private static final Map<String, List<String>> SCOPED_PRIMARY_KEYS = Map.ofEntries(
            Map.entry("users", List.of("tenant_id", "store_id", "username")),
            Map.entry("suppliers", List.of("tenant_id", "store_id", "id")),
            Map.entry("clients", List.of("tenant_id", "store_id", "id")),
            Map.entry("products", List.of("tenant_id", "store_id", "code")),
            Map.entry("stock_movements", List.of("tenant_id", "store_id", "id")),
            Map.entry("cash_sessions", List.of("tenant_id", "store_id", "id")),
            Map.entry("cash_movements", List.of("tenant_id", "store_id", "id")),
            Map.entry("financial_entries", List.of("tenant_id", "store_id", "id")),
            Map.entry("sales", List.of("tenant_id", "store_id", "id")),
            Map.entry("sale_items", List.of("tenant_id", "store_id", "id")),
            Map.entry("sale_cancellations", List.of("tenant_id", "store_id", "id")),
            Map.entry("comandas", List.of("tenant_id", "store_id", "id")),
            Map.entry("comanda_itens", List.of("tenant_id", "store_id", "id")),
            Map.entry("audit_log", List.of("tenant_id", "store_id", "id"))
    );

    private final DatabaseSettings settings;
    private final JdbcTemplate jdbcTemplate;

    public WebDatabaseInitializer(DatabaseSettings settings, JdbcTemplate jdbcTemplate) {
        this.settings = settings;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initializeOnStartup() {
        try {
            ensureReady();
        } catch (Exception e) {
            log.error("Zentrix AppGestão não iniciou porque o banco web não está pronto: {}", e.getMessage(), e);
            throw new IllegalStateException("Banco web indisponível. Verifique MySQL, .env e permissões do usuário.", e);
        }
    }

    public void ensureReady() {
        ensureValidIdentifier(settings.getName());
        ensureDatabaseExists();
        createTables();
    }

    private void ensureDatabaseExists() {
        SQLException databaseConnectionError = null;
        try (var ignored = DriverManager.getConnection(
                settings.jdbcUrl(),
                settings.getUsername(),
                settings.getPassword()
        )) {
            return;
        } catch (SQLException e) {
            databaseConnectionError = e;
        }

        try (var connection = DriverManager.getConnection(
                settings.serverJdbcUrl(),
                settings.getUsername(),
                settings.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + settings.getName()
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            e.addSuppressed(databaseConnectionError);
            throw new IllegalStateException("Não foi possível criar/acessar o banco web " + settings.getName()
                    + " em " + settings.getHost() + ":" + settings.getPort()
                    + " com o usuário " + settings.getUsername(), e);
        }
    }

    private void createTables() {
        for (String ddl : schemaStatements()) {
            jdbcTemplate.execute(ddl);
        }
        migrateTenantScopedTables();
        migrateDomainColumns();
        applyVersionedMigrations();
        seedTenantMetadataFromExistingData();
    }

    private void ensureValidIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Nome de banco invalido: " + value);
        }
    }

    private List<String> schemaStatements() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version VARCHAR(40) NOT NULL,
                    description VARCHAR(180) NOT NULL,
                    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS tenants (
                    id VARCHAR(80) NOT NULL,
                    name VARCHAR(180) NOT NULL,
                    document VARCHAR(40),
                    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    INDEX idx_tenants_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS tenant_stores (
                    tenant_id VARCHAR(80) NOT NULL,
                    id VARCHAR(80) NOT NULL,
                    name VARCHAR(180) NOT NULL,
                    source_id VARCHAR(120),
                    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (tenant_id, id),
                    INDEX idx_tenant_stores_source (tenant_id, source_id),
                    INDEX idx_tenant_stores_status (tenant_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS tenant_devices (
                    tenant_id VARCHAR(80) NOT NULL,
                    store_id VARCHAR(80) NOT NULL,
                    id VARCHAR(120) NOT NULL,
                    name VARCHAR(180),
                    source_id VARCHAR(120),
                    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                    last_seen_at DATETIME NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_tenant_devices_source (tenant_id, store_id, source_id),
                    INDEX idx_tenant_devices_seen (tenant_id, store_id, last_seen_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS activation_codes (
                    code VARCHAR(20) NOT NULL,
                    tenant_id VARCHAR(80) NOT NULL,
                    store_id VARCHAR(80) NOT NULL,
                    store_name VARCHAR(180) NOT NULL,
                    source_id VARCHAR(120),
                    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                    expires_at DATETIME NOT NULL,
                    used_at DATETIME NULL,
                    used_device_id VARCHAR(120) NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (code),
                    INDEX idx_activation_codes_scope (tenant_id, store_id, status),
                    INDEX idx_activation_codes_expires (status, expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS sync_runs (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(80) NOT NULL DEFAULT 'legacy',
                    store_id VARCHAR(80) NOT NULL DEFAULT 'WEB',
                    device_id VARCHAR(120) NULL,
                    source_id VARCHAR(120) NOT NULL,
                    mode VARCHAR(20) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    generated_at DATETIME NULL,
                    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    finished_at DATETIME NULL,
                    total_rows INT NOT NULL DEFAULT 0,
                    table_counts_json LONGTEXT,
                    message TEXT,
                    PRIMARY KEY (id),
                    INDEX idx_sync_runs_received_at (received_at),
                    INDEX idx_sync_runs_scope (tenant_id, store_id, received_at),
                    INDEX idx_sync_runs_source (tenant_id, store_id, source_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS web_change_outbox (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(80) NOT NULL,
                    store_id VARCHAR(80) NOT NULL,
                    source_id VARCHAR(120) NOT NULL DEFAULT 'WEB',
                    target_source_id VARCHAR(120) NULL,
                    target_device_id VARCHAR(120) NULL,
                    entity_type VARCHAR(40) NOT NULL,
                    entity_id VARCHAR(120) NOT NULL,
                    operation VARCHAR(80) NOT NULL,
                    contract_version VARCHAR(30) NOT NULL DEFAULT '2026-07-02',
                    payload_json LONGTEXT NOT NULL,
                    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
                    attempts INT NOT NULL DEFAULT 0,
                    error_count INT NOT NULL DEFAULT 0,
                    last_error VARCHAR(500) NULL,
                    next_attempt_at DATETIME NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    delivered_at DATETIME NULL,
                    acknowledged_at DATETIME NULL,
                    dead_letter_at DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_web_change_outbox_pull (tenant_id, store_id, status, next_attempt_at, id),
                    INDEX idx_web_change_outbox_target (tenant_id, store_id, target_source_id, target_device_id, status, id),
                    INDEX idx_web_change_outbox_entity (tenant_id, store_id, entity_type, entity_id, id),
                    INDEX idx_web_change_outbox_created (tenant_id, store_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS scoped_sequences (
                    tenant_id VARCHAR(80) NOT NULL,
                    store_id VARCHAR(80) NOT NULL,
                    sequence_name VARCHAR(80) NOT NULL,
                    next_value INT NOT NULL DEFAULT 1,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (tenant_id, store_id, sequence_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                scopedTable("users", """
                    username VARCHAR(80) NOT NULL,
                    password VARCHAR(120) NOT NULL,
                    display_name VARCHAR(140) NOT NULL,
                    role VARCHAR(30) NOT NULL DEFAULT 'OPERATOR',
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    PRIMARY KEY (tenant_id, store_id, username),
                    INDEX idx_users_login (tenant_id, username, active),
                    INDEX idx_users_active (tenant_id, store_id, active)
                """),
                scopedTable("suppliers", """
                    id INT NOT NULL,
                    name VARCHAR(180) NOT NULL,
                    cnpj VARCHAR(30),
                    phone VARCHAR(40),
                    email VARCHAR(180),
                    address VARCHAR(255),
                    created_at DATETIME NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_suppliers_name (tenant_id, store_id, name)
                """),
                scopedTable("clients", """
                    id INT NOT NULL,
                    name VARCHAR(180) NOT NULL,
                    cpf_cnpj VARCHAR(30),
                    phone VARCHAR(40),
                    email VARCHAR(180),
                    address VARCHAR(255),
                    created_at DATETIME NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_clients_name (tenant_id, store_id, name)
                """),
                scopedTable("products", """
                    code VARCHAR(80) NOT NULL,
                    description VARCHAR(255) NOT NULL,
                    unit VARCHAR(20) NOT NULL DEFAULT 'UN',
                    price DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    stock DECIMAL(15,3) NOT NULL DEFAULT 0.000,
                    supplier_id INT NULL,
                    min_stock DECIMAL(15,3) NOT NULL DEFAULT 0.000,
                    PRIMARY KEY (tenant_id, store_id, code),
                    INDEX idx_products_description (tenant_id, store_id, description),
                    INDEX idx_products_supplier (tenant_id, store_id, supplier_id)
                """),
                scopedTable("stock_movements", """
                    id INT NOT NULL,
                    product_code VARCHAR(80) NOT NULL,
                    type VARCHAR(30) NOT NULL,
                    quantity DECIMAL(15,3) NOT NULL,
                    reason VARCHAR(255),
                    user VARCHAR(80),
                    created_at DATETIME NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_stock_movements_product (tenant_id, store_id, product_code),
                    INDEX idx_stock_movements_created_at (tenant_id, store_id, created_at)
                """),
                scopedTable("cash_sessions", """
                    id INT NOT NULL,
                    cash_id VARCHAR(40) NOT NULL,
                    operator VARCHAR(140) NOT NULL,
                    opening_balance DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    observation TEXT,
                    opened_at DATETIME NULL,
                    closed_at DATETIME NULL,
                    is_open BOOLEAN NOT NULL DEFAULT TRUE,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_cash_sessions_cash_open (tenant_id, store_id, cash_id, is_open),
                    INDEX idx_cash_sessions_opened_at (tenant_id, store_id, opened_at)
                """),
                scopedTable("cash_movements", """
                    id INT NOT NULL,
                    session_id INT NOT NULL,
                    type VARCHAR(30) NOT NULL,
                    value DECIMAL(15,2) NOT NULL,
                    observation VARCHAR(255),
                    date_time DATETIME NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_cash_movements_session (tenant_id, store_id, session_id)
                """),
                scopedTable("financial_entries", """
                    id INT NOT NULL,
                    type VARCHAR(20) NOT NULL,
                    category VARCHAR(120) NOT NULL,
                    description VARCHAR(255) NOT NULL,
                    amount DECIMAL(15,2) NOT NULL,
                    entry_date DATE NOT NULL,
                    status VARCHAR(30) NOT NULL DEFAULT 'PENDENTE',
                    created_by VARCHAR(80) NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL,
                    origin VARCHAR(40) NOT NULL DEFAULT 'APPGESTAO',
                    notes VARCHAR(255) NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_financial_entries_date (tenant_id, store_id, entry_date),
                    INDEX idx_financial_entries_status (tenant_id, store_id, status, type)
                """),
                scopedTable("sales", """
                    id INT NOT NULL,
                    session_id INT NOT NULL,
                    operator VARCHAR(140) NOT NULL,
                    discount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    surcharge DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    payment_method VARCHAR(30),
                    amount_paid DECIMAL(15,2),
                    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
                    date_time DATETIME NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_sales_session (tenant_id, store_id, session_id),
                    INDEX idx_sales_date_time (tenant_id, store_id, date_time),
                    INDEX idx_sales_status (tenant_id, store_id, status)
                """),
                scopedTable("sale_items", """
                    id INT NOT NULL,
                    sale_id INT NOT NULL,
                    product_code VARCHAR(80) NOT NULL,
                    quantity DECIMAL(15,3) NOT NULL,
                    unit_price DECIMAL(15,2) NOT NULL,
                    discount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_sale_items_sale (tenant_id, store_id, sale_id),
                    INDEX idx_sale_items_product (tenant_id, store_id, product_code)
                """),
                scopedTable("sale_cancellations", """
                    id INT NOT NULL,
                    sale_id INT NOT NULL,
                    reason VARCHAR(255) NOT NULL,
                    cancelled_by VARCHAR(80) NOT NULL,
                    cancelled_at DATETIME NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_sale_cancellations_sale (tenant_id, store_id, sale_id)
                """),
                scopedTable("comandas", """
                    id INT NOT NULL,
                    nome_cliente VARCHAR(180) NOT NULL,
                    client_id INT NULL,
                    aberta BOOLEAN NOT NULL DEFAULT TRUE,
                    data_abertura DATETIME NULL,
                    data_fechamento DATETIME NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_comandas_aberta (tenant_id, store_id, aberta)
                """),
                scopedTable("comanda_itens", """
                    id INT NOT NULL,
                    comanda_id INT NOT NULL,
                    descricao VARCHAR(255) NOT NULL,
                    valor DECIMAL(15,2) NOT NULL,
                    is_produto BOOLEAN NOT NULL DEFAULT FALSE,
                    product_code VARCHAR(80) NULL,
                    quantidade DECIMAL(15,3) NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_comanda_itens_comanda (tenant_id, store_id, comanda_id)
                """),
                scopedTable("audit_log", """
                    id INT NOT NULL,
                    usuario VARCHAR(80),
                    acao VARCHAR(80) NOT NULL,
                    entity_type VARCHAR(80),
                    entity_id VARCHAR(80),
                    details TEXT,
                    created_at DATETIME NULL,
                    PRIMARY KEY (tenant_id, store_id, id),
                    INDEX idx_audit_log_created_at (tenant_id, store_id, created_at)
                """)
                ,
                """
                CREATE TABLE IF NOT EXISTS app_settings (
                    tenant_id VARCHAR(80) NOT NULL,
                    store_id VARCHAR(80) NOT NULL DEFAULT 'all',
                    setting_key VARCHAR(120) NOT NULL,
                    setting_value TEXT,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (tenant_id, store_id, setting_key),
                    INDEX idx_app_settings_scope (tenant_id, store_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS licenses (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(80) NOT NULL,
                    plan_name VARCHAR(80) NOT NULL DEFAULT 'LEGACY',
                    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
                    starts_at DATETIME NULL,
                    expires_at DATETIME NULL,
                    max_stores INT NOT NULL DEFAULT 0,
                    max_devices INT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    INDEX idx_licenses_tenant (tenant_id, status),
                    INDEX idx_licenses_expiration (status, expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS backup_runs (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(80) NOT NULL,
                    store_id VARCHAR(80) NOT NULL DEFAULT 'WEB',
                    device_id VARCHAR(120) NULL,
                    source_id VARCHAR(120) NULL,
                    status VARCHAR(30) NOT NULL DEFAULT 'WAITING',
                    total_rows INT NOT NULL DEFAULT 0,
                    file_name VARCHAR(255) NULL,
                    file_size_bytes BIGINT NOT NULL DEFAULT 0,
                    checksum_sha256 VARCHAR(64) NULL,
                    created_by VARCHAR(80) NULL,
                    backup_type VARCHAR(40) NOT NULL DEFAULT 'MANUAL',
                    file_path VARCHAR(500) NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    finished_at DATETIME NULL,
                    message TEXT,
                    PRIMARY KEY (id),
                    INDEX idx_backup_runs_scope (tenant_id, store_id, created_at),
                    INDEX idx_backup_runs_status (tenant_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS backup_restore_staging (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(80) NOT NULL,
                    store_id VARCHAR(80) NOT NULL DEFAULT 'WEB',
                    backup_id BIGINT NOT NULL,
                    status VARCHAR(30) NOT NULL DEFAULT 'STAGED',
                    total_rows INT NOT NULL DEFAULT 0,
                    tables_json LONGTEXT NULL,
                    warnings_json LONGTEXT NULL,
                    file_name VARCHAR(255) NULL,
                    checksum_sha256 VARCHAR(64) NULL,
                    requested_by VARCHAR(80) NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    applied_by VARCHAR(80) NULL,
                    applied_at DATETIME NULL,
                    message TEXT,
                    PRIMARY KEY (id),
                    INDEX idx_backup_restore_staging_scope (tenant_id, store_id, created_at),
                    INDEX idx_backup_restore_staging_backup (tenant_id, backup_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """
        );
    }

    private String scopedTable(String tableName, String body) {
        return """
                CREATE TABLE IF NOT EXISTS %s (
                    tenant_id VARCHAR(80) NOT NULL DEFAULT 'legacy',
                    store_id VARCHAR(80) NOT NULL DEFAULT 'WEB',
                    device_id VARCHAR(120) NULL,
                    source_id VARCHAR(120) NOT NULL DEFAULT 'WEB',
                %s
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.formatted(tableName, body.indent(4).stripTrailing());
    }

    private void migrateTenantScopedTables() {
        migrateSyncRuns();
        for (Map.Entry<String, List<String>> entry : SCOPED_PRIMARY_KEYS.entrySet()) {
            String tableName = entry.getKey();
            ensureScopeColumns(tableName);
            ensurePrimaryKey(tableName, entry.getValue());
        }
    }

    private void migrateSyncRuns() {
        ensureColumn("sync_runs", "tenant_id", "VARCHAR(80) NULL", "FIRST");
        ensureColumn("sync_runs", "store_id", "VARCHAR(80) NULL", "AFTER tenant_id");
        ensureColumn("sync_runs", "device_id", "VARCHAR(120) NULL", "AFTER store_id");
        if (!columnExists("sync_runs", "source_id")) {
            jdbcTemplate.execute("ALTER TABLE sync_runs ADD COLUMN source_id VARCHAR(120) NULL AFTER device_id");
        }
        jdbcTemplate.update("UPDATE sync_runs SET source_id = ? WHERE source_id IS NULL OR source_id = ''", "WEB");
        jdbcTemplate.update("UPDATE sync_runs SET tenant_id = ? WHERE tenant_id IS NULL OR tenant_id = ''", LEGACY_TENANT_ID);
        jdbcTemplate.update("UPDATE sync_runs SET store_id = source_id WHERE store_id IS NULL OR store_id = ''");
        jdbcTemplate.update("UPDATE sync_runs SET device_id = ? WHERE device_id IS NULL OR device_id = ''", LEGACY_DEVICE_ID);
        jdbcTemplate.execute("ALTER TABLE sync_runs MODIFY COLUMN tenant_id VARCHAR(80) NOT NULL DEFAULT 'legacy'");
        jdbcTemplate.execute("ALTER TABLE sync_runs MODIFY COLUMN store_id VARCHAR(80) NOT NULL DEFAULT 'WEB'");
        jdbcTemplate.execute("ALTER TABLE sync_runs MODIFY COLUMN device_id VARCHAR(120) NULL");
        jdbcTemplate.execute("ALTER TABLE sync_runs MODIFY COLUMN source_id VARCHAR(120) NOT NULL");
        jdbcTemplate.execute("ALTER TABLE sync_runs MODIFY COLUMN status VARCHAR(30) NOT NULL");
    }

    private void migrateDomainColumns() {
        ensureColumn("audit_log", "risk_level", "VARCHAR(30) NULL", "AFTER created_at");
        ensureColumn("audit_log", "previous_value", "TEXT NULL", "AFTER risk_level");
        ensureColumn("audit_log", "new_value", "TEXT NULL", "AFTER previous_value");
        ensureColumn("audit_log", "reason", "VARCHAR(255) NULL", "AFTER new_value");
        ensureColumn("audit_log", "origin", "VARCHAR(80) NULL", "AFTER reason");
        ensureColumn("audit_log", "ip_address", "VARCHAR(80) NULL", "AFTER origin");
        ensureColumn("audit_log", "user_role", "VARCHAR(40) NULL", "AFTER ip_address");

        ensureColumn("products", "cost_price", "DECIMAL(15,2) NOT NULL DEFAULT 0.00", "AFTER price");
        ensureColumn("products", "category", "VARCHAR(120) NULL", "AFTER supplier_id");
        ensureColumn("products", "barcode", "VARCHAR(80) NULL", "AFTER category");
        ensureColumn("products", "created_at", "DATETIME NULL", "AFTER barcode");
        ensureColumn("products", "ideal_stock", "DECIMAL(15,3) NOT NULL DEFAULT 0.000", "AFTER min_stock");
        ensureColumn("products", "active", "BOOLEAN NOT NULL DEFAULT TRUE", "AFTER ideal_stock");
        ensureColumn("products", "updated_at", "DATETIME NULL", "AFTER active");
        ensureColumn("products", "deleted_at", "DATETIME NULL", "AFTER updated_at");

        ensureColumn("clients", "birth_date", "DATE NULL", "AFTER created_at");
        ensureColumn("clients", "active", "BOOLEAN NOT NULL DEFAULT TRUE", "AFTER birth_date");
        ensureColumn("clients", "notes", "TEXT NULL", "AFTER active");
        ensureColumn("clients", "loyalty_points", "INT NOT NULL DEFAULT 0", "AFTER notes");
        ensureColumn("clients", "updated_at", "DATETIME NULL", "AFTER loyalty_points");
        ensureColumn("clients", "deleted_at", "DATETIME NULL", "AFTER updated_at");

        ensureColumn("users", "created_at", "DATETIME NULL", "AFTER active");
        ensureColumn("users", "updated_at", "DATETIME NULL", "AFTER created_at");
        ensureColumn("users", "last_login_at", "DATETIME NULL", "AFTER updated_at");
        ensureColumn("users", "permissions_json", "TEXT NULL", "AFTER last_login_at");

        ensureColumn("cash_sessions", "closing_balance", "DECIMAL(15,2) NULL", "AFTER opening_balance");
        ensureColumn("cash_sessions", "expected_balance", "DECIMAL(15,2) NULL", "AFTER closing_balance");
        ensureColumn("cash_sessions", "difference", "DECIMAL(15,2) NULL", "AFTER expected_balance");
        ensureColumn("cash_sessions", "closed_by", "VARCHAR(80) NULL", "AFTER closed_at");
        ensureColumn("cash_sessions", "close_reason", "VARCHAR(255) NULL", "AFTER closed_by");
        ensureColumn("cash_sessions", "status", "VARCHAR(30) NOT NULL DEFAULT 'OPEN'", "AFTER is_open");

        ensureColumn("stock_movements", "previous_stock", "DECIMAL(15,3) NULL", "AFTER quantity");
        ensureColumn("stock_movements", "new_stock", "DECIMAL(15,3) NULL", "AFTER previous_stock");
        ensureColumn("stock_movements", "origin", "VARCHAR(80) NULL", "AFTER new_stock");
        ensureColumn("stock_movements", "reference_type", "VARCHAR(80) NULL", "AFTER origin");
        ensureColumn("stock_movements", "reference_id", "VARCHAR(80) NULL", "AFTER reference_type");

        ensureColumn("financial_entries", "updated_at", "DATETIME NULL", "AFTER created_at");
        ensureColumn("financial_entries", "origin", "VARCHAR(40) NOT NULL DEFAULT 'APPGESTAO'", "AFTER updated_at");
        ensureColumn("financial_entries", "notes", "VARCHAR(255) NULL", "AFTER origin");
        ensureIndex("financial_entries", "idx_financial_entries_date", List.of("tenant_id", "store_id", "entry_date"));
        ensureIndex("financial_entries", "idx_financial_entries_status", List.of("tenant_id", "store_id", "status", "type"));

        ensureColumn("backup_runs", "file_size_bytes", "BIGINT NOT NULL DEFAULT 0", "AFTER file_name");
        ensureColumn("backup_runs", "checksum_sha256", "VARCHAR(64) NULL", "AFTER file_size_bytes");
        ensureColumn("backup_runs", "created_by", "VARCHAR(80) NULL", "AFTER checksum_sha256");
        ensureColumn("backup_runs", "backup_type", "VARCHAR(40) NOT NULL DEFAULT 'MANUAL'", "AFTER created_by");
        ensureColumn("backup_runs", "file_path", "VARCHAR(500) NULL", "AFTER backup_type");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS backup_restore_staging (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(80) NOT NULL,
                    store_id VARCHAR(80) NOT NULL DEFAULT 'WEB',
                    backup_id BIGINT NOT NULL,
                    status VARCHAR(30) NOT NULL DEFAULT 'STAGED',
                    total_rows INT NOT NULL DEFAULT 0,
                    tables_json LONGTEXT NULL,
                    warnings_json LONGTEXT NULL,
                    file_name VARCHAR(255) NULL,
                    checksum_sha256 VARCHAR(64) NULL,
                    requested_by VARCHAR(80) NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    applied_by VARCHAR(80) NULL,
                    applied_at DATETIME NULL,
                    message TEXT,
                    PRIMARY KEY (id),
                    INDEX idx_backup_restore_staging_scope (tenant_id, store_id, created_at),
                    INDEX idx_backup_restore_staging_backup (tenant_id, backup_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        ensureColumn("backup_restore_staging", "applied_by", "VARCHAR(80) NULL", "AFTER created_at");
        ensureColumn("backup_restore_staging", "applied_at", "DATETIME NULL", "AFTER applied_by");

        ensureColumn("web_change_outbox", "target_source_id", "VARCHAR(120) NULL", "AFTER source_id");
        ensureColumn("web_change_outbox", "target_device_id", "VARCHAR(120) NULL", "AFTER target_source_id");
        ensureColumn("web_change_outbox", "contract_version", "VARCHAR(30) NOT NULL DEFAULT '2026-07-02'", "AFTER operation");
        ensureColumn("web_change_outbox", "error_count", "INT NOT NULL DEFAULT 0", "AFTER attempts");
        ensureColumn("web_change_outbox", "next_attempt_at", "DATETIME NULL", "AFTER last_error");
        ensureColumn("web_change_outbox", "dead_letter_at", "DATETIME NULL", "AFTER acknowledged_at");

    }

    private void applyVersionedMigrations() {
        List<Migration> migrations = List.of(
                new Migration("2026063001", "performance indexes for panel pagination", ignored -> migrationPerformanceIndexes()),
                new Migration("2026063002", "sync reconciliation ledger", ignored -> migrationSyncReconciliation()),
                new Migration("2026070201", "web change outbox retry policy", ignored -> migrationWebChangeOutboxRetryPolicy()),
                new Migration("2026070202", "tenant period indexes for panel filters", ignored -> migrationTenantPeriodIndexes())
        );
        for (Migration migration : migrations) {
            if (migrationApplied(migration.version())) {
                continue;
            }
            migration.action().accept(this);
            jdbcTemplate.update("""
                    INSERT INTO schema_migrations (version, description)
                    VALUES (?, ?)
                    """, migration.version(), migration.description());
            log.info("Migração Zentrix aplicada: {} - {}", migration.version(), migration.description());
        }
    }

    private void migrationPerformanceIndexes() {
        ensureIndex("sales", "idx_sales_status_date_id", List.of("tenant_id", "store_id", "status", "date_time", "id"));
        ensureIndex("sales", "idx_sales_scope_date_id", List.of("tenant_id", "store_id", "date_time", "id"));
        ensureIndex("sale_items", "idx_sale_items_sale_product", List.of("tenant_id", "store_id", "sale_id", "product_code"));
        ensureIndex("sale_items", "idx_sale_items_product_sale", List.of("tenant_id", "store_id", "product_code", "sale_id"));
        ensureIndex("products", "idx_products_active_stock", List.of("tenant_id", "store_id", "active", "deleted_at", "stock"));
        ensureIndex("clients", "idx_clients_active_name", List.of("tenant_id", "store_id", "active", "name"));
        ensureIndex("cash_sessions", "idx_cash_sessions_status_dates", List.of("tenant_id", "store_id", "status", "opened_at", "closed_at"));
        ensureIndex("cash_movements", "idx_cash_movements_date", List.of("tenant_id", "store_id", "date_time"));
        ensureIndex("audit_log", "idx_audit_log_created_id", List.of("tenant_id", "store_id", "created_at", "id"));
        ensureIndex("sync_runs", "idx_sync_runs_status_received", List.of("tenant_id", "store_id", "status", "received_at", "id"));
    }

    private void migrationSyncReconciliation() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sync_reconciliation (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    run_id BIGINT NULL,
                    tenant_id VARCHAR(80) NOT NULL,
                    store_id VARCHAR(80) NOT NULL,
                    device_id VARCHAR(120) NULL,
                    source_id VARCHAR(120) NOT NULL,
                    mode VARCHAR(20) NOT NULL,
                    status VARCHAR(30) NOT NULL,
                    expected_tables_json LONGTEXT NULL,
                    received_tables_json LONGTEXT NULL,
                    missing_tables_json LONGTEXT NULL,
                    conflict_count INT NOT NULL DEFAULT 0,
                    conflict_details_json LONGTEXT NULL,
                    message TEXT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    resolved_at DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_sync_reconciliation_scope (tenant_id, store_id, created_at),
                    INDEX idx_sync_reconciliation_run (run_id),
                    INDEX idx_sync_reconciliation_status (tenant_id, store_id, status, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private void migrationWebChangeOutboxRetryPolicy() {
        ensureColumn("web_change_outbox", "target_source_id", "VARCHAR(120) NULL", "AFTER source_id");
        ensureColumn("web_change_outbox", "target_device_id", "VARCHAR(120) NULL", "AFTER target_source_id");
        ensureColumn("web_change_outbox", "contract_version", "VARCHAR(30) NOT NULL DEFAULT '2026-07-02'", "AFTER operation");
        ensureColumn("web_change_outbox", "error_count", "INT NOT NULL DEFAULT 0", "AFTER attempts");
        ensureColumn("web_change_outbox", "next_attempt_at", "DATETIME NULL", "AFTER last_error");
        ensureColumn("web_change_outbox", "dead_letter_at", "DATETIME NULL", "AFTER acknowledged_at");
        ensureIndex("web_change_outbox", "idx_web_change_outbox_retry", List.of("tenant_id", "store_id", "status", "next_attempt_at", "id"));
        ensureIndex("web_change_outbox", "idx_web_change_outbox_target", List.of("tenant_id", "store_id", "target_source_id", "target_device_id", "status", "id"));
        ensureColumn("sync_reconciliation", "conflict_details_json", "LONGTEXT NULL", "AFTER conflict_count");
    }

    private void migrationTenantPeriodIndexes() {
        ensureIndex("sales", "idx_sales_tenant_status_date_id", List.of("tenant_id", "status", "date_time", "id"));
        ensureIndex("sales", "idx_sales_tenant_date_id", List.of("tenant_id", "date_time", "id"));
        ensureIndex("cash_sessions", "idx_cash_sessions_tenant_opened_id", List.of("tenant_id", "opened_at", "id"));
        ensureIndex("cash_sessions", "idx_cash_sessions_tenant_closed_id", List.of("tenant_id", "closed_at", "id"));
        ensureIndex("cash_movements", "idx_cash_movements_tenant_date_id", List.of("tenant_id", "date_time", "id"));
        ensureIndex("financial_entries", "idx_financial_entries_tenant_date_id", List.of("tenant_id", "entry_date", "id"));
        ensureIndex("audit_log", "idx_audit_log_tenant_created_id", List.of("tenant_id", "created_at", "id"));
        ensureIndex("products", "idx_products_tenant_active_stock", List.of("tenant_id", "active", "deleted_at", "stock"));
        ensureIndex("clients", "idx_clients_tenant_active_name", List.of("tenant_id", "active", "name"));
    }

    private void ensureScopeColumns(String tableName) {
        ensureColumn(tableName, "tenant_id", "VARCHAR(80) NULL", "FIRST");
        ensureColumn(tableName, "store_id", "VARCHAR(80) NULL", "AFTER tenant_id");
        ensureColumn(tableName, "device_id", "VARCHAR(120) NULL", "AFTER store_id");
        if (!columnExists(tableName, "source_id")) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN source_id VARCHAR(120) NULL AFTER device_id");
        }
        String fallbackSource = lastKnownSourceId();
        jdbcTemplate.update("UPDATE `" + tableName + "` SET source_id = ? WHERE source_id IS NULL OR source_id = ''", fallbackSource);
        jdbcTemplate.update("UPDATE `" + tableName + "` SET tenant_id = ? WHERE tenant_id IS NULL OR tenant_id = ''", LEGACY_TENANT_ID);
        jdbcTemplate.update("UPDATE `" + tableName + "` SET store_id = source_id WHERE store_id IS NULL OR store_id = ''");
        jdbcTemplate.update("UPDATE `" + tableName + "` SET device_id = ? WHERE device_id IS NULL OR device_id = ''", LEGACY_DEVICE_ID);
        jdbcTemplate.execute("ALTER TABLE `" + tableName + "` MODIFY COLUMN tenant_id VARCHAR(80) NOT NULL DEFAULT 'legacy'");
        jdbcTemplate.execute("ALTER TABLE `" + tableName + "` MODIFY COLUMN store_id VARCHAR(80) NOT NULL DEFAULT 'WEB'");
        jdbcTemplate.execute("ALTER TABLE `" + tableName + "` MODIFY COLUMN device_id VARCHAR(120) NULL");
        jdbcTemplate.execute("ALTER TABLE `" + tableName + "` MODIFY COLUMN source_id VARCHAR(120) NOT NULL DEFAULT 'WEB'");
    }

    private void ensureColumn(String tableName, String columnName, String definition, String position) {
        if (!columnExists(tableName, columnName)) {
            jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD COLUMN " + columnName + " " + definition + " " + position);
        }
    }

    private void ensurePrimaryKey(String tableName, List<String> expectedColumns) {
        List<String> currentColumns = primaryKeyColumns(tableName);
        if (currentColumns.equals(expectedColumns)) {
            return;
        }
        String expectedSql = expectedColumns.stream()
                .map(column -> "`" + column + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String dropPrimary = currentColumns.isEmpty() ? "" : "DROP PRIMARY KEY, ";
        jdbcTemplate.execute("ALTER TABLE `" + tableName + "` " + dropPrimary + "ADD PRIMARY KEY (" + expectedSql + ")");
    }

    private boolean migrationApplied(String version) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM schema_migrations
                WHERE version = ?
                """, Integer.class, version);
        return count != null && count > 0;
    }

    private void ensureIndex(String tableName, String indexName, List<String> columns) {
        if (indexExists(tableName, indexName)) {
            return;
        }
        String columnSql = columns.stream()
                .map(column -> "`" + column + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        jdbcTemplate.execute("ALTER TABLE `" + tableName + "` ADD INDEX `" + indexName + "` (" + columnSql + ")");
    }

    private void seedTenantMetadataFromExistingData() {
        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, status)
                SELECT DISTINCT tenant_id, IF(tenant_id = 'legacy', 'Cliente legado', tenant_id), 'ACTIVE'
                FROM sync_runs
                WHERE tenant_id IS NOT NULL AND tenant_id <> ''
                ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP
                """);
        jdbcTemplate.update("""
                INSERT INTO tenant_stores (tenant_id, id, name, source_id, status)
                SELECT tenant_id, store_id, store_id, MAX(source_id), 'ACTIVE'
                FROM sync_runs
                WHERE tenant_id IS NOT NULL AND tenant_id <> ''
                  AND store_id IS NOT NULL AND store_id <> ''
                GROUP BY tenant_id, store_id
                ON DUPLICATE KEY UPDATE
                    source_id = VALUES(source_id),
                    updated_at = CURRENT_TIMESTAMP
                """);
        jdbcTemplate.update("""
                INSERT INTO tenant_devices (tenant_id, store_id, id, name, source_id, status, last_seen_at)
                SELECT tenant_id, store_id, COALESCE(NULLIF(device_id, ''), 'legacy-device'),
                       COALESCE(NULLIF(device_id, ''), 'legacy-device'), MAX(source_id), 'ACTIVE', MAX(received_at)
                FROM sync_runs
                WHERE tenant_id IS NOT NULL AND tenant_id <> ''
                  AND store_id IS NOT NULL AND store_id <> ''
                GROUP BY tenant_id, store_id, COALESCE(NULLIF(device_id, ''), 'legacy-device')
                ON DUPLICATE KEY UPDATE
                    source_id = VALUES(source_id),
                    last_seen_at = VALUES(last_seen_at),
                    updated_at = CURRENT_TIMESTAMP
                """);
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, Integer.class, tableName, indexName);
        return count != null && count > 0;
    }

    private List<String> primaryKeyColumns(String tableName) {
        return jdbcTemplate.query("""
                SELECT column_name
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = 'PRIMARY'
                ORDER BY ordinal_position
                """, (rs, rowNum) -> rs.getString(1), tableName);
    }

    private String lastKnownSourceId() {
        List<String> rows = new ArrayList<>(jdbcTemplate.query("""
                SELECT source_id
                FROM sync_runs
                WHERE source_id IS NOT NULL AND source_id <> ''
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getString(1)));
        return rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank() ? "WEB" : rows.get(0);
    }

    private record Migration(String version, String description, Consumer<WebDatabaseInitializer> action) {
    }
}
