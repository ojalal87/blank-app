package com.rootguard.app.helper;

// Root helper boundary. There is deliberately no method that accepts a command, path or script.
// call() takes one RgProtocol v1 request (JSON, at most 256 KiB) that is decoded into typed DTOs with
// unknown-field rejection and re-validated in the root process before anything runs.
interface IRootGuardHelper {
    // Returns {"protocolVersion":1,"bootId":"..."}; required before call().
    String handshake() = 1;
    String call(String requestJson) = 2;
}
