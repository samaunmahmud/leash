package com.leash.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Small JDBC helpers shared by the repositories. Timestamps are always stored as UTC timestamptz. */
public final class Db {
    private Db() {}

    public static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    /** Sets are stored one value per line. */
    public static String lines(Collection<String> values) {
        if (values == null || values.isEmpty()) return "";
        return values.stream().sorted().collect(Collectors.joining("\n"));
    }

    public static Set<String> set(String lines) {
        if (lines == null || lines.isBlank()) return Set.of();
        return Arrays.stream(lines.split("\n")).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
}
