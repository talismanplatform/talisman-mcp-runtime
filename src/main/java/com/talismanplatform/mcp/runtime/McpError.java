/*
 * Copyright 2026 Talisman Platform
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.talismanplatform.mcp.runtime;

/**
 * A JSON-RPC error answered to the client, with the HTTP status of the response.
 */
class McpError extends RuntimeException {

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    static final int HEADER_MISMATCH = -32020;
    static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    private final int code;
    private final int httpStatus;
    private final transient Object data;

    McpError(int code, String message, int httpStatus, Object data) {
        super(message, null, false, false);
        this.code = code;
        this.httpStatus = httpStatus;
        this.data = data;
    }

    static McpError invalidParams(String message) {
        return new McpError(INVALID_PARAMS, message, 200, null);
    }

    static McpError headerMismatch(String message) {
        return new McpError(HEADER_MISMATCH, message, 400, null);
    }

    int getCode() {
        return code;
    }

    int getHttpStatus() {
        return httpStatus;
    }

    Object getData() {
        return data;
    }
}
