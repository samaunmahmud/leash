package com.leash.api;

import com.leash.purchase.TugService;
import com.leash.purchase.TugService.TugView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/tugs")
public class TugController {

    private final TugService tugs;

    public TugController(TugService tugs) {
        this.tugs = tugs;
    }

    /** Pending tugs by default; {@code ?status=all} for history. */
    @GetMapping
    public List<TugView> list(@RequestParam(defaultValue = "PENDING") String status) {
        String s = status.toUpperCase(Locale.ROOT);
        if (!s.equals("ALL") && !s.equals("PENDING") && !s.equals("APPROVED") && !s.equals("DENIED")) {
            throw new IllegalArgumentException("status must be PENDING, APPROVED, DENIED or all");
        }
        return tugs.list(s.equals("ALL") ? null : s);
    }

    @com.leash.auth.DemoScoped(com.leash.auth.DemoScoped.Target.TUG)
    @PostMapping("/{id}/approve")
    public TugView approve(@PathVariable String id) {
        return tugs.approve(id);
    }

    @com.leash.auth.DemoScoped(com.leash.auth.DemoScoped.Target.TUG)
    @PostMapping("/{id}/deny")
    public TugView deny(@PathVariable String id) {
        return tugs.deny(id);
    }
}
