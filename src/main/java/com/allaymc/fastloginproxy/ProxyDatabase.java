package com.allaymc.fastloginproxy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class ProxyDatabase {

    private final Path dataDirectory;
    private Connection connection;

    public ProxyDatabase(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public void connect() throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dataDirectory.resolve("proxy-auth.db"));

        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS premium_profiles (
                    username TEXT PRIMARY KEY,
                    premium INTEGER NOT NULL DEFAULT 0,
                    last_verified_at INTEGER NOT NULL DEFAULT 0
                )
            """);
        }
    }

    public Connection getConnection() {
        return connection;
    }
}
