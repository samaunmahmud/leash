package com.leash.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Render (and Heroku-style hosts) hand out {@code DATABASE_URL=postgres://user:pass@host:port/db}; the JDBC driver
 * wants {@code jdbc:postgresql://host:port/db} with the user and password apart. Used only when {@code LEASH_DB_URL}
 * isn't set, so an explicit JDBC URL always wins.
 */
public final class DatabaseUrl {
    private DatabaseUrl() {}

    public record Jdbc(String url, String user, String password) {}

    public static Optional<Jdbc> parse(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) return Optional.empty();
        URI u;
        try {
            u = URI.create(databaseUrl.strip());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String scheme = u.getScheme();
        if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) return Optional.empty();
        if (u.getHost() == null || u.getPath() == null || u.getPath().length() < 2) return Optional.empty();
        String user = null, password = null;
        String info = u.getRawUserInfo();
        if (info != null) {
            int colon = info.indexOf(':');
            user = decode(colon < 0 ? info : info.substring(0, colon));
            password = colon < 0 ? null : decode(info.substring(colon + 1));
        }
        String url = "jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getRawPath()
                + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        return Optional.of(new Jdbc(url, user, password));
    }

    /**
     * Sets {@code LEASH_DB_URL/USER/PASSWORD} as system properties from {@code DATABASE_URL} when the JDBC ones aren't
     * in the environment. System properties outrank env vars in Spring, but these are only set when the env has none.
     */
    public static void applyFrom(Map<String, String> env) {
        String explicit = env.get("LEASH_DB_URL");
        if (explicit != null && !explicit.isBlank()) return;
        parse(env.get("DATABASE_URL")).ifPresent(j -> {
            System.setProperty("LEASH_DB_URL", j.url());
            if (j.user() != null) System.setProperty("LEASH_DB_USER", j.user());
            System.setProperty("LEASH_DB_PASSWORD", j.password() == null ? "" : j.password());
        });
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
