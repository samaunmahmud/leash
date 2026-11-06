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
        applyFrom(env, DatabaseUrl::reachable);
    }

    /**
     * With {@code LEASH_DB_FALLBACK=memory} a database that doesn't answer is skipped and the in-memory one is used
     * instead. That keeps the hosted public demo up after Render's free Postgres expires (it resets hourly anyway).
     */
    static void applyFrom(Map<String, String> env, java.util.function.Predicate<Jdbc> reachable) {
        String explicit = env.get("LEASH_DB_URL");
        if (explicit != null && !explicit.isBlank()) return;
        Optional<Jdbc> parsed = parse(env.get("DATABASE_URL"));
        if (parsed.isPresent() && "memory".equalsIgnoreCase(String.valueOf(env.get("LEASH_DB_FALLBACK")).strip())
                && !reachable.test(parsed.get())) {
            // Logging isn't set up yet this early; AppConfig's in-memory warning follows once it is.
            System.err.println("WARN Leash: DATABASE_URL doesn't answer; LEASH_DB_FALLBACK=memory, so using the in-memory database.");
            return;
        }
        parsed.ifPresent(j -> {
            System.setProperty("LEASH_DB_URL", j.url());
            if (j.user() != null) System.setProperty("LEASH_DB_USER", j.user());
            System.setProperty("LEASH_DB_PASSWORD", j.password() == null ? "" : j.password());
        });
    }

    /** One quick connection attempt, at most about 5 seconds. */
    static boolean reachable(Jdbc j) {
        var props = new java.util.Properties();
        if (j.user() != null) props.setProperty("user", j.user());
        if (j.password() != null) props.setProperty("password", j.password());
        props.setProperty("connectTimeout", "5");
        props.setProperty("loginTimeout", "5");
        props.setProperty("socketTimeout", "5");
        // The driver directly, not DriverManager: this runs in main() before Spring, and inside the packaged jar
        // DriverManager may not see drivers loaded by the jar's class loader yet (seen on Render: it always fell back).
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            var driver = (java.sql.Driver) Class.forName("org.postgresql.Driver", true, cl).getDeclaredConstructor().newInstance();
            try (var c = driver.connect(j.url(), props)) {
                return c != null && c.isValid(3);
            }
        } catch (ReflectiveOperationException | java.sql.SQLException | RuntimeException e) {
            System.err.println("WARN Leash: database check failed: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).replaceAll("//[^/@ ]*@", "//***@")
                    + (e.getCause() != null ? " (" + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage() + ")" : ""));
            return false;
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
