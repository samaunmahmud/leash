package com.leash.api;

/** The caller is known but not allowed to do this (403). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
