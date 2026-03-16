package com.allaymc.fastloginproxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

public class MojangLookupService {

    private final int timeoutMs;

    public MojangLookupService(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isPremiumName(String username) {
        try {
            String url = "https://api.mojang.com/users/profiles/minecraft/" + username;
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);

            int code = connection.getResponseCode();
            if (code != 200) {
                return false;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            return response.toString().contains("\"id\"");
        } catch (Exception e) {
            return false;
        }
    }
}
