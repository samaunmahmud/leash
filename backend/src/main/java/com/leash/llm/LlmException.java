package com.leash.llm;

/** The model couldn't be used. Messages are safe to log: status codes and error kinds, never prompts or keys. */
public class LlmException extends RuntimeException {
    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
