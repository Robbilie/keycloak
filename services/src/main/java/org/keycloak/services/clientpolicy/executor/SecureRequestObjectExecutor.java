/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.services.clientpolicy.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.ws.rs.core.MultivaluedMap;

import org.jboss.logging.Logger;
import org.keycloak.OAuthErrorException;
import org.keycloak.common.util.Time;
import org.keycloak.models.KeycloakSession;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.endpoints.request.AuthorizationEndpointRequest;
import org.keycloak.protocol.oidc.endpoints.request.AuthzEndpointRequestParser;
import org.keycloak.protocol.oidc.utils.OIDCResponseType;
import org.keycloak.representations.idm.ClientPolicyExecutorConfigurationRepresentation;
import org.keycloak.services.Urls;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.AuthorizationRequestContext;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * @author <a href="mailto:takashi.norimatsu.ws@hitachi.com">Takashi Norimatsu</a>
 */
public class SecureRequestObjectExecutor implements ClientPolicyExecutorProvider<SecureRequestObjectExecutor.Configuration> {

    private static final Logger logger = Logger.getLogger(SecureRequestObjectExecutor.class);

    public static final String INVALID_REQUEST_OBJECT = "invalid_request_object";
    public static final Integer DEFAULT_AVAILABLE_PERIOD = Integer.valueOf(3600); // (sec) from FAPI 1.0 Advanced requirement

    private final KeycloakSession session;
    private Configuration configuration;

    public SecureRequestObjectExecutor(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void setupConfiguration(SecureRequestObjectExecutor.Configuration config) {
        if (config == null) {
            configuration = new Configuration();
            configuration.setVerifyNbf(Boolean.TRUE);
            configuration.setAvailablePeriod(DEFAULT_AVAILABLE_PERIOD);
        } else {
            configuration = config;
            if (config.isVerifyNbf() == null) {
                configuration.setVerifyNbf(Boolean.TRUE);
            }
            if (config.getAvailablePeriod() == null) {
                configuration.setAvailablePeriod(DEFAULT_AVAILABLE_PERIOD);
            }
        }
    }

    @Override
    public Class<Configuration> getExecutorConfigurationClass() {
        return Configuration.class;
    }

    public static class Configuration extends ClientPolicyExecutorConfigurationRepresentation {
        @JsonProperty("available-period")
        protected Integer availablePeriod;
        @JsonProperty("verify-nbf")
        protected Boolean verifyNbf;

        public Integer getAvailablePeriod() {
            return availablePeriod;
        }

        public void setAvailablePeriod(Integer availablePeriod) {
            this.availablePeriod = availablePeriod;
        }

        public Boolean isVerifyNbf() {
            return verifyNbf;
        }

        public void setVerifyNbf(Boolean verifyNbf) {
            this.verifyNbf = verifyNbf;
        }
    }

    @Override
    public String getProviderId() {
        return SecureRequestObjectExecutorFactory.PROVIDER_ID;
    }

    @Override
    public void executeOnEvent(ClientPolicyContext context) throws ClientPolicyException {
        switch (context.getEvent()) {
            case AUTHORIZATION_REQUEST:
                AuthorizationRequestContext authorizationRequestContext = (AuthorizationRequestContext)context;
                executeOnAuthorizationRequest(authorizationRequestContext.getparsedResponseType(),
                    authorizationRequestContext.getAuthorizationEndpointRequest(),
                    authorizationRequestContext.getRedirectUri(),
                    authorizationRequestContext.getRequestParameters());
                break;
            default:
                return;
        }
    }

    private void executeOnAuthorizationRequest(
            OIDCResponseType parsedResponseType,
            AuthorizationEndpointRequest request,
            String redirectUri,
            MultivaluedMap<String, String> params) throws ClientPolicyException {
        logger.trace("Authz Endpoint - authz request");

        if (params == null) {
            logger.trace("request parameter not exist.");
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST, "Missing parameters");
        }

        String requestParam = params.getFirst(OIDCLoginProtocol.REQUEST_PARAM);
        String requestUriParam = params.getFirst(OIDCLoginProtocol.REQUEST_URI_PARAM);

        // check whether whether request object exists
        if (requestParam == null && requestUriParam == null) {
            logger.trace("request object not exist.");
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST, "Missing parameter: 'request' or 'request_uri'");
        }

        JsonNode requestObject = (JsonNode)session.getAttribute(AuthzEndpointRequestParser.AUTHZ_REQUEST_OBJECT);

        // check whether request object exists
        if (requestObject == null || requestObject.isEmpty()) {
            logger.trace("request object not exist.");
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST, "Invalid parameter: : 'request' or 'request_uri'");
        }

        // check whether scope exists in both query parameter and request object
        if (params.getFirst(OIDCLoginProtocol.SCOPE_PARAM) == null && requestObject.get(OIDCLoginProtocol.SCOPE_PARAM) == null) {
            logger.trace("scope object not exist.");
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST, "Parameter 'scope' missing in the request parameters or in 'request' object");
        }

        // check whether "exp" claim exists
        if (requestObject.get("exp") == null) {
            logger.trace("exp claim not incuded.");
            throw new ClientPolicyException(INVALID_REQUEST_OBJECT, "Missing parameter in the 'request' object: exp");
        }

        // check whether request object not expired
        long exp = requestObject.get("exp").asLong();
        if (Time.currentTime() > exp) { // TODO: Time.currentTime() is int while exp is long...
            logger.trace("request object expired.");
            throw new ClientPolicyException(INVALID_REQUEST_OBJECT, "Request Expired");
        }

        // "nbf" check is not needed for FAPI-RW ID2 security profile
        // while needed for FAPI 1.0 Advanced security profile
        if (Optional.ofNullable(configuration.isVerifyNbf()).orElse(Boolean.FALSE).booleanValue()) {
            // check whether "nbf" claim exists
            if (requestObject.get("nbf") == null) {
                logger.trace("nbf claim not incuded.");
                throw new ClientPolicyException(INVALID_REQUEST_OBJECT, "Missing parameter in the 'request' object: nbf");
            }

            // check whether request object not yet being processed
            long nbf = requestObject.get("nbf").asLong();
            if (Time.currentTime() < nbf) { // TODO: Time.currentTime() is int while nbf is long...
                logger.trace("request object not yet being processed.");
                throw new ClientPolicyException(INVALID_REQUEST_OBJECT, "Request not yet being processed");
            }

            // check whether request object's available period is short
            int availablePeriod = Optional.ofNullable(configuration.getAvailablePeriod()).orElse(DEFAULT_AVAILABLE_PERIOD).intValue();
            if (exp - nbf > availablePeriod) {
                logger.trace("request object's available period is long.");
                throw new ClientPolicyException(INVALID_REQUEST_OBJECT, "Request's available period is long");
            }
        }

        // check whether "aud" claim exists
        List<String> aud = new ArrayList<String>();
        JsonNode audience = requestObject.get("aud");
        if (audience == null) {
            logger.trace("aud claim not incuded.");
            throw new ClientPolicyException(INVALID_REQUEST_OBJECT, "Missing parameter in the 'request' object: aud");
        }
        if (audience.isArray()) {
            for (JsonNode node : audience) aud.add(node.asText());
        } else {
            aud.add(audience.asText());
        }
        if (aud.isEmpty()) {
            logger.trace("aud claim not incuded.");
            throw new ClientPolicyException(INVALID_REQUEST_OBJECT, "Missing parameter value in the 'request' object: aud");
        }

        // check whether "aud" claim points to this keycloak as authz server
        String iss = Urls.realmIssuer(session.getContext().getUri().getBaseUri(), session.getContext().getRealm().getName());
        if (!aud.contains(iss)) {
            logger.trace("aud not points to the intended realm.");
            throw new ClientPolicyException(INVALID_REQUEST_OBJECT, "Invalid parameter in the 'request' object: aud");
        }

        // confirm whether all parameters in query string are included in the request object, and have the same values
        // argument "request" are parameters overridden by parameters in request object
        Optional<String> incorrectParam = AuthzEndpointRequestParser.KNOWN_REQ_PARAMS.stream()
                .filter(param -> params.containsKey(param))
                .filter(param -> !isSameParameterIncluded(param, params.getFirst(param), requestObject))
                .findFirst();
        if (incorrectParam.isPresent()) {
            logger.warnf("Parameter '%s' does not have same value in 'request' object and in request parameters", incorrectParam.get());
            throw new ClientPolicyException(OAuthErrorException.INVALID_REQUEST, "Invalid parameter. Parameters in 'request' object not matching with request parameters");
        }

        logger.trace("Passed.");
    }

    private boolean isSameParameterIncluded(String param, String value, JsonNode requestObject) {
        if (param.equals(OIDCLoginProtocol.REQUEST_PARAM) || param.equals(OIDCLoginProtocol.REQUEST_URI_PARAM)) return true;
        if (requestObject.hasNonNull(param)) return requestObject.get(param).asText().equals(value);
        return false;
    }

}