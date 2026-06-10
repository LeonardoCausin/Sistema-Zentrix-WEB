package br.com.zentrix.web.service;

import br.com.zentrix.web.config.DatabaseConfig.DatabaseSettings;
import jakarta.annotation.PostConstruct;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(WebDatabaseInitializer.class);

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
            log.warn("Banco web ainda nao esta pronto. A API sobe e tentara novamente na sincronizacao: {}", e.getMessage());
        }
    }

    public void ensureReady() {
        ensureValidIdentifier(settings.getName());
        ensureDatabaseExists();
        createTables();
    }

    private void ensureDatabaseExists() {
        try (var connection = DriverManager.getConnection(
                settings.serverJdbcUrl(),
                settings.getUsername(),
                settings.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + settings.getName()
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            throw new IllegalStateException("Nao foi possivel criar/acessar o banco web " + settings.getName(), e);
        }
    }

    private void createTables() {
        for (String ddl : schemaStatements()) {
            jdbcTemplate.execute(ddl);
        }
    }

    private void ensureValidIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Nome de banco invalido: " + value);
        }
    }

    private List<String> schemaStatements() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS sync_runs (
                    id BIGINT NOT NULL AUTO_INCREMENT,
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
                    INDEX idx_sync_runs_source (source_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS users (
                    username VARCHAR(80) NOT NULL,
                    password VARCHAR(120) NOT NULL,
                    display_name VARCHAR(140) NOT NULL,
                    role VARCHAR(30) NOT NULL DEFAULT 'OPERATOR',
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    PRIMARY KEY (username),
                    INDEX idx_users_active (active)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS suppliers (
                    id INT NOT NULL,
                    name VARCHAR(180) NOT NULL,
                    cnpj VARCHAR(30),
                    phone VARCHAR(40),
                    email VARCHAR(180),
                    address VARCHAR(255),
                    created_at DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_suppliers_name (name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS clients (
                    id INT NOT NULL,
                    name VARCHAR(180) NOT NULL,
                    cpf_cnpj VARCHAR(30),
                    phone VARCHAR(40),
                    email VARCHAR(180),
                    address VARCHAR(255),
                    created_at DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_clients_name (name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS products (
                    code VARCHAR(80) NOT NULL,
                    description VARCHAR(255) NOT NULL,
                    unit VARCHAR(20) NOT NULL DEFAULT 'UN',
                    price DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    stock DECIMAL(15,3) NOT NULL DEFAULT 0.000,
                    supplier_id INT NULL,
                    min_stock DECIMAL(15,3) NOT NULL DEFAULT 0.000,
                    PRIMARY KEY (code),
                    INDEX idx_products_description (description),
                    INDEX idx_products_supplier (supplier_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS stock_movements (
                    id INT NOT NULL,
                    product_code VARCHAR(80) NOT NULL,
                    type VARCHAR(30) NOT NULL,
                    quantity DECIMAL(15,3) NOT NULL,
                    reason VARCHAR(255),
                    user VARCHAR(80),
                    created_at DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_stock_movements_product (product_code),
                    INDEX idx_stock_movements_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS cash_sessions (
                    id INT NOT NULL,
                    cash_id VARCHAR(40) NOT NULL,
                    operator VARCHAR(140) NOT NULL,
                    opening_balance DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    observation TEXT,
                    opened_at DATETIME NULL,
                    closed_at DATETIME NULL,
                    is_open BOOLEAN NOT NULL DEFAULT TRUE,
                    PRIMARY KEY (id),
                    INDEX idx_cash_sessions_cash_open (cash_id, is_open),
                    INDEX idx_cash_sessions_opened_at (opened_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS cash_movements (
                    id INT NOT NULL,
                    session_id INT NOT NULL,
                    type VARCHAR(30) NOT NULL,
                    value DECIMAL(15,2) NOT NULL,
                    observation VARCHAR(255),
                    date_time DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_cash_movements_session (session_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS sales (
                    id INT NOT NULL,
                    session_id INT NOT NULL,
                    operator VARCHAR(140) NOT NULL,
                    discount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    surcharge DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    payment_method VARCHAR(30),
                    amount_paid DECIMAL(15,2),
                    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
                    date_time DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_sales_session (session_id),
                    INDEX idx_sales_date_time (date_time),
                    INDEX idx_sales_status (status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS sale_items (
                    id INT NOT NULL,
                    sale_id INT NOT NULL,
                    product_code VARCHAR(80) NOT NULL,
                    quantity DECIMAL(15,3) NOT NULL,
                    unit_price DECIMAL(15,2) NOT NULL,
                    discount DECIMAL(15,2) NOT NULL DEFAULT 0.00,
                    PRIMARY KEY (id),
                    INDEX idx_sale_items_sale (sale_id),
                    INDEX idx_sale_items_product (product_code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS sale_cancellations (
                    id INT NOT NULL,
                    sale_id INT NOT NULL,
                    reason VARCHAR(255) NOT NULL,
                    cancelled_by VARCHAR(80) NOT NULL,
                    cancelled_at DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_sale_cancellations_sale (sale_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS comandas (
                    id INT NOT NULL,
                    nome_cliente VARCHAR(180) NOT NULL,
                    client_id INT NULL,
                    aberta BOOLEAN NOT NULL DEFAULT TRUE,
                    data_abertura DATETIME NULL,
                    data_fechamento DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_comandas_aberta (aberta)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS comanda_itens (
                    id INT NOT NULL,
                    comanda_id INT NOT NULL,
                    descricao VARCHAR(255) NOT NULL,
                    valor DECIMAL(15,2) NOT NULL,
                    is_produto BOOLEAN NOT NULL DEFAULT FALSE,
                    product_code VARCHAR(80) NULL,
                    quantidade DECIMAL(15,3) NULL,
                    PRIMARY KEY (id),
                    INDEX idx_comanda_itens_comanda (comanda_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS audit_log (
                    id INT NOT NULL,
                    usuario VARCHAR(80),
                    acao VARCHAR(80) NOT NULL,
                    entity_type VARCHAR(80),
                    entity_id VARCHAR(80),
                    details TEXT,
                    created_at DATETIME NULL,
                    PRIMARY KEY (id),
                    INDEX idx_audit_log_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """
        );
    }
}
