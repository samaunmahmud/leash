package com.leash.api;

import com.leash.config.LeashProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Where PayPal sends the payer after approving or cancelling (the return_url / cancel_url in each order).
 * T02 version: a plain "you can close this tab" page; the dev endpoint captures. T04 captures ledger orders here.
 */
@RestController
@RequestMapping("/api/paypal")
public class PayPalReturnController {

    private final LeashProperties props;

    public PayPalReturnController(LeashProperties props) {
        this.props = props;
    }

    @GetMapping(value = "/return", produces = MediaType.TEXT_HTML_VALUE)
    public String approved() {
        return page("Approved in PayPal", "Leash will capture the payment. You can close this tab.");
    }

    @GetMapping(value = "/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public String cancelled() {
        return page("Cancelled", "Nothing was paid. You can close this tab.");
    }

    String page(String title, String body) {
        String app = HtmlUtils.htmlEscape(props.appUrl());
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>Leash · %s</title>
                <style>body{font:16px/1.5 system-ui,sans-serif;background:#0e0a0c;color:#f6eff1;display:grid;place-items:center;min-height:100vh;margin:0;padding:16px}
                main{max-width:420px;border:1px solid #2a1f23;background:#171113;border-radius:16px;padding:28px}
                h1{font-size:20px;margin:0 0 8px}p{color:#c9bcc0;margin:0 0 16px}a{color:#e8668c}</style></head>
                <body><main><h1>%s</h1><p>%s</p><a href="%s/#/app">Back to Leash</a></main></body></html>
                """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(body), app);
    }
}
