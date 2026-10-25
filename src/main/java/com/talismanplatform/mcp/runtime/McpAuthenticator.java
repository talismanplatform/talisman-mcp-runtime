/*
 * Copyright 2026 Talisman Cloud Inc.
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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.CamelContext;
import org.apache.camel.spi.OAuthTokenValidationConfigResolver;
import org.apache.camel.spi.OAuthTokenValidationFactory;
import org.apache.camel.spi.OAuthTokenValidationResult;
import org.apache.camel.spi.OAuthTokenValidationResult.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.talismanplatform.mcp.runtime.McpServerDefinition.Auth;

/**
 * Validates bearer tokens with a camel-oauth named profile ({@code camel.oauth.<profile>.*}): signature, issuer,
 * audience, expiry and algorithms are camel-oauth's; the required scopes are checked by the endpoint.
 */
final class McpAuthenticator {

    private static final Logger LOG = LoggerFactory.getLogger(McpAuthenticator.class);

    /** The caller of an authenticated request. */
    record Caller(String subject, Set<String> scopes, Map<String, Object> claims) {

        boolean hasScopes(Collection<String> required) {
            return scopes.containsAll(required);
        }
    }

    /** A request without a usable token: 401 with {@code error="invalid_token"} when a token was sent. */
    static final class Unauthorized extends RuntimeException {
        private final boolean tokenPresent;

        Unauthorized(String message, boolean tokenPresent) {
            super(message, null, false, false);
            this.tokenPresent = tokenPresent;
        }

        boolean isTokenPresent() {
            return tokenPresent;
        }
    }

    /** The token could not be checked (the identity provider is unreachable, ...): 503. */
    static final class Unavailable extends RuntimeException {
        Unavailable() {
            super(null, null, false, false);
        }
    }

    private final CamelContext context;
    private final OAuthTokenValidationFactory factory;
    private final Auth auth;
    private final String issuer;

    /**
     * Checks the profile's configuration (camel-oauth reads the OpenID configuration of {@code base-uri}).
     *
     * @throws McpDefinitionException when the profile is incomplete or its identity provider cannot be read
     */
    McpAuthenticator(CamelContext context, Auth auth, String file) {
        this.context = context;
        this.auth = auth;
        String profile = auth.oauthProfile();
        String where = "$.auth.oauthProfile: camel.oauth." + profile + ".*: ";
        this.issuer = OAuthTokenValidationConfigResolver.resolveProfileConfig(context, profile).getExpectedIssuer();
        if (issuer == null) {
            throw new McpDefinitionException(file, List.of(where + "set base-uri or expected-issuer"));
        }
        this.factory = resolveFactory(context);
        try {
            factory.validateConfiguration(context, profile);
        } catch (RuntimeException e) {
            throw new McpDefinitionException(file, List.of(where + e.getMessage()));
        }
    }

    Auth getAuth() {
        return auth;
    }

    /** The profile's issuer: the authorization server of the protected resource metadata. */
    String getIssuer() {
        return issuer;
    }

    /**
     * @param authorization the {@code Authorization} header, may be {@code null}
     * @throws Unauthorized when the header is missing or the token is not valid
     * @throws Unavailable  when the token cannot be checked
     */
    Caller authenticate(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new Unauthorized("No bearer token", false);
        }
        String[] parts = authorization.trim().split("\\s+", 2);
        if (parts.length != 2 || !parts[0].equalsIgnoreCase("Bearer")) {
            throw new Unauthorized("The Authorization header is not a bearer token", false);
        }
        OAuthTokenValidationResult result;
        try {
            result = factory.validateToken(context, auth.oauthProfile(), parts[1]);
        } catch (RuntimeException e) {
            LOG.warn("Token validation with OAuth profile {} failed: {}", auth.oauthProfile(), e.toString());
            LOG.debug("Token validation failure", e);
            throw new Unavailable();
        }
        if (!result.isValid()) {
            LOG.debug("Token rejected: {}", result.getError());
            throw new Unauthorized(describe(result.getErrorCode()), true);
        }
        Set<String> scopes = new LinkedHashSet<>(result.getScopes() == null ? List.of() : result.getScopes());
        addScopes(scopes, result.getClaim("scope"));
        addScopes(scopes, result.getClaim("scp"));
        Map<String, Object> claims = result.getClaims() == null ? Map.of() : result.getClaims();
        return new Caller(result.getSubject(), Set.copyOf(scopes), claims);
    }

    /** The {@code error_description}: what is wrong, without the token's or the configuration's values. */
    static String describe(ErrorCode code) {
        if (code == null) {
            return "The token is not valid";
        }
        return switch (code) {
            case EXPIRED_TOKEN -> "The token has expired";
            case NOT_YET_VALID -> "The token is not valid yet";
            case MISSING_EXPIRATION -> "The token has no expiration";
            case INACTIVE_TOKEN -> "The token is not active";
            case INVALID_SIGNATURE, NO_MATCHING_KEY -> "The token's signature is not valid";
            case UNSUPPORTED_ALGORITHM -> "The token's signing algorithm is not allowed";
            case INVALID_ISSUER -> "The token is from another issuer";
            case INVALID_AUDIENCE -> "The token is for another audience";
            case INVALID_TOKEN -> "The token is not valid";
        };
    }

    private static void addScopes(Set<String> scopes, Object claim) {
        if (claim instanceof String text) {
            for (String scope : text.trim().split("\\s+")) {
                if (!scope.isEmpty()) {
                    scopes.add(scope);
                }
            }
        } else if (claim instanceof Collection<?> values) {
            values.forEach(value -> addScopes(scopes, value));
        }
    }

    private static OAuthTokenValidationFactory resolveFactory(CamelContext context) {
        return context.getCamelContextExtension().getBootstrapFactoryFinder()
                .newInstance(OAuthTokenValidationFactory.FACTORY, OAuthTokenValidationFactory.class)
                .orElseThrow(() -> new IllegalStateException(
                        "No " + OAuthTokenValidationFactory.FACTORY + " found: camel-oauth is missing"));
    }
}
