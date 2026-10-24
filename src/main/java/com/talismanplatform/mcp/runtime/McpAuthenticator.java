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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.camel.CamelContext;
import org.apache.camel.spi.OAuthTokenValidationConfig;
import org.apache.camel.spi.OAuthTokenValidationFactory;
import org.apache.camel.spi.OAuthTokenValidationResult;

import com.talismanplatform.mcp.runtime.McpServerDefinition.Auth;

/**
 * Checks the bearer token of a request with Camel's {@link OAuthTokenValidationFactory} (camel-oauth): signature
 * against the issuer's JWKS (found through its OpenID configuration, cached), {@code iss}, {@code aud},
 * {@code exp}/{@code nbf}. Scopes come from the {@code scope} and {@code scp} claims.
 */
final class McpAuthenticator {

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

    private final OAuthTokenValidationFactory factory;
    private final OAuthTokenValidationConfig config;
    private final Auth auth;

    McpAuthenticator(CamelContext context, Auth auth) {
        this.auth = auth;
        this.factory = resolveFactory(context);
        String issuer = auth.issuer().endsWith("/") ? auth.issuer().substring(0, auth.issuer().length() - 1)
                : auth.issuer();
        this.config = new OAuthTokenValidationConfig()
                .setOidcDiscoveryUrl(issuer + "/.well-known/openid-configuration")
                .setExpectedIssuer(auth.issuer())
                .setExpectedAudience(auth.audience())
                .setRequireExpiration(true)
                // a plain-http issuer is a development setup (the validator warns about it)
                .setAllowInsecureHttp(auth.issuer().toLowerCase(Locale.ROOT).startsWith("http:"));
    }

    Auth getAuth() {
        return auth;
    }

    /**
     * @param authorization the {@code Authorization} header, may be {@code null}
     * @throws Unauthorized when the header is missing or the token is not valid
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
            result = factory.validateToken(config, parts[1]);
        } catch (RuntimeException e) {
            throw new Unauthorized("The token cannot be validated: " + e.getMessage(), true);
        }
        if (!result.isValid()) {
            throw new Unauthorized(result.getError() == null ? "Invalid token" : result.getError(), true);
        }
        Set<String> scopes = new LinkedHashSet<>(result.getScopes() == null ? List.of() : result.getScopes());
        addScopes(scopes, result.getClaim("scope"));
        addScopes(scopes, result.getClaim("scp"));
        Map<String, Object> claims = result.getClaims() == null ? Map.of() : result.getClaims();
        return new Caller(result.getSubject(), Set.copyOf(scopes), claims);
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
