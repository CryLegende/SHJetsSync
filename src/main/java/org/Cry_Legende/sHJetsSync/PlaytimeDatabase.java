package org.Cry_Legende.sHJetsSync;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Table {@code player_afk_data (uuid, playtime)} de JetsAntiAFKPro, avec un pool à part : la connexion unique de
 * JetsAntiAFKPro, partagée sans verrou par ses fils, n'est jamais touchée.
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal.</p>
 */
final class PlaytimeDatabase {

    private final HikariDataSource pool;

    PlaytimeDatabase(String host, String port, String database, String user, String password, boolean ssl, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHJetsSync");
        hikari.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("sslMode", ssl ? "trust" : "disable");
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : JetsAntiAFKPro garde alors son fonctionnement seul
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Temps de jeu en base, en secondes ; 0 sans ligne, comme JetsAntiAFKPro. */
    int read(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT playtime FROM player_afk_data WHERE uuid = ?")) {
            st.setString(1, uuid.toString());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Valeur absolue, comme le REPLACE de JetsAntiAFKPro. */
    void write(UUID uuid, int seconds) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement st = c.prepareStatement(
                 "INSERT INTO player_afk_data (uuid, playtime) VALUES (?, ?) ON DUPLICATE KEY UPDATE playtime = VALUES(playtime)")) {
            st.setString(1, uuid.toString());
            st.setInt(2, seconds);
            st.executeUpdate();
        }
    }

    void close() {
        pool.close();
    }
}
