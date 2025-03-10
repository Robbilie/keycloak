/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.models.map.client;

import org.jboss.logging.Logger;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientModel.ClientUpdatedEvent;
import org.keycloak.models.ClientModel.SearchableFields;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;

import org.keycloak.models.map.storage.MapKeycloakTransaction;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.keycloak.models.map.storage.MapStorage;

import org.keycloak.models.map.storage.ModelCriteriaBuilder;
import org.keycloak.models.map.storage.ModelCriteriaBuilder.Operator;
import static org.keycloak.common.util.StackUtil.getShortStackTrace;
import org.keycloak.models.ClientScopeModel;
import static org.keycloak.models.map.common.MapStorageUtils.registerEntityForChanges;
import static org.keycloak.models.map.storage.QueryParameters.Order.ASCENDING;
import static org.keycloak.models.map.storage.QueryParameters.withCriteria;

import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import java.util.HashSet;

public class MapClientProvider<K> implements ClientProvider {

    private static final Logger LOG = Logger.getLogger(MapClientProvider.class);
    private final KeycloakSession session;
    final MapKeycloakTransaction<K, MapClientEntity<K>, ClientModel> tx;
    private final MapStorage<K, MapClientEntity<K>, ClientModel> clientStore;
    private final ConcurrentMap<K, ConcurrentMap<String, Integer>> clientRegisteredNodesStore;

    public MapClientProvider(KeycloakSession session, MapStorage<K, MapClientEntity<K>, ClientModel> clientStore, ConcurrentMap<K, ConcurrentMap<String, Integer>> clientRegisteredNodesStore) {
        this.session = session;
        this.clientStore = clientStore;
        this.clientRegisteredNodesStore = clientRegisteredNodesStore;
        this.tx = clientStore.createTransaction(session);
        session.getTransactionManager().enlist(tx);
    }

    private ClientUpdatedEvent clientUpdatedEvent(ClientModel c) {
        return new ClientModel.ClientUpdatedEvent() {
            @Override
            public ClientModel getUpdatedClient() {
                return c;
            }

            @Override
            public KeycloakSession getKeycloakSession() {
                return session;
            }
        };
    }

    private <T extends MapClientEntity<K>> Function<T, ClientModel> entityToAdapterFunc(RealmModel realm) {
        // Clone entity before returning back, to avoid giving away a reference to the live object to the caller

        return (T origEntity) -> new MapClientAdapter<K>(session, realm, registerEntityForChanges(tx, origEntity)) {
            @Override
            public String getId() {
                return clientStore.getKeyConvertor().keyToString(entity.getId());
            }

            @Override
            public void updateClient() {
                LOG.tracef("updateClient(%s)%s", realm, origEntity.getId(), getShortStackTrace());
                session.getKeycloakSessionFactory().publish(clientUpdatedEvent(this));
            }

            /** This is runtime information and should have never been part of the adapter */
            @Override
            public Map<String, Integer> getRegisteredNodes() {
                return clientRegisteredNodesStore.computeIfAbsent(entity.getId(), k -> new ConcurrentHashMap<>());
            }

            @Override
            public void registerNode(String nodeHost, int registrationTime) {
                Map<String, Integer> value = getRegisteredNodes();
                value.put(nodeHost, registrationTime);
            }

            @Override
            public void unregisterNode(String nodeHost) {
                getRegisteredNodes().remove(nodeHost);
            }

        };
    }

    private Predicate<MapClientEntity<K>> entityRealmFilter(RealmModel realm) {
        if (realm == null || realm.getId() == null) {
            return c -> false;
        }
        String realmId = realm.getId();
        return entity -> Objects.equals(realmId, entity.getRealmId());
    }

    @Override
    public Stream<ClientModel> getClientsStream(RealmModel realm, Integer firstResult, Integer maxResults) {
        ModelCriteriaBuilder<ClientModel> mcb = clientStore.createCriteriaBuilder()
                .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId());

        return tx.read(withCriteria(mcb).pagination(firstResult, maxResults, SearchableFields.CLIENT_ID))
            .map(entityToAdapterFunc(realm));
    }

    @Override
    public Stream<ClientModel> getClientsStream(RealmModel realm) {
        ModelCriteriaBuilder<ClientModel> mcb = clientStore.createCriteriaBuilder()
          .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId());

        return tx.read(withCriteria(mcb).orderBy(SearchableFields.CLIENT_ID, ASCENDING))
          .map(entityToAdapterFunc(realm));
    }

    @Override
    public ClientModel addClient(RealmModel realm, String id, String clientId) {
        final K entityId = id == null ? clientStore.getKeyConvertor().yieldNewUniqueKey() : clientStore.getKeyConvertor().fromString(id);

        if (clientId == null) {
            clientId = entityId.toString();
        }

        LOG.tracef("addClient(%s, %s, %s)%s", realm, id, clientId, getShortStackTrace());

        MapClientEntity<K> entity = new MapClientEntityImpl<>(entityId, realm.getId());
        entity.setClientId(clientId);
        entity.setEnabled(true);
        entity.setStandardFlowEnabled(true);
        if (tx.read(entity.getId()) != null) {
            throw new ModelDuplicateException("Client exists: " + id);
        }
        tx.create(entity);
        final ClientModel resource = entityToAdapterFunc(realm).apply(entity);

        // TODO: Sending an event should be extracted to store layer
        session.getKeycloakSessionFactory().publish((ClientModel.ClientCreationEvent) () -> resource);
        resource.updateClient();        // This is actualy strange contract - it should be the store code to call updateClient

        return resource;
    }

    @Override
    public Stream<ClientModel> getAlwaysDisplayInConsoleClientsStream(RealmModel realm) {
        return getClientsStream(realm)
                .filter(ClientModel::isAlwaysDisplayInConsole);
    }

    @Override
    public void removeClients(RealmModel realm) {
        LOG.tracef("removeClients(%s)%s", realm, getShortStackTrace());

        getClientsStream(realm)
          .map(ClientModel::getId)
          .collect(Collectors.toSet())  // This is necessary to read out all the client IDs before removing the clients
          .forEach(cid -> removeClient(realm, cid));
    }

    @Override
    public boolean removeClient(RealmModel realm, String id) {
        if (id == null) {
            return false;
        }

        LOG.tracef("removeClient(%s, %s)%s", realm, id, getShortStackTrace());

        // TODO: Sending an event (and client role removal) should be extracted to store layer
        final ClientModel client = getClientById(realm, id);
        if (client == null) return false;
        session.users().preRemove(realm, client);
        session.roles().removeRoles(client);

        session.getKeycloakSessionFactory().publish(new ClientModel.ClientRemovedEvent() {
            @Override
            public ClientModel getClient() {
                return client;
            }

            @Override
            public KeycloakSession getKeycloakSession() {
                return session;
            }
        });
        // TODO: ^^^^^^^ Up to here

        tx.delete(clientStore.getKeyConvertor().fromString(id));

        return true;
    }

    @Override
    public long getClientsCount(RealmModel realm) {
        ModelCriteriaBuilder<ClientModel> mcb = clientStore.createCriteriaBuilder()
          .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId());

        return this.clientStore.getCount(withCriteria(mcb));
    }

    @Override
    public ClientModel getClientById(RealmModel realm, String id) {
        if (id == null) {
            return null;
        }

        LOG.tracef("getClientById(%s, %s)%s", realm, id, getShortStackTrace());

        MapClientEntity<K> entity = tx.read(clientStore.getKeyConvertor().fromStringSafe(id));
        return (entity == null || ! entityRealmFilter(realm).test(entity))
          ? null
          : entityToAdapterFunc(realm).apply(entity);
    }

    @Override
    public ClientModel getClientByClientId(RealmModel realm, String clientId) {
        if (clientId == null) {
            return null;
        }
        LOG.tracef("getClientByClientId(%s, %s)%s", realm, clientId, getShortStackTrace());

        ModelCriteriaBuilder<ClientModel> mcb = clientStore.createCriteriaBuilder()
          .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId())
          .compare(SearchableFields.CLIENT_ID, Operator.ILIKE, clientId);

        return tx.read(withCriteria(mcb))
          .map(entityToAdapterFunc(realm))
          .findFirst()
          .orElse(null)
        ;
    }

    @Override
    public Stream<ClientModel> searchClientsByClientIdStream(RealmModel realm, String clientId, Integer firstResult, Integer maxResults) {
        if (clientId == null) {
            return Stream.empty();
        }

        ModelCriteriaBuilder<ClientModel> mcb = clientStore.createCriteriaBuilder()
          .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId())
          .compare(SearchableFields.CLIENT_ID, Operator.ILIKE, "%" + clientId + "%");

        return tx.read(withCriteria(mcb).pagination(firstResult, maxResults, SearchableFields.CLIENT_ID))
                .map(entityToAdapterFunc(realm));
    }

    @Override
    public Stream<ClientModel> searchClientsByAttributes(RealmModel realm, Map<String, String> attributes, Integer firstResult, Integer maxResults) {
        ModelCriteriaBuilder<ClientModel> mcb = clientStore.createCriteriaBuilder()
                .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId());

        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            mcb = mcb.compare(SearchableFields.ATTRIBUTE, Operator.EQ, entry.getKey(), entry.getValue());
        }

        return tx.read(withCriteria(mcb).pagination(firstResult, maxResults, SearchableFields.CLIENT_ID))
                .map(entityToAdapterFunc(realm));
    }

    @Override
    public void addClientScopes(RealmModel realm, ClientModel client, Set<ClientScopeModel> clientScopes, boolean defaultScope) {
        final String id = client.getId();
        MapClientEntity<K> entity = tx.read(clientStore.getKeyConvertor().fromString(id));

        if (entity == null) return;

        // Defaults to openid-connect
        String clientProtocol = client.getProtocol() == null ? OIDCLoginProtocol.LOGIN_PROTOCOL : client.getProtocol();

        LOG.tracef("addClientScopes(%s, %s, %s, %b)%s", realm, client, clientScopes, defaultScope, getShortStackTrace());

        Map<String, ClientScopeModel> existingClientScopes = getClientScopes(realm, client, true);
        existingClientScopes.putAll(getClientScopes(realm, client, false));

        clientScopes.stream()
                .filter(clientScope -> ! existingClientScopes.containsKey(clientScope.getName()))
                .filter(clientScope -> Objects.equals(clientScope.getProtocol(), clientProtocol))
                .forEach(clientScope -> entity.addClientScope(clientScope.getId(), defaultScope));
    }

    @Override
    public void removeClientScope(RealmModel realm, ClientModel client, ClientScopeModel clientScope) {
        final String id = client.getId();
        MapClientEntity<K> entity = tx.read(clientStore.getKeyConvertor().fromString(id));

        if (entity == null) return;

        LOG.tracef("removeClientScope(%s, %s, %s)%s", realm, client, clientScope, getShortStackTrace());

        entity.removeClientScope(clientScope.getId());
    }

    @Override
    public Map<String, ClientScopeModel> getClientScopes(RealmModel realm, ClientModel client, boolean defaultScopes) {
        final String id = client.getId();
        MapClientEntity<K> entity = tx.read(clientStore.getKeyConvertor().fromString(id));

        if (entity == null) return null;

        // Defaults to openid-connect
        String clientProtocol = client.getProtocol() == null ? OIDCLoginProtocol.LOGIN_PROTOCOL : client.getProtocol();

        LOG.tracef("getClientScopes(%s, %s, %b)%s", realm, client, defaultScopes, getShortStackTrace());

        return entity.getClientScopes(defaultScopes)
                .map(clientScopeId -> session.clientScopes().getClientScopeById(realm, clientScopeId))
                .filter(Objects::nonNull)
                .filter(clientScope -> Objects.equals(clientScope.getProtocol(), clientProtocol))
                .collect(Collectors.toMap(ClientScopeModel::getName, Function.identity()));
    }

    @Override
    public Map<ClientModel, Set<String>> getAllRedirectUrisOfEnabledClients(RealmModel realm) {
        ModelCriteriaBuilder<ClientModel> mcb = clientStore.createCriteriaBuilder()
          .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId())
          .compare(SearchableFields.ENABLED, Operator.EQ, Boolean.TRUE);

        try (Stream<MapClientEntity<K>> st = tx.read(withCriteria(mcb))) {
            return st
              .filter(mce -> mce.getRedirectUris() != null && ! mce.getRedirectUris().isEmpty())
              .collect(Collectors.toMap(
                mce -> entityToAdapterFunc(realm).apply(mce),
                mce -> new HashSet<>(mce.getRedirectUris()))
              );
        }
    }

    public void preRemove(RealmModel realm, RoleModel role) {
        ModelCriteriaBuilder<ClientModel> mcb = clientStore.createCriteriaBuilder()
          .compare(SearchableFields.REALM_ID, Operator.EQ, realm.getId())
          .compare(SearchableFields.SCOPE_MAPPING_ROLE, Operator.EQ, role.getId());
        try (Stream<MapClientEntity<K>> toRemove = tx.read(withCriteria(mcb))) {
            toRemove
                .map(clientEntity -> session.clients().getClientById(realm, clientEntity.getId().toString()))
                .filter(Objects::nonNull)
                .forEach(clientModel -> clientModel.deleteScopeMapping(role));
        }
    }

    @Override
    public void close() {
        
    }

}
