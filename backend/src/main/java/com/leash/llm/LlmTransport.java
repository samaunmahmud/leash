package com.leash.llm;

/**
 * Sends one system + user message pair to a chat model and returns the assistant's text. The only thing that talks
 * to the network, so tests swap in a lambda.
 */
@FunctionalInterface
public interface LlmTransport {

    /** @throws LlmException when the model can't be reached or answers with an error */
    String chat(String system, String user);
}
