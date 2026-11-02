package com.leash.purchase;

import com.leash.config.LeashProperties;
import com.leash.demo.LarderCatalogue;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text of the page an agent is buying from, for the risk checks.
 *
 * <p>Agents may send an excerpt. For Larder's own pages Leash also reads the page itself, server-side: a hijacked
 * agent could simply leave the poisoned text out of its excerpt, but it can't stop Leash from reading the source.
 * (Fetching arbitrary third-party URLs is deliberately out of scope: that would make Leash an open proxy.)
 */
@Component
public class PageSource {

    private static final Pattern LARDER_PATH = Pattern.compile("^/larder/([a-z0-9-]{1,40})\\.html$");

    private final LarderCatalogue larder;
    private final String publicHost;

    public PageSource(LarderCatalogue larder, LeashProperties props) {
        this.larder = larder;
        this.publicHost = host(props.publicUrl());
    }

    public String textFor(String sourceUrl, String providedText) {
        String own = larderSlug(sourceUrl).flatMap(larder::page).orElse(null);
        if (own == null) return providedText;
        if (providedText == null || providedText.isBlank()) return own;
        return providedText + "\n\n" + own;
    }

    java.util.Optional<String> larderSlug(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) return java.util.Optional.empty();
        try {
            URI u = URI.create(sourceUrl.trim());
            // Relative paths, or absolute URLs on this backend's own host. Never someone else's site.
            if (u.getHost() != null && !Objects.equals(u.getHost().toLowerCase(), publicHost)) return java.util.Optional.empty();
            Matcher m = LARDER_PATH.matcher(u.getPath() == null ? "" : u.getPath());
            return m.matches() ? java.util.Optional.of(m.group(1)) : java.util.Optional.empty();
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    private static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
