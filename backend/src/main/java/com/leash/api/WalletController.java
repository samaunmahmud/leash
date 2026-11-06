package com.leash.api;

import com.leash.vault.WalletService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Save the owner's PayPal once so approved purchases are paid with no popup (T11). */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService wallet;

    public WalletController(WalletService wallet) {
        this.wallet = wallet;
    }

    @GetMapping
    public WalletService.View get() {
        return wallet.view();
    }

    /** Returns the approve link to open once in the PayPal sandbox. */
    @com.leash.auth.OwnerOnly
    @PostMapping("/setup")
    public WalletService.View setup() {
        return wallet.start();
    }

    /** "I've approved it": finishes setup if PayPal's redirect didn't reach Leash. */
    @com.leash.auth.OwnerOnly
    @PostMapping("/complete")
    public WalletService.View complete() {
        return wallet.complete();
    }

    @com.leash.auth.OwnerOnly
    @DeleteMapping
    public WalletService.View forget() {
        return wallet.forget();
    }
}
