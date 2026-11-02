package com.leash.demo;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Larder, the demo shop: static pages under /larder/ (src/main/resources/static/larder). This reads them from the
 * classpath, both for the demo agent "browsing" and for risk checks reading the real page server-side.
 */
@Component
public class LarderCatalogue {

    public record Product(String slug, String name, String description, String merchant, String category,
                          long priceMinor, String currency, String url) {}

    record Catalogue(String merchant, List<Product> products) {}

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{1,40}");
    private final List<Product> products;

    public LarderCatalogue() {
        try (InputStream in = new ClassPathResource("static/larder/products.json").getInputStream()) {
            this.products = JsonMapper.builder().build().readValue(in, Catalogue.class).products();
        } catch (IOException e) {
            throw new IllegalStateException("Larder catalogue missing", e);
        }
    }

    public List<Product> products() {
        return products;
    }

    public Product product(String slug) {
        return products.stream().filter(p -> p.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No Larder product " + slug));
    }

    /** The raw HTML of a product page. The slug is checked against a strict pattern, so no path tricks. */
    public Optional<String> page(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) return Optional.empty();
        var res = new ClassPathResource("static/larder/" + slug + ".html");
        if (!res.exists()) return Optional.empty();
        try (InputStream in = res.getInputStream()) {
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
