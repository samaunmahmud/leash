package com.leash.api;

import com.leash.mandate.MandateParser;
import com.leash.mandate.ParsedMandate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Plain English → proposed mandate for the composer. Saves nothing: the human confirms via POST /api/leashes. */
@RestController
@RequestMapping("/api/mandates")
public class MandateController {

    private final MandateParser parser;

    public MandateController(MandateParser parser) {
        this.parser = parser;
    }

    @com.leash.auth.OwnerOnly
    @PostMapping("/parse")
    public ParsedMandate parse(@Valid @RequestBody ParseRequest body) {
        return parser.parse(body.text());
    }

    public record ParseRequest(@NotBlank @Size(max = MandateParser.MAX_TEXT) String text) {}
}
