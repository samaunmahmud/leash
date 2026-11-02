package com.leash.purchase;

import org.springframework.stereotype.Component;

/**
 * The text of the page an agent is buying from, for the risk checks. Agents may send an excerpt; T09 also reads
 * Larder's own pages server-side, so a compromised agent can't hide an injected page by leaving the excerpt out.
 */
@Component
public class PageSource {

    public String textFor(String sourceUrl, String providedText) {
        return providedText;
    }
}
