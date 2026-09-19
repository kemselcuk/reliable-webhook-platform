package com.kemselcuk.webhook.domain;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class WebhookEndpointSecretUpgradeIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_endpoint_secret_upgrade_test")
            .withUsername("webhook_endpoint_secret_upgrade_test")
            .withPassword("webhook_endpoint_secret_upgrade_test");

    @Test
    void v6BackfillsExistingV5EndpointsAndEnforcesSecretConstraints() throws Exception {
        Flyway throughV5 = flyway(MigrationVersion.fromVersion("5"));
        throughV5.migrate();
        assertThat(throughV5.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("5"));
        assertThat(tableCount("webhook_endpoint_secrets")).isZero();

        UUID endpointId = UUID.randomUUID();
        insertPreV6Endpoint(endpointId);

        Flyway throughV6 = flyway(MigrationVersion.fromVersion("6"));
        throughV6.migrate();
        assertThat(throughV6.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("6"));

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) AS secret_count,
                            COUNT(*) FILTER (WHERE active) AS active_count,
                            MIN(octet_length(secret_material)) AS minimum_length,
                            MAX(octet_length(secret_material)) AS maximum_length
                     FROM webhook_endpoint_secrets
                     WHERE webhook_endpoint_id = ?
                     """)) {
            statement.setObject(1, endpointId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt("secret_count")).isEqualTo(1);
                assertThat(resultSet.getInt("active_count")).isEqualTo(1);
                assertThat(resultSet.getInt("minimum_length")).isEqualTo(32);
                assertThat(resultSet.getInt("maximum_length")).isEqualTo(32);
            }
        }

        assertThatThrownBy(() -> insertSecret(
                endpointId, 2, "v2-short", new byte[31], false
        )).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertSecret(
                endpointId, 3, "v3-long", new byte[513], false
        )).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertSecret(
                endpointId, 2, "v2-active", new byte[32], true
        )).isInstanceOf(SQLException.class);
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private void insertPreV6Endpoint(UUID endpointId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO webhook_endpoints (id, name, url, enabled)
                     VALUES (?, 'Pre V6 endpoint', 'https://receiver.example.test/hooks', TRUE)
                     """)) {
            statement.setObject(1, endpointId);
            statement.executeUpdate();
        }
    }

    private void insertSecret(
            UUID endpointId,
            int version,
            String keyId,
            byte[] material,
            boolean active
    ) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO webhook_endpoint_secrets (
                         id, webhook_endpoint_id, secret_version, key_id, secret_material, active
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, endpointId);
            statement.setInt(3, version);
            statement.setString(4, keyId);
            statement.setBytes(5, material);
            statement.setBoolean(6, active);
            statement.executeUpdate();
        }
    }

    private int tableCount(String tableName) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_name = ?
                     """)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
    }
}
