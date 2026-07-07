package io.synccache.server;

import io.synccache.protocol.RespValue;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Represents a command submitted to the command engine.
 *
 * @param args   the parsed RESP arguments (first element is the command name)
 * @param result a future that will be completed with the command's response
 */
public record CommandRequest(List<RespValue> args, CompletableFuture<RespValue> result) {
}
