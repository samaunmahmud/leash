package com.leash.api;

import com.leash.auth.OwnerGate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** "Unlock owner mode": checks the passphrase once so the dashboard can remember it for this tab. */
@RestController
public class OwnerController {

    private final OwnerGate gate;

    public OwnerController(OwnerGate gate) {
        this.gate = gate;
    }

    /** 204 when right, 401 when wrong, 403 when no passphrase is set, 429 after too many guesses. */
    @PostMapping("/api/owner/check")
    public ResponseEntity<Void> check(@RequestHeader(value = OwnerGate.HEADER, required = false) String passphrase,
                                      HttpServletRequest req) {
        if (!gate.passphraseSet()) throw new ForbiddenException("No owner passphrase is set on this server.");
        if (!gate.guessAllowed(req)) throw new RateLimitedException("Too many passphrase attempts. Wait a few minutes.");
        if (!gate.matches(passphrase)) throw new OwnerRequiredException("That passphrase isn't right.");
        return ResponseEntity.noContent().build();
    }

    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    /** 401 with the owner-required code (ApiErrors maps it). */
    public static class OwnerRequiredException extends RuntimeException {
        public OwnerRequiredException(String message) {
            super(message);
        }
    }
}
