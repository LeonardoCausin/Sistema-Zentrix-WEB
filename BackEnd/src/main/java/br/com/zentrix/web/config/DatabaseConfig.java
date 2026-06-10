package br.com.zentrix.web.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class DatabaseConfig {

    @Bean
    @ConfigurationProperties("zentrix.database.web")
    public DatabaseSettings webDatabaseSettings() {
        return new DatabaseSettings();
    }

    @Bean
    @Primary
    public DataSource webDataSource(DatabaseSettings webDatabaseSettings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ZentrixWebPool");
        config.setJdbcUrl(webDatabaseSettings.jdbcUrl());
        config.setUsername(webDatabaseSettings.getUsername());
        config.setPassword(webDatabaseSettings.getPassword());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(3000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(1800000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean
    @Primary
    public JdbcTemplate webJdbcTemplate(@Qualifier("webDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Primary
    public PlatformTransactionManager webTransactionManager(@Qualifier("webDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    public static class DatabaseSettings {
        private String host = "localhost";
        private int port = 3306;
        private String name = "zentrix_web";
        private String username = "root";
        private String password = "";

        public String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + name
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo"
                    + "&connectTimeout=5000&socketTimeout=30000";
        }

        public String serverJdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Sao_Paulo"
                    + "&connectTimeout=5000&socketTimeout=30000";
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password == null ? "" : password;
        }
    }
}
