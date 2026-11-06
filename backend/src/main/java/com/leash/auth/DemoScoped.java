package com.leash.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Anyone may call this for a demo leash's data; anything else needs the owner. {@link #value} says which path
 * variable names the target and what it is.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DemoScoped {

    Target value();

    enum Target {
        /** {@code {agentId}} */
        AGENT,
        /** {@code {id}} of a tug */
        TUG,
        /** {@code {id}} of a ledger entry */
        ENTRY
    }
}
