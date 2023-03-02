/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.kafka.schemaregistry.storage;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.SchemaProvider;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.rest.RestService;
import io.confluent.kafka.schemaregistry.client.rest.entities.Config;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaString;
import io.confluent.kafka.schemaregistry.client.rest.entities.SubjectVersion;
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.ConfigUpdateRequest;
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.ModeUpdateRequest;
import io.confluent.kafka.schemaregistry.client.rest.entities.requests.RegisterSchemaRequest;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.client.rest.utils.UrlList;
import io.confluent.kafka.schemaregistry.client.security.SslFactory;
import io.confluent.kafka.schemaregistry.exceptions.IdGenerationException;
import io.confluent.kafka.schemaregistry.exceptions.IncompatibleSchemaException;
import io.confluent.kafka.schemaregistry.exceptions.InvalidSchemaException;
import io.confluent.kafka.schemaregistry.exceptions.OperationNotPermittedException;
import io.confluent.kafka.schemaregistry.exceptions.ReferenceExistsException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryInitializationException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryRequestForwardingException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryStoreException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaRegistryTimeoutException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaTooLargeException;
import io.confluent.kafka.schemaregistry.exceptions.SchemaVersionNotSoftDeletedException;
import io.confluent.kafka.schemaregistry.exceptions.SubjectNotSoftDeletedException;
import io.confluent.kafka.schemaregistry.exceptions.UnknownLeaderException;
import io.confluent.kafka.schemaregistry.id.IdGenerator;
import io.confluent.kafka.schemaregistry.id.IncrementalIdGenerator;
import io.confluent.kafka.schemaregistry.json.JsonSchemaProvider;
import io.confluent.kafka.schemaregistry.leaderelector.kafka.KafkaGroupLeaderElector;
import io.confluent.kafka.schemaregistry.metrics.MetricsContainer;
import io.confluent.kafka.schemaregistry.protobuf.ProtobufSchemaProvider;
import io.confluent.kafka.schemaregistry.rest.SchemaRegistryConfig;
import io.confluent.kafka.schemaregistry.rest.VersionId;
import io.confluent.kafka.schemaregistry.storage.encoder.MetadataEncoderService;
import io.confluent.kafka.schemaregistry.storage.exceptions.EntryTooLargeException;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreException;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreInitializationException;
import io.confluent.kafka.schemaregistry.storage.exceptions.StoreTimeoutException;
import io.confluent.kafka.schemaregistry.storage.serialization.Serializer;
import io.confluent.kafka.schemaregistry.utils.QualifiedSubject;
import io.confluent.rest.RestConfig;
import io.confluent.rest.exceptions.RestException;
import io.confluent.rest.NamedURI;

import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.avro.reflect.Nullable;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static io.confluent.kafka.schemaregistry.client.rest.entities.Metadata.mergeMetadata;
import static io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet.mergeRuleSets;
import static io.confluent.kafka.schemaregistry.utils.QualifiedSubject.CONTEXT_DELIMITER;
import static io.confluent.kafka.schemaregistry.utils.QualifiedSubject.CONTEXT_PREFIX;
import static io.confluent.kafka.schemaregistry.utils.QualifiedSubject.CONTEXT_WILDCARD;
import static io.confluent.kafka.schemaregistry.utils.QualifiedSubject.DEFAULT_CONTEXT;

public class KafkaSchemaRegistry implements SchemaRegistry, LeaderAwareSchemaRegistry {

  /**
   * Schema versions under a particular subject are indexed from MIN_VERSION.
   */
  public static final int MIN_VERSION = 1;
  // Subject name under which global permissions are stored.
  public static final String GLOBAL_RESOURCE_NAME = "__GLOBAL";
  public static final int MAX_VERSION = Integer.MAX_VALUE;
  private static final Logger log = LoggerFactory.getLogger(KafkaSchemaRegistry.class);

  private final SchemaRegistryConfig config;
  private final Map<String, Object> props;
  private final LoadingCache<RawSchema, ParsedSchema> schemaCache;
  private final LookupCache<SchemaRegistryKey, SchemaRegistryValue> lookupCache;
  // visible for testing
  final KafkaStore<SchemaRegistryKey, SchemaRegistryValue> kafkaStore;
  private final MetadataEncoderService metadataEncoder;
  private RuleSetHandler ruleSetHandler;
  private final Serializer<SchemaRegistryKey, SchemaRegistryValue> serializer;
  private final SchemaRegistryIdentity myIdentity;
  private final CompatibilityLevel defaultCompatibilityLevel;
  private final Mode defaultMode;
  private final int kafkaStoreTimeoutMs;
  private final int initTimeout;
  private final int kafkaStoreMaxRetries;
  private final boolean isEligibleForLeaderElector;
  private final boolean delayLeaderElection;
  private final boolean allowModeChanges;
  private SchemaRegistryIdentity leaderIdentity;
  private RestService leaderRestService;
  private SslFactory sslFactory;
  private int leaderConnectTimeoutMs;
  private int leaderReadTimeoutMs;
  private IdGenerator idGenerator = null;
  private LeaderElector leaderElector = null;
  private final MetricsContainer metricsContainer;
  private final Map<String, SchemaProvider> providers;
  private final String kafkaClusterId;
  private final String groupId;
  private final List<Consumer<Boolean>> leaderChangeListeners = new CopyOnWriteArrayList<>();

  public KafkaSchemaRegistry(SchemaRegistryConfig config,
                             Serializer<SchemaRegistryKey, SchemaRegistryValue> serializer)
      throws SchemaRegistryException {
    if (config == null) {
      throw new SchemaRegistryException("Schema registry configuration is null");
    }
    this.config = config;
    this.props = new ConcurrentHashMap<>();
    Boolean leaderEligibility = config.getBoolean(SchemaRegistryConfig.MASTER_ELIGIBILITY);
    if (leaderEligibility == null) {
      leaderEligibility = config.getBoolean(SchemaRegistryConfig.LEADER_ELIGIBILITY);
    }
    this.isEligibleForLeaderElector = leaderEligibility;
    this.delayLeaderElection = config.getBoolean(SchemaRegistryConfig.LEADER_ELECTION_DELAY);
    this.allowModeChanges = config.getBoolean(SchemaRegistryConfig.MODE_MUTABILITY);

    String interInstanceListenerNameConfig = config.interInstanceListenerName();
    NamedURI internalListener = getInterInstanceListener(config.getListeners(),
        interInstanceListenerNameConfig,
        config.interInstanceProtocol());
    log.info("Found internal listener: " + internalListener.toString());
    SchemeAndPort schemeAndPort = new SchemeAndPort(internalListener.getUri().getScheme(),
        internalListener.getUri().getPort());
    // Use listener endpoint for identity when a matching named inter instance listener was found.
    // Default to existing behavior of using host name config and listener port otherwise.
    String internalListenerName = internalListener.getName();
    String host =   (internalListenerName != null
                      && internalListenerName.equals(interInstanceListenerNameConfig))
                    ? internalListener.getUri().getHost()
                    : config.getString(SchemaRegistryConfig.HOST_NAME_CONFIG);
    this.myIdentity = new SchemaRegistryIdentity(host, schemeAndPort.port,
        isEligibleForLeaderElector, schemeAndPort.scheme);
    log.info("Setting my identity to " + myIdentity.toString());

    Map<String, Object> sslConfig = config.getOverriddenSslConfigs(internalListener);
    this.sslFactory =
        new SslFactory(ConfigDef.convertToStringMapWithPasswordValues(sslConfig));
    this.leaderConnectTimeoutMs = config.getInt(SchemaRegistryConfig.LEADER_CONNECT_TIMEOUT_MS);
    this.leaderReadTimeoutMs = config.getInt(SchemaRegistryConfig.LEADER_READ_TIMEOUT_MS);
    this.kafkaStoreTimeoutMs =
        config.getInt(SchemaRegistryConfig.KAFKASTORE_TIMEOUT_CONFIG);
    this.initTimeout = config.getInt(SchemaRegistryConfig.KAFKASTORE_INIT_TIMEOUT_CONFIG);
    this.kafkaStoreMaxRetries =
        config.getInt(SchemaRegistryConfig.KAFKASTORE_WRITE_MAX_RETRIES_CONFIG);
    this.serializer = serializer;
    this.defaultCompatibilityLevel = config.compatibilityType();
    this.defaultMode = Mode.READWRITE;
    this.kafkaClusterId = kafkaClusterId(config);
    this.groupId = config.getString(SchemaRegistryConfig.SCHEMAREGISTRY_GROUP_ID_CONFIG);
    this.metricsContainer = new MetricsContainer(config, this.kafkaClusterId);
    this.providers = initProviders(config);
    this.schemaCache = CacheBuilder.newBuilder()
        .maximumSize(config.getInt(SchemaRegistryConfig.SCHEMA_CACHE_SIZE_CONFIG))
        .expireAfterAccess(
            config.getInt(SchemaRegistryConfig.SCHEMA_CACHE_EXPIRY_SECS_CONFIG), TimeUnit.SECONDS)
        .build(new CacheLoader<RawSchema, ParsedSchema>() {
          @Override
          public ParsedSchema load(RawSchema s) throws Exception {
            return loadSchema(s.getSchema(), s.isNew(), s.isNormalize());
          }
        });
    this.lookupCache = lookupCache();
    this.idGenerator = identityGenerator(config);
    this.kafkaStore = kafkaStore(config);
    this.metadataEncoder = new MetadataEncoderService(this);
    this.ruleSetHandler = new RuleSetHandler();
  }

  private Map<String, SchemaProvider> initProviders(SchemaRegistryConfig config) {
    Map<String, Object> schemaProviderConfigs =
        config.originalsWithPrefix(SchemaRegistryConfig.SCHEMA_PROVIDERS_CONFIG + ".");
    schemaProviderConfigs.put(SchemaProvider.SCHEMA_VERSION_FETCHER_CONFIG, this);
    List<SchemaProvider> defaultSchemaProviders = Arrays.asList(
        new AvroSchemaProvider(), new JsonSchemaProvider(), new ProtobufSchemaProvider()
    );
    for (SchemaProvider provider : defaultSchemaProviders) {
      provider.configure(schemaProviderConfigs);
    }
    Map<String, SchemaProvider> providerMap = new HashMap<>();
    registerProviders(providerMap, defaultSchemaProviders);
    List<SchemaProvider> customSchemaProviders =
        config.getConfiguredInstances(SchemaRegistryConfig.SCHEMA_PROVIDERS_CONFIG,
            SchemaProvider.class,
            schemaProviderConfigs);
    // Allow custom providers to override default providers
    registerProviders(providerMap, customSchemaProviders);
    metricsContainer.getCustomSchemaProviderCount().record(customSchemaProviders.size());
    return providerMap;
  }

  private void registerProviders(
      Map<String, SchemaProvider> providerMap,
      List<SchemaProvider> schemaProviders
  ) {
    for (SchemaProvider schemaProvider : schemaProviders) {
      log.info("Registering schema provider for {}: {}",
          schemaProvider.schemaType(),
          schemaProvider.getClass().getName()
      );
      providerMap.put(schemaProvider.schemaType(), schemaProvider);
    }
  }

  protected KafkaStore<SchemaRegistryKey, SchemaRegistryValue> kafkaStore(
      SchemaRegistryConfig config) throws SchemaRegistryException {
    return new KafkaStore<SchemaRegistryKey, SchemaRegistryValue>(
        config,
        getSchemaUpdateHandler(config),
        this.serializer, lookupCache, new NoopKey());
  }

  protected SchemaUpdateHandler getSchemaUpdateHandler(SchemaRegistryConfig config) {
    Map<String, Object> handlerConfigs =
        config.originalsWithPrefix(SchemaRegistryConfig.KAFKASTORE_UPDATE_HANDLERS_CONFIG + ".");
    handlerConfigs.put(StoreUpdateHandler.SCHEMA_REGISTRY, this);
    List<SchemaUpdateHandler> customSchemaHandlers =
        config.getConfiguredInstances(SchemaRegistryConfig.KAFKASTORE_UPDATE_HANDLERS_CONFIG,
            SchemaUpdateHandler.class,
            handlerConfigs);
    KafkaStoreMessageHandler storeHandler =
        new KafkaStoreMessageHandler(this, getLookupCache(), getIdentityGenerator());
    for (SchemaUpdateHandler customSchemaHandler : customSchemaHandlers) {
      log.info("Registering custom schema handler: {}",
          customSchemaHandler.getClass().getName()
      );
    }
    customSchemaHandlers.add(storeHandler);
    return new CompositeSchemaUpdateHandler(customSchemaHandlers);
  }

  protected LookupCache<SchemaRegistryKey, SchemaRegistryValue> lookupCache() {
    return new InMemoryCache<SchemaRegistryKey, SchemaRegistryValue>(serializer);
  }

  public LookupCache<SchemaRegistryKey, SchemaRegistryValue> getLookupCache() {
    return lookupCache;
  }

  public Serializer<SchemaRegistryKey, SchemaRegistryValue> getSerializer() {
    return serializer;
  }

  public MetadataEncoderService getMetadataEncoder() {
    return metadataEncoder;
  }

  public RuleSetHandler getRuleSetHandler() {
    return ruleSetHandler;
  }

  public void setRuleSetHandler(RuleSetHandler ruleSetHandler) {
    this.ruleSetHandler = ruleSetHandler;
  }

  protected IdGenerator identityGenerator(SchemaRegistryConfig config) {
    config.checkBootstrapServers();
    IdGenerator idGenerator = new IncrementalIdGenerator(this);
    idGenerator.configure(config);
    return idGenerator;
  }

  public IdGenerator getIdentityGenerator() {
    return idGenerator;
  }

  public MetricsContainer getMetricsContainer() {
    return metricsContainer;
  }

  /**
   * <p>This method returns a listener to be used for inter-instance communication.
   * It iterates through the list of listeners until it finds one whose name
   * matches the inter.instance.listener.name config. If no such listener is found,
   * it returns the last listener matching the requested scheme.
   * </p>
   * <p>When there is no matching named listener, in theory, any port from any listener
   * would be sufficient. Choosing the last, instead of say the first, is arbitrary.
   * The port used by this listener also forms the identity of the schema registry instance
   * along with the host name.
   * </p>
   */
  // TODO: once RestConfig.PORT_CONFIG is deprecated, remove the port parameter.
  public static NamedURI getInterInstanceListener(List<NamedURI> listeners,
                                             String interInstanceListenerName,
                                             String requestedScheme)
      throws SchemaRegistryException {
    if (requestedScheme.isEmpty()) {
      requestedScheme = SchemaRegistryConfig.HTTP;
    }

    NamedURI internalListener = null;
    for (NamedURI listener : listeners) {
      if (listener.getName() !=  null
              && listener.getName().equalsIgnoreCase(interInstanceListenerName)) {
        internalListener = listener;
        break;
      } else if (listener.getUri().getScheme().equalsIgnoreCase(requestedScheme)) {
        internalListener = listener;
      }
    }
    if (internalListener == null) {
      throw new SchemaRegistryException(" No listener configured with requested scheme "
                                          + requestedScheme);
    }
    return internalListener;
  }

  @Override
  public void init() throws SchemaRegistryException {
    try {
      kafkaStore.init();
    } catch (StoreInitializationException e) {
      throw new SchemaRegistryInitializationException(
          "Error initializing kafka store while initializing schema registry", e);
    }
    try {
      metadataEncoder.init();
    } catch (Exception e) {
      throw new SchemaRegistryInitializationException(
          "Error initializing metadata encoder while initializing schema registry", e);
    }

    config.checkBootstrapServers();
    if (!delayLeaderElection) {
      electLeader();
    }
  }

  public void postInit() throws SchemaRegistryException {
    if (delayLeaderElection) {
      electLeader();
    }
  }

  private void electLeader() throws SchemaRegistryException {
    log.info("Joining schema registry with Kafka-based coordination");
    leaderElector = new KafkaGroupLeaderElector(config, myIdentity, this);
    try {
      leaderElector.init();
    } catch (SchemaRegistryStoreException e) {
      throw new SchemaRegistryInitializationException(
          "Error electing leader while initializing schema registry", e);
    } catch (SchemaRegistryTimeoutException e) {
      throw new SchemaRegistryInitializationException(e);
    }
  }

  public void waitForInit() throws InterruptedException {
    kafkaStore.waitForInit();
  }

  public boolean initialized() {
    return kafkaStore.initialized();
  }

  /**
   * Add a leader change listener.
   *
   * @param listener a function that takes whether this node is a leader
   */
  public void addLeaderChangeListener(Consumer<Boolean> listener) {
    leaderChangeListeners.add(listener);
  }

  public boolean isLeader() {
    kafkaStore.leaderLock().lock();
    try {
      if (leaderIdentity != null && leaderIdentity.equals(myIdentity)) {
        return true;
      } else {
        return false;
      }
    } finally {
      kafkaStore.leaderLock().unlock();
    }
  }

  /**
   * 'Inform' this SchemaRegistry instance which SchemaRegistry is the current leader.
   * If this instance is set as the new leader, ensure it is up-to-date with data in
   * the kafka store.
   *
   * @param newLeader Identity of the current leader. null means no leader is alive.
   */
  @Override
  public void setLeader(@Nullable SchemaRegistryIdentity newLeader)
      throws SchemaRegistryTimeoutException, SchemaRegistryStoreException, IdGenerationException {
    log.debug("Setting the leader to " + newLeader);

    // Only schema registry instances eligible for leader can be set to leader
    if (newLeader != null && !newLeader.getLeaderEligibility()) {
      throw new IllegalStateException(
          "Tried to set an ineligible node to leader: " + newLeader);
    }

    boolean isLeader;
    boolean leaderChanged;
    kafkaStore.leaderLock().lock();
    try {
      final SchemaRegistryIdentity previousLeader = leaderIdentity;
      leaderIdentity = newLeader;

      if (leaderIdentity == null) {
        leaderRestService = null;
      } else {
        leaderRestService = new RestService(leaderIdentity.getUrl());
        leaderRestService.setHttpConnectTimeoutMs(leaderConnectTimeoutMs);
        leaderRestService.setHttpReadTimeoutMs(leaderReadTimeoutMs);
        if (sslFactory != null && sslFactory.sslContext() != null) {
          leaderRestService.setSslSocketFactory(sslFactory.sslContext().getSocketFactory());
          leaderRestService.setHostnameVerifier(getHostnameVerifier());
        }
      }

      isLeader = isLeader();
      leaderChanged = leaderIdentity != null && !leaderIdentity.equals(previousLeader);
      if (leaderChanged && isLeader) {
        // The new leader may not know the exact last offset in the Kafka log. So, mark the
        // last offset invalid here
        kafkaStore.markLastWrittenOffsetInvalid();
        //ensure the new leader catches up with the offsets before it gets nextid and assigns
        // leader
        try {
          kafkaStore.waitUntilKafkaReaderReachesLastOffset(initTimeout);
        } catch (StoreException e) {
          throw new SchemaRegistryStoreException("Exception getting latest offset ", e);
        }
        idGenerator.init();
      }
      metricsContainer.getLeaderNode().record(isLeader() ? 1 : 0);
    } finally {
      kafkaStore.leaderLock().unlock();
    }

    if (leaderChanged) {
      for (Consumer<Boolean> listener : leaderChangeListeners) {
        try {
          listener.accept(isLeader);
        } catch (Exception e) {
          log.error("Could not invoke leader change listener", e);
        }
      }
    }
  }

  /**
   * Return json data encoding basic information about this SchemaRegistry instance, such as
   * host, port, etc.
   */
  public SchemaRegistryIdentity myIdentity() {
    return myIdentity;
  }

  /**
   * Return the identity of the SchemaRegistry that this instance thinks is current leader.
   * Any request that requires writing new data gets forwarded to the leader.
   */
  public SchemaRegistryIdentity leaderIdentity() {
    kafkaStore.leaderLock().lock();
    try {
      return leaderIdentity;
    } finally {
      kafkaStore.leaderLock().unlock();
    }
  }

  public RestService leaderRestService() {
    return leaderRestService;
  }

  public Set<String> schemaTypes() {
    return providers.keySet();
  }

  public SchemaProvider schemaProvider(String schemaType) {
    return providers.get(schemaType);
  }

  @Override
  public int register(String subject,
                      Schema schema,
                      boolean normalize)
      throws SchemaRegistryException {
    try {
      checkRegisterMode(subject, schema);

      // Ensure cache is up-to-date before any potential writes
      kafkaStore.waitUntilKafkaReaderReachesLastOffset(subject, kafkaStoreTimeoutMs);

      int schemaId = schema.getId();
      ParsedSchema parsedSchema = canonicalizeSchema(schema, schemaId < 0, normalize);

      if (parsedSchema != null) {
        // see if the schema to be registered already exists
        SchemaIdAndSubjects schemaIdAndSubjects = this.lookupCache.schemaIdAndSubjects(schema);
        if (schemaIdAndSubjects != null
            && (schemaId < 0 || schemaId == schemaIdAndSubjects.getSchemaId())) {
          if (schemaIdAndSubjects.hasSubject(subject)
              && !isSubjectVersionDeleted(subject, schemaIdAndSubjects.getVersion(subject))) {
            // return only if the schema was previously registered under the input subject
            return schemaIdAndSubjects.getSchemaId();
          } else {
            // need to register schema under the input subject
            schemaId = schemaIdAndSubjects.getSchemaId();
          }
        }
      }

      // determine the latest version of the schema in the subject
      List<SchemaValue> allVersions = getAllSchemaValues(subject);
      Collections.reverse(allVersions);

      List<SchemaValue> deletedVersions = new ArrayList<>();
      List<ParsedSchema> undeletedVersions = new ArrayList<>();
      int newVersion = MIN_VERSION;
      for (SchemaValue schemaValue : allVersions) {
        newVersion = Math.max(newVersion, schemaValue.getVersion() + 1);
        if (schemaValue.isDeleted()) {
          deletedVersions.add(schemaValue);
        } else {
          ParsedSchema undeletedSchema = parseSchema(getSchemaEntityFromSchemaValue(schemaValue));
          if (parsedSchema != null
              && parsedSchema.references().isEmpty()
              && !undeletedSchema.references().isEmpty()
              && parsedSchema.deepEquals(undeletedSchema)) {
            // This handles the case where a schema is sent with all references resolved
            return schemaValue.getId();
          }
          undeletedVersions.add(undeletedSchema);
        }
      }
      Collections.reverse(undeletedVersions);

      Config config = getConfigInScope(subject);
      if (schemaId < 0) {
        parsedSchema = maybePopulateFromPrevious(config, schema, parsedSchema, undeletedVersions);
      }

      final List<String> compatibilityErrorLogs = isCompatibleWithPrevious(
              config, parsedSchema, undeletedVersions);
      final boolean isCompatible = compatibilityErrorLogs.isEmpty();

      maybeValidateAndNormalizeSchema(parsedSchema, schema, false, normalize);

      // see if the schema to be registered already exists, after population and re-normalization
      SchemaIdAndSubjects schemaIdAndSubjects = this.lookupCache.schemaIdAndSubjects(schema);
      if (schemaIdAndSubjects != null
          && (schemaId < 0 || schemaId == schemaIdAndSubjects.getSchemaId())) {
        if (schemaIdAndSubjects.hasSubject(subject)
            && !isSubjectVersionDeleted(subject, schemaIdAndSubjects.getVersion(subject))) {
          // return only if the schema was previously registered under the input subject
          return schemaIdAndSubjects.getSchemaId();
        } else {
          // need to register schema under the input subject
          schemaId = schemaIdAndSubjects.getSchemaId();
        }
      }

      Mode mode = getModeInScope(subject);
      if (isCompatible || mode == Mode.IMPORT) {
        // save the context key
        QualifiedSubject qs = QualifiedSubject.create(tenant(), subject);
        if (qs != null && !DEFAULT_CONTEXT.equals(qs.getContext())) {
          ContextKey contextKey = new ContextKey(qs.getTenant(), qs.getContext());
          if (kafkaStore.get(contextKey) == null) {
            ContextValue contextValue = new ContextValue(qs.getTenant(), qs.getContext());
            kafkaStore.put(contextKey, contextValue);
          }
        }

        // assign a guid and put the schema in the kafka store
        if (schema.getVersion() <= 0) {
          schema.setVersion(newVersion);
        } else if (newVersion != schema.getVersion() && mode != Mode.IMPORT) {
          throw new InvalidSchemaException("Version is not one more than previous version");
        }

        SchemaKey schemaKey = new SchemaKey(subject, schema.getVersion());
        SchemaValue schemaValue = new SchemaValue(schema, ruleSetHandler);
        metadataEncoder.encodeMetadata(schemaValue);
        if (schemaId >= 0) {
          checkIfSchemaWithIdExist(schemaId, schema);
          schema.setId(schemaId);
          schemaValue.setId(schemaId);
          kafkaStore.put(schemaKey, schemaValue);
        } else {
          int retries = 0;
          while (retries++ < kafkaStoreMaxRetries) {
            int newId = idGenerator.id(schemaValue);
            // Verify id is not already in use
            if (lookupCache.schemaKeyById(newId, subject) == null) {
              schema.setId(newId);
              schemaValue.setId(newId);
              if (retries > 1) {
                log.warn(String.format("Retrying to register the schema with ID %s", newId));
              }
              kafkaStore.put(schemaKey, schemaValue);
              break;
            }
          }
          if (retries >= kafkaStoreMaxRetries) {
            throw new SchemaRegistryStoreException("Error while registering the schema due "
                + "to generating an ID that is already in use.");
          }
        }
        for (SchemaValue deleted : deletedVersions) {
          if (deleted.getId().equals(schema.getId())
                  && deleted.getVersion().compareTo(schema.getVersion()) < 0) {
            // Tombstone previous version with the same ID
            SchemaKey key = new SchemaKey(deleted.getSubject(), deleted.getVersion());
            kafkaStore.put(key, null);
          }
        }

        return schema.getId();
      } else {
        throw new IncompatibleSchemaException(compatibilityErrorLogs.toString());
      }
    } catch (EntryTooLargeException e) {
      throw new SchemaTooLargeException("Write failed because schema is too large", e);
    } catch (StoreTimeoutException te) {
      throw new SchemaRegistryTimeoutException("Write to the Kafka store timed out while", te);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Error while registering the schema in the"
                                             + " backend Kafka store", e);
    }
  }

  private void checkRegisterMode(
      String subject, Schema schema
  ) throws OperationNotPermittedException, SchemaRegistryStoreException {
    if (isReadOnlyMode(subject)) {
      throw new OperationNotPermittedException("Subject " + subject + " is in read-only mode");
    }

    if (schema.getId() >= 0) {
      if (getModeInScope(subject) != Mode.IMPORT) {
        throw new OperationNotPermittedException("Subject " + subject + " is not in import mode");
      }
    } else {
      if (getModeInScope(subject) != Mode.READWRITE) {
        throw new OperationNotPermittedException(
            "Subject " + subject + " is not in read-write mode"
        );
      }
    }
  }

  private boolean isReadOnlyMode(String subject) throws SchemaRegistryStoreException {
    Mode subjectMode = getModeInScope(subject);
    return subjectMode == Mode.READONLY || subjectMode == Mode.READONLY_OVERRIDE;
  }

  private ParsedSchema maybePopulateFromPrevious(
      Config config, Schema schema, ParsedSchema parsedSchema, List<ParsedSchema> undeletedVersions)
      throws InvalidSchemaException {
    ParsedSchema previousSchema =
        undeletedVersions.size() > 0 ? undeletedVersions.get(0) : null;
    if (parsedSchema == null) {
      if (previousSchema != null) {
        parsedSchema = previousSchema.copy(schema.getMetadata(), schema.getRuleSet());
      } else {
        throw new InvalidSchemaException("Empty schema");
      }
    }
    return maybeSetMetadataRuleSet(config, schema, parsedSchema, previousSchema);
  }

  private ParsedSchema maybeSetMetadataRuleSet(
      Config config, Schema schema, ParsedSchema parsedSchema, ParsedSchema previousSchema) {
    io.confluent.kafka.schemaregistry.client.rest.entities.Metadata specificMetadata = null;
    if (parsedSchema.metadata() != null) {
      specificMetadata = parsedSchema.metadata();
    } else if (previousSchema != null) {
      specificMetadata = previousSchema.metadata();
    }
    io.confluent.kafka.schemaregistry.client.rest.entities.Metadata mergedMetadata;
    io.confluent.kafka.schemaregistry.client.rest.entities.Metadata defaultMetadata;
    io.confluent.kafka.schemaregistry.client.rest.entities.Metadata overrideMetadata;
    defaultMetadata = config.getDefaultMetadata();
    overrideMetadata = config.getOverrideMetadata();
    mergedMetadata =
        mergeMetadata(mergeMetadata(defaultMetadata, specificMetadata), overrideMetadata);
    io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet specificRuleSet = null;
    if (parsedSchema.ruleSet() != null) {
      specificRuleSet = parsedSchema.ruleSet();
    } else if (previousSchema != null) {
      specificRuleSet = previousSchema.ruleSet();
    }
    io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet mergedRuleSet;
    io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet defaultRuleSet;
    io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet overrideRuleSet;
    defaultRuleSet = config.getDefaultRuleSet();
    overrideRuleSet = config.getOverrideRuleSet();
    mergedRuleSet = mergeRuleSets(mergeRuleSets(defaultRuleSet, specificRuleSet), overrideRuleSet);
    if (mergedMetadata != null || mergedRuleSet != null) {
      parsedSchema = parsedSchema.copy(mergedMetadata, mergedRuleSet);
      schema.setMetadata(parsedSchema.metadata());
      schema.setRuleSet(parsedSchema.ruleSet());
    }
    return parsedSchema;
  }

  public int registerOrForward(String subject,
                               Schema schema,
                               boolean normalize,
                               Map<String, String> headerProperties)
      throws SchemaRegistryException {
    Schema existingSchema = lookUpSchemaUnderSubject(subject, schema, normalize, false);
    if (existingSchema != null) {
      if (schema.getId() == null
          || schema.getId() < 0
          || schema.getId().equals(existingSchema.getId())
      ) {
        return existingSchema.getId();
      }
    }

    kafkaStore.lockFor(subject).lock();
    try {
      if (isLeader()) {
        return register(subject, schema, normalize);
      } else {
        // forward registering request to the leader
        if (leaderIdentity != null) {
          return forwardRegisterRequestToLeader(subject, schema, normalize, headerProperties);
        } else {
          throw new UnknownLeaderException("Register schema request failed since leader is "
                                           + "unknown");
        }
      }
    } finally {
      kafkaStore.lockFor(subject).unlock();
    }
  }

  @Override
  public void deleteSchemaVersion(String subject,
                                  Schema schema,
                                  boolean permanentDelete)
      throws SchemaRegistryException {
    try {
      if (isReadOnlyMode(subject)) {
        throw new OperationNotPermittedException("Subject " + subject + " is in read-only mode");
      }
      SchemaKey key = new SchemaKey(subject, schema.getVersion());
      if (!lookupCache.referencesSchema(key).isEmpty()) {
        throw new ReferenceExistsException(key.toString());
      }
      SchemaValue schemaValue = (SchemaValue) lookupCache.get(key);
      if (permanentDelete && schemaValue != null && !schemaValue.isDeleted()) {
        throw new SchemaVersionNotSoftDeletedException(subject, schema.getVersion().toString());
      }
      // Ensure cache is up-to-date before any potential writes
      kafkaStore.waitUntilKafkaReaderReachesLastOffset(subject, kafkaStoreTimeoutMs);
      if (!permanentDelete) {
        schemaValue = new SchemaValue(schema);
        schemaValue.setDeleted(true);
        metadataEncoder.encodeMetadata(schemaValue);
        kafkaStore.put(key, schemaValue);
        if (!getAllVersions(subject, LookupFilter.DEFAULT).hasNext()) {
          if (getMode(subject) != null) {
            deleteMode(subject);
          }
          if (getConfig(subject) != null) {
            deleteConfig(subject);
          }
        }
      } else {
        kafkaStore.put(key, null);
      }
    } catch (StoreTimeoutException te) {
      throw new SchemaRegistryTimeoutException("Write to the Kafka store timed out while", te);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Error while deleting the schema for subject '"
                                            + subject + "' in the backend Kafka store", e);
    }
  }

  public void deleteSchemaVersionOrForward(
      Map<String, String> headerProperties, String subject,
      Schema schema, boolean permanentDelete) throws SchemaRegistryException {

    kafkaStore.lockFor(subject).lock();
    try {
      if (isLeader()) {
        deleteSchemaVersion(subject, schema, permanentDelete);
      } else {
        // forward registering request to the leader
        if (leaderIdentity != null) {
          forwardDeleteSchemaVersionRequestToLeader(headerProperties, subject,
                  schema.getVersion(), permanentDelete);
        } else {
          throw new UnknownLeaderException("Register schema request failed since leader is "
                                           + "unknown");
        }
      }
    } finally {
      kafkaStore.lockFor(subject).unlock();
    }
  }

  @Override
  public List<Integer> deleteSubject(String subject,
                                     boolean permanentDelete) throws SchemaRegistryException {
    // Ensure cache is up-to-date before any potential writes
    try {
      if (isReadOnlyMode(subject)) {
        throw new OperationNotPermittedException("Subject " + subject + " is in read-only mode");
      }
      kafkaStore.waitUntilKafkaReaderReachesLastOffset(subject, kafkaStoreTimeoutMs);
      List<Integer> deletedVersions = new ArrayList<>();
      int deleteWatermarkVersion = 0;
      Iterator<Schema> schemasToBeDeleted = getAllVersions(subject,
          permanentDelete ? LookupFilter.INCLUDE_DELETED : LookupFilter.DEFAULT);
      while (schemasToBeDeleted.hasNext()) {
        deleteWatermarkVersion = schemasToBeDeleted.next().getVersion();
        SchemaKey key = new SchemaKey(subject, deleteWatermarkVersion);
        if (!lookupCache.referencesSchema(key).isEmpty()) {
          throw new ReferenceExistsException(key.toString());
        }
        if (permanentDelete) {
          SchemaValue schemaValue = (SchemaValue) lookupCache.get(key);
          if (schemaValue != null && !schemaValue.isDeleted()) {
            throw new SubjectNotSoftDeletedException(subject);
          }
        }
        deletedVersions.add(deleteWatermarkVersion);
      }

      if (!permanentDelete) {
        DeleteSubjectKey key = new DeleteSubjectKey(subject);
        DeleteSubjectValue value = new DeleteSubjectValue(subject, deleteWatermarkVersion);
        kafkaStore.put(key, value);
        if (getMode(subject) != null) {
          deleteMode(subject);
        }
        if (getConfig(subject) != null) {
          deleteConfig(subject);
        }
      } else {
        for (Integer version : deletedVersions) {
          kafkaStore.put(new SchemaKey(subject, version), null);
        }
      }
      return deletedVersions;

    } catch (StoreTimeoutException te) {
      throw new SchemaRegistryTimeoutException("Write to the Kafka store timed out while", te);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Error while deleting the subject in the"
                                             + " backend Kafka store", e);
    }
  }

  public List<Integer> deleteSubjectOrForward(
      Map<String, String> requestProperties,
      String subject,
      boolean permanentDelete) throws SchemaRegistryException {
    kafkaStore.lockFor(subject).lock();
    try {
      if (isLeader()) {
        return deleteSubject(subject, permanentDelete);
      } else {
        // forward registering request to the leader
        if (leaderIdentity != null) {
          return forwardDeleteSubjectRequestToLeader(requestProperties,
                  subject,
                  permanentDelete);
        } else {
          throw new UnknownLeaderException("Register schema request failed since leader is "
                                           + "unknown");
        }
      }
    } finally {
      kafkaStore.lockFor(subject).unlock();
    }
  }

  public Schema lookUpSchemaUnderSubjectUsingContexts(
      String subject, Schema schema, boolean normalize, boolean lookupDeletedSchema)
      throws SchemaRegistryException {
    Schema matchingSchema =
        lookUpSchemaUnderSubject(subject, schema, normalize, lookupDeletedSchema);
    if (matchingSchema != null) {
      return matchingSchema;
    }
    QualifiedSubject qs = QualifiedSubject.create(tenant(), subject);
    boolean isQualifiedSubject = qs != null && !DEFAULT_CONTEXT.equals(qs.getContext());
    if (isQualifiedSubject) {
      return null;
    }
    // Try qualifying the subject with each known context
    try (CloseableIterator<SchemaRegistryValue> iter = allContexts()) {
      while (iter.hasNext()) {
        ContextValue v = (ContextValue) iter.next();
        QualifiedSubject qualSub =
            new QualifiedSubject(v.getTenant(), v.getContext(), qs.getSubject());
        Schema qualSchema = schema.copy();
        qualSchema.setSubject(qualSub.toQualifiedSubject());
        matchingSchema = lookUpSchemaUnderSubject(
            qualSub.toQualifiedSubject(), qualSchema, normalize, lookupDeletedSchema);
        if (matchingSchema != null) {
          return matchingSchema;
        }
      }
    }
    return null;
  }

  /**
   * Checks if given schema was ever registered under a subject. If found, it returns the version of
   * the schema under the subject. If not, returns -1
   */
  public Schema lookUpSchemaUnderSubject(
      String subject, Schema schema, boolean normalize, boolean lookupDeletedSchema)
      throws SchemaRegistryException {
    try {
      ParsedSchema parsedSchema = canonicalizeSchema(schema, false, normalize);
      if (parsedSchema != null) {
        SchemaIdAndSubjects schemaIdAndSubjects = this.lookupCache.schemaIdAndSubjects(schema);
        if (schemaIdAndSubjects != null) {
          if (schemaIdAndSubjects.hasSubject(subject)
              && (lookupDeletedSchema || !isSubjectVersionDeleted(subject, schemaIdAndSubjects
              .getVersion(subject)))) {
            Schema matchingSchema = schema.copy();
            matchingSchema.setSubject(subject);
            matchingSchema.setVersion(schemaIdAndSubjects.getVersion(subject));
            matchingSchema.setId(schemaIdAndSubjects.getSchemaId());
            return matchingSchema;
          }
        }
      }

      List<SchemaValue> allVersions = getAllSchemaValues(subject);
      Collections.reverse(allVersions);

      for (SchemaValue schemaValue : allVersions) {
        if ((lookupDeletedSchema || !schemaValue.isDeleted())
            && parsedSchema != null
            && parsedSchema.references().isEmpty()
            && !schemaValue.getReferences().isEmpty()) {
          Schema prev = getSchemaEntityFromSchemaValue(schemaValue);
          ParsedSchema prevSchema = parseSchema(prev);
          if (parsedSchema.deepEquals(prevSchema)) {
            // This handles the case where a schema is sent with all references resolved
            return prev;
          }
        }
      }

      return null;
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error from the backend Kafka store", e);
    }
  }

  public Schema getLatestWithMetadata(
      String subject, Map<String, String> metadata, boolean lookupDeletedSchema)
      throws SchemaRegistryException {
    List<SchemaValue> allVersions = getAllSchemaValues(subject);
    Collections.reverse(allVersions);

    for (SchemaValue schemaValue : allVersions) {
      if (lookupDeletedSchema || !schemaValue.isDeleted()) {
        Schema schema = getSchemaEntityFromSchemaValue(schemaValue);
        if (schema.getMetadata() != null) {
          Map<String, String> props = schema.getMetadata().getProperties();
          if (props != null && props.entrySet().containsAll(metadata.entrySet())) {
            return schema;
          }
        }
      }
    }

    return null;
  }

  public void checkIfSchemaWithIdExist(int id, Schema schema)
      throws SchemaRegistryException, StoreException {
    SchemaKey existingKey = this.lookupCache.schemaKeyById(id, schema.getSubject());
    if (existingKey != null) {
      SchemaRegistryValue existingValue = this.lookupCache.get(existingKey);
      if (existingValue != null
          && existingValue instanceof SchemaValue
          && !((SchemaValue) existingValue).getSchema().equals(schema.getSchema())) {
        throw new OperationNotPermittedException(
            String.format("Overwrite new schema with id %s is not permitted.", id)
        );
      }
    }
  }

  private int forwardRegisterRequestToLeader(String subject, Schema schema, boolean normalize,
                                             Map<String, String> headerProperties)
      throws SchemaRegistryRequestForwardingException {
    final UrlList baseUrl = leaderRestService.getBaseUrls();

    RegisterSchemaRequest registerSchemaRequest = new RegisterSchemaRequest(schema);
    log.debug(String.format("Forwarding registering schema request to %s", baseUrl));
    try {
      int id = leaderRestService.registerSchema(
          headerProperties, registerSchemaRequest, subject, normalize);
      return id;
    } catch (IOException e) {
      throw new SchemaRegistryRequestForwardingException(
          String.format("Unexpected error while forwarding the registering schema request to %s",
              baseUrl),
          e);
    } catch (RestClientException e) {
      throw new RestException(e.getMessage(), e.getStatus(), e.getErrorCode(), e);
    }
  }

  private void forwardUpdateConfigRequestToLeader(
      String subject, Config config,
      Map<String, String> headerProperties)
      throws SchemaRegistryRequestForwardingException {
    UrlList baseUrl = leaderRestService.getBaseUrls();

    ConfigUpdateRequest configUpdateRequest = new ConfigUpdateRequest(config);
    log.debug(String.format("Forwarding update config request %s to %s",
                            configUpdateRequest, baseUrl));
    try {
      leaderRestService.updateConfig(headerProperties, configUpdateRequest, subject);
    } catch (IOException e) {
      throw new SchemaRegistryRequestForwardingException(
          String.format("Unexpected error while forwarding the update config request %s to %s",
                        configUpdateRequest, baseUrl),
          e);
    } catch (RestClientException e) {
      throw new RestException(e.getMessage(), e.getStatus(), e.getErrorCode(), e);
    }
  }

  private void forwardDeleteSchemaVersionRequestToLeader(
      Map<String, String> headerProperties,
      String subject,
      Integer version,
      boolean permanentDelete) throws SchemaRegistryRequestForwardingException {
    UrlList baseUrl = leaderRestService.getBaseUrls();

    log.debug(String.format("Forwarding deleteSchemaVersion schema version request %s-%s to %s",
                            subject, version, baseUrl));
    try {
      leaderRestService.deleteSchemaVersion(headerProperties, subject,
              String.valueOf(version), permanentDelete);
    } catch (IOException e) {
      throw new SchemaRegistryRequestForwardingException(
          String.format(
              "Unexpected error while forwarding deleteSchemaVersion schema version "
              + "request %s-%s to %s", subject, version, baseUrl), e);
    } catch (RestClientException e) {
      throw new RestException(e.getMessage(), e.getStatus(), e.getErrorCode(), e);
    }
  }

  private List<Integer> forwardDeleteSubjectRequestToLeader(
      Map<String, String> requestProperties,
      String subject,
      boolean permanentDelete) throws SchemaRegistryRequestForwardingException {
    UrlList baseUrl = leaderRestService.getBaseUrls();

    log.debug(String.format("Forwarding delete subject request for  %s to %s",
                            subject, baseUrl));
    try {
      return leaderRestService.deleteSubject(requestProperties, subject, permanentDelete);
    } catch (IOException e) {
      throw new SchemaRegistryRequestForwardingException(
          String.format(
              "Unexpected error while forwarding delete subject "
              + "request %s to %s", subject, baseUrl), e);
    } catch (RestClientException e) {
      throw new RestException(e.getMessage(), e.getStatus(), e.getErrorCode(), e);
    }
  }

  private void forwardDeleteConfigToLeader(
      Map<String, String> requestProperties,
      String subject
  ) throws SchemaRegistryRequestForwardingException {
    UrlList baseUrl = leaderRestService.getBaseUrls();

    log.debug(String.format("Forwarding delete subject compatibility config request %s to %s",
        subject, baseUrl));
    try {
      leaderRestService.deleteConfig(requestProperties, subject);
    } catch (IOException e) {
      throw new SchemaRegistryRequestForwardingException(
          String.format(
              "Unexpected error while forwarding delete subject compatibility config"
                  + "request %s to %s", subject, baseUrl), e);
    } catch (RestClientException e) {
      throw new RestException(e.getMessage(), e.getStatus(), e.getErrorCode(), e);
    }
  }

  private void forwardSetModeRequestToLeader(
      String subject, Mode mode, boolean force,
      Map<String, String> headerProperties)
      throws SchemaRegistryRequestForwardingException {
    UrlList baseUrl = leaderRestService.getBaseUrls();

    ModeUpdateRequest modeUpdateRequest = new ModeUpdateRequest();
    modeUpdateRequest.setMode(mode.name());
    log.debug(String.format("Forwarding update mode request %s to %s",
        modeUpdateRequest, baseUrl));
    try {
      leaderRestService.setMode(headerProperties, modeUpdateRequest, subject, force);
    } catch (IOException e) {
      throw new SchemaRegistryRequestForwardingException(
          String.format("Unexpected error while forwarding the update mode request %s to %s",
              modeUpdateRequest, baseUrl),
          e);
    } catch (RestClientException e) {
      throw new RestException(e.getMessage(), e.getStatus(), e.getErrorCode(), e);
    }
  }

  private void forwardDeleteSubjectModeRequestToLeader(
      String subject,
      Map<String, String> headerProperties)
      throws SchemaRegistryRequestForwardingException {
    UrlList baseUrl = leaderRestService.getBaseUrls();

    log.debug(String.format("Forwarding delete subject mode request %s to %s",
        subject, baseUrl));
    try {
      leaderRestService.deleteSubjectMode(headerProperties, subject);
    } catch (IOException e) {
      throw new SchemaRegistryRequestForwardingException(
          String.format(
              "Unexpected error while forwarding delete subject mode"
                  + "request %s to %s", subject, baseUrl), e);
    } catch (RestClientException e) {
      throw new RestException(e.getMessage(), e.getStatus(), e.getErrorCode(), e);
    }
  }

  private ParsedSchema canonicalizeSchema(Schema schema, boolean isNew, boolean normalize)
          throws InvalidSchemaException {
    if (schema == null
        || schema.getSchema() == null
        || schema.getSchema().trim().isEmpty()) {
      return null;
    }
    ParsedSchema parsedSchema = parseSchema(schema, isNew, normalize);
    return maybeValidateAndNormalizeSchema(parsedSchema, schema, true, normalize);
  }

  private ParsedSchema maybeValidateAndNormalizeSchema(
      ParsedSchema parsedSchema, Schema schema, boolean validate, boolean normalize)
          throws InvalidSchemaException {
    try {
      if (validate) {
        parsedSchema.validate();
      }
      if (normalize) {
        parsedSchema = parsedSchema.normalize();
      }
    } catch (Exception e) {
      String errMsg = "Invalid schema " + schema + ", details: " + e.getMessage();
      log.error(errMsg, e);
      throw new InvalidSchemaException(errMsg, e);
    }
    schema.setSchemaType(parsedSchema.schemaType());
    schema.setSchema(parsedSchema.canonicalString());
    schema.setReferences(parsedSchema.references());
    return parsedSchema;
  }

  public ParsedSchema parseSchema(Schema schema) throws InvalidSchemaException {
    return parseSchema(schema, false, false);
  }

  public ParsedSchema parseSchema(
          Schema schema,
          boolean isNew,
          boolean normalize) throws InvalidSchemaException {
    try {
      ParsedSchema parsedSchema = schemaCache.get(new RawSchema(schema, isNew, normalize));
      if (schema.getVersion() != null) {
        parsedSchema = parsedSchema.copy(schema.getVersion());
      }
      return parsedSchema;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof InvalidSchemaException) {
        throw (InvalidSchemaException) cause;
      } else if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else {
        throw new RuntimeException(e);
      }
    }
  }

  private ParsedSchema loadSchema(
      Schema schema,
      boolean isNew,
      boolean normalize)
      throws InvalidSchemaException {
    String schemaType = schema.getSchemaType();
    if (schemaType == null) {
      schemaType = AvroSchema.TYPE;
    }
    SchemaProvider provider = schemaProvider(schemaType);
    if (provider == null) {
      String errMsg = "Invalid schema type " + schemaType;
      log.error(errMsg);
      throw new InvalidSchemaException(errMsg);
    }
    final String type = schemaType;

    try {
      return provider.parseSchemaOrElseThrow(schema, isNew, normalize);
    } catch (Exception e) {
      throw new InvalidSchemaException("Invalid schema " + schema
              + " with refs " + schema.getReferences()
              + " of type " + type + ", details: " + e.getMessage());
    }
  }

  public Schema getUsingContexts(String subject, int version, boolean
      returnDeletedSchema) throws SchemaRegistryException {
    Schema schema = get(subject, version, returnDeletedSchema);
    if (schema != null) {
      return schema;
    }
    QualifiedSubject qs = QualifiedSubject.create(tenant(), subject);
    boolean isQualifiedSubject = qs != null && !DEFAULT_CONTEXT.equals(qs.getContext());
    if (isQualifiedSubject) {
      return null;
    }
    // Try qualifying the subject with each known context
    try (CloseableIterator<SchemaRegistryValue> iter = allContexts()) {
      while (iter.hasNext()) {
        ContextValue v = (ContextValue) iter.next();
        QualifiedSubject qualSub =
            new QualifiedSubject(v.getTenant(), v.getContext(), qs.getSubject());
        schema = get(qualSub.toQualifiedSubject(), version, returnDeletedSchema);
        if (schema != null) {
          return schema;
        }
      }
    }
    return null;
  }

  public boolean schemaVersionExists(String subject, VersionId versionId, boolean
          returnDeletedSchema) throws SchemaRegistryException {
    final int version = versionId.getVersionId();
    Schema schema = this.get(subject, version, returnDeletedSchema);
    return (schema != null);
  }

  @Override
  public Schema get(String subject, int version, boolean returnDeletedSchema)
      throws SchemaRegistryException {
    VersionId versionId = new VersionId(version);
    if (versionId.isLatest()) {
      return getLatestVersion(subject);
    } else {
      SchemaKey key = new SchemaKey(subject, version);
      try {
        SchemaValue schemaValue = (SchemaValue) kafkaStore.get(key);
        metadataEncoder.decodeMetadata(schemaValue);
        Schema schema = null;
        if ((schemaValue != null && !schemaValue.isDeleted()) || returnDeletedSchema) {
          schema = getSchemaEntityFromSchemaValue(schemaValue);
        }
        return schema;
      } catch (StoreException e) {
        throw new SchemaRegistryStoreException(
            "Error while retrieving schema from the backend Kafka"
            + " store", e);
      }
    }
  }

  @Override
  public SchemaString get(int id, String subject) throws SchemaRegistryException {
    return get(id, subject, null, false);
  }

  public SchemaString get(
      int id,
      String subject,
      String format,
      boolean fetchMaxId
  ) throws SchemaRegistryException {
    SchemaValue schema = null;
    try {
      SchemaKey subjectVersionKey = getSchemaKeyUsingContexts(id, subject);
      if (subjectVersionKey == null) {
        return null;
      }
      schema = (SchemaValue) kafkaStore.get(subjectVersionKey);
      if (schema == null) {
        return null;
      }
      metadataEncoder.decodeMetadata(schema);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error while retrieving schema with id "
          + id
          + " from the backend Kafka"
          + " store", e);
    }
    Schema schemaEntity = schema.toSchemaEntity();
    SchemaString schemaString = new SchemaString(schemaEntity);
    if (format != null && !format.trim().isEmpty()) {
      ParsedSchema parsedSchema = parseSchema(schemaEntity, false, false);
      schemaString.setSchemaString(parsedSchema.formattedString(format));
    } else {
      schemaString.setSchemaString(schema.getSchema());
    }
    if (fetchMaxId) {
      schemaString.setMaxId(idGenerator.getMaxId(schema));
    }
    return schemaString;
  }

  private SchemaKey getSchemaKeyUsingContexts(int id, String subject)
          throws StoreException, SchemaRegistryException {
    QualifiedSubject qs = QualifiedSubject.create(tenant(), subject);
    boolean isQualifiedSubject = qs != null && !DEFAULT_CONTEXT.equals(qs.getContext());
    SchemaKey subjectVersionKey = lookupCache.schemaKeyById(id, subject);
    if (subject == null
        || subject.isEmpty()
        || isQualifiedSubject
        || schemaKeyMatchesSubject(subjectVersionKey, qs)) {
      return subjectVersionKey;
    }
    // Try qualifying the subject with each known context
    try (CloseableIterator<SchemaRegistryValue> iter = allContexts()) {
      while (iter.hasNext()) {
        ContextValue v = (ContextValue) iter.next();
        QualifiedSubject qualSub =
            new QualifiedSubject(v.getTenant(), v.getContext(), qs.getSubject());
        SchemaKey key = lookupCache.schemaKeyById(id, qualSub.toQualifiedSubject());
        if (schemaKeyMatchesSubject(key, qualSub)) {
          return key;
        }
      }
    }
    // Could not find the id in subjects in other contexts,
    // just return the id in the default context if found
    return subjectVersionKey;
  }

  private boolean schemaKeyMatchesSubject(SchemaKey key, QualifiedSubject qs) {
    if (key == null) {
      return false;
    } else if (qs == null) {
      return true;
    } else {
      QualifiedSubject keyQs = QualifiedSubject.create(tenant(), key.getSubject());
      return keyQs != null && qs.getSubject().equals(keyQs.getSubject());
    }
  }

  private CloseableIterator<SchemaRegistryValue> allContexts() throws SchemaRegistryException {
    try {
      ContextKey key1 = new ContextKey(tenant(), String.valueOf(Character.MIN_VALUE));
      ContextKey key2 = new ContextKey(tenant(), String.valueOf(Character.MAX_VALUE));
      return kafkaStore.getAll(key1, key2);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
              "Error from the backend Kafka store", e);
    }
  }

  public List<Integer> getReferencedBy(String subject, VersionId versionId)
      throws SchemaRegistryException {
    try {
      int version = versionId.getVersionId();
      if (versionId.isLatest()) {
        version = getLatestVersion(subject).getVersion();
      }
      SchemaKey key = new SchemaKey(subject, version);
      List<Integer> ids = new ArrayList<>(lookupCache.referencesSchema(key));
      Collections.sort(ids);
      return ids;
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error from the backend Kafka store", e);
    }
  }

  public List<String> listContexts() throws SchemaRegistryException {
    List<String> contexts = new ArrayList<>();
    contexts.add(DEFAULT_CONTEXT);
    try (CloseableIterator<SchemaRegistryValue> iter = allContexts()) {
      while (iter.hasNext()) {
        ContextValue contextValue = (ContextValue) iter.next();
        contexts.add(contextValue.getContext());
      }
    }
    return contexts;
  }

  @Override
  public Set<String> listSubjects(LookupFilter filter)
          throws SchemaRegistryException {
    return listSubjectsWithPrefix(CONTEXT_WILDCARD, filter);
  }

  public Set<String> listSubjectsWithPrefix(String prefix, LookupFilter filter)
      throws SchemaRegistryException {
    try (CloseableIterator<SchemaRegistryValue> allVersions = allVersions(prefix, true)) {
      return extractUniqueSubjects(allVersions, filter);
    }
  }

  public Set<String> listSubjectsForId(int id, String subject) throws SchemaRegistryException {
    return listSubjectsForId(id, subject, false);
  }

  @Override
  public Set<String> listSubjectsForId(int id, String subject, boolean returnDeleted)
      throws SchemaRegistryException {
    List<SubjectVersion> versions = listVersionsForId(id, subject, returnDeleted);
    return versions != null
        ? versions.stream()
            .map(SubjectVersion::getSubject)
            .collect(Collectors.toCollection(LinkedHashSet::new))
        : null;
  }

  public List<SubjectVersion> listVersionsForId(int id, String subject)
      throws SchemaRegistryException {
    return listVersionsForId(id, subject, false);
  }

  public List<SubjectVersion> listVersionsForId(int id, String subject, boolean lookupDeleted)
      throws SchemaRegistryException {
    SchemaValue schema = null;
    try {
      SchemaKey subjectVersionKey = getSchemaKeyUsingContexts(id, subject);
      if (subjectVersionKey == null) {
        return null;
      }
      schema = (SchemaValue) kafkaStore.get(subjectVersionKey);
      if (schema == null) {
        return null;
      }

      return lookupCache.schemaIdAndSubjects(getSchemaEntityFromSchemaValue(schema))
          .allSubjectVersions()
          .entrySet()
          .stream()
          .flatMap(e -> {
            try {
              SchemaValue schemaValue =
                  (SchemaValue) kafkaStore.get(new SchemaKey(e.getKey(), e.getValue()));
              if ((schemaValue != null && !schemaValue.isDeleted()) || lookupDeleted) {
                return Stream.of(new SubjectVersion(e.getKey(), e.getValue()));
              } else {
                return Stream.empty();
              }
            } catch (StoreException ex) {
              return Stream.empty();
            }
          })
          .collect(Collectors.toList());
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Error while retrieving schema with id "
                                              + id + " from the backend Kafka store", e);
    }
  }

  private Set<String> extractUniqueSubjects(Iterator<SchemaRegistryValue> allVersions,
                                            LookupFilter filter) {
    Map<String, Boolean> subjects = new HashMap<>();
    while (allVersions.hasNext()) {
      SchemaValue value = (SchemaValue) allVersions.next();
      subjects.merge(value.getSubject(), value.isDeleted(), (v1, v2) -> v1 && v2);
    }

    return subjects.keySet().stream()
        .filter(k -> shouldInclude(subjects.get(k), filter))
        .sorted()
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public Set<String> subjects(String subject,
                              boolean lookupDeletedSubjects)
      throws SchemaRegistryStoreException {
    try {
      return lookupCache.subjects(subject, lookupDeletedSubjects);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error from the backend Kafka store", e);
    }
  }

  public boolean hasSubjects(String subject,
                             boolean lookupDeletedSubjects)
          throws SchemaRegistryStoreException {
    try {
      return lookupCache.hasSubjects(subject, lookupDeletedSubjects);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error from the backend Kafka store", e);
    }
  }

  @Override
  public Iterator<Schema> getAllVersions(String subject, LookupFilter filter)
      throws SchemaRegistryException {
    try (CloseableIterator<SchemaRegistryValue> allVersions = allVersions(subject, false)) {
      return sortSchemasByVersion(allVersions, filter).iterator();
    }
  }

  @Override
  public Iterator<Schema> getVersionsWithSubjectPrefix(String prefix,
                                                       LookupFilter filter,
                                                       boolean returnLatestOnly)
      throws SchemaRegistryException {
    try (CloseableIterator<SchemaRegistryValue> allVersions = allVersions(prefix, true)) {
      return sortSchemasByVersion(allVersions, filter, returnLatestOnly)
          .iterator();
    }
  }

  private List<SchemaValue> getAllSchemaValues(String subject)
      throws SchemaRegistryException {
    try (CloseableIterator<SchemaRegistryValue> allVersions = allVersions(subject, false)) {
      return sortSchemaValuesByVersion(allVersions);
    }
  }

  @Override
  public Schema getLatestVersion(String subject) throws SchemaRegistryException {
    try (CloseableIterator<SchemaRegistryValue> allVersions = allVersions(subject, false)) {
      return getLatestVersionFromSubjectSchemas(allVersions);
    }
  }

  private Schema getLatestVersionFromSubjectSchemas(
          CloseableIterator<SchemaRegistryValue> schemas) {
    int latestVersionId = -1;
    SchemaValue latestSchemaValue = null;

    while (schemas.hasNext()) {
      SchemaValue schemaValue = (SchemaValue) schemas.next();
      if (schemaValue.isDeleted()) {
        continue;
      }
      if (schemaValue.getVersion() > latestVersionId) {
        latestVersionId = schemaValue.getVersion();
        latestSchemaValue = schemaValue;
      }
    }

    return latestSchemaValue != null ? getSchemaEntityFromSchemaValue(latestSchemaValue) : null;
  }

  private CloseableIterator<SchemaRegistryValue> allVersions(
      String subjectOrPrefix, boolean isPrefix) throws SchemaRegistryException {
    try {
      String start;
      String end;
      int idx = subjectOrPrefix.indexOf(CONTEXT_WILDCARD);
      if (idx >= 0) {
        // Context wildcard match (prefix may contain tenant)
        String prefix = subjectOrPrefix.substring(0, idx);
        String unqualifiedSubjectOrPrefix =
            subjectOrPrefix.substring(idx + CONTEXT_WILDCARD.length());
        if (!unqualifiedSubjectOrPrefix.isEmpty()) {
          return allVersionsFromAllContexts(prefix, unqualifiedSubjectOrPrefix, isPrefix);
        }
        start = prefix + CONTEXT_PREFIX + CONTEXT_DELIMITER;
        end = prefix + CONTEXT_PREFIX + Character.MAX_VALUE + CONTEXT_DELIMITER;
      } else {
        start = subjectOrPrefix;
        end = isPrefix ? subjectOrPrefix + Character.MAX_VALUE : subjectOrPrefix;
      }
      SchemaKey key1 = new SchemaKey(start, MIN_VERSION);
      SchemaKey key2 = new SchemaKey(end, MAX_VERSION);
      return TransformedIterator.transform(kafkaStore.getAll(key1, key2), v -> {
        if (v instanceof SchemaValue) {
          metadataEncoder.decodeMetadata(((SchemaValue) v));
        }
        return v;
      });
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error from the backend Kafka store", e);
    }
  }

  private CloseableIterator<SchemaRegistryValue> allVersionsFromAllContexts(
      String tenantPrefix, String unqualifiedSubjectOrPrefix, boolean isPrefix)
      throws SchemaRegistryException {
    List<SchemaRegistryValue> versions = new ArrayList<>();
    // Add versions from default context
    try (CloseableIterator<SchemaRegistryValue> iter =
        allVersions(tenantPrefix + unqualifiedSubjectOrPrefix, isPrefix)) {
      while (iter.hasNext()) {
        versions.add(iter.next());
      }
    }
    List<ContextValue> contexts = new ArrayList<>();
    try (CloseableIterator<SchemaRegistryValue> iter = allContexts()) {
      while (iter.hasNext()) {
        contexts.add((ContextValue) iter.next());
      }
    }
    for (ContextValue v : contexts) {
      QualifiedSubject qualSub =
          new QualifiedSubject(v.getTenant(), v.getContext(), unqualifiedSubjectOrPrefix);
      try (CloseableIterator<SchemaRegistryValue> subiter =
          allVersions(qualSub.toQualifiedSubject(), isPrefix)) {
        while (subiter.hasNext()) {
          versions.add(subiter.next());
        }
      }
    }
    return new DelegatingIterator<>(versions.iterator());
  }

  @Override
  public void close() {
    log.info("Shutting down schema registry");
    kafkaStore.close();
    metadataEncoder.close();
    if (leaderElector != null) {
      leaderElector.close();
    }
  }

  public void updateConfig(String subject, Config config)
      throws SchemaRegistryStoreException, OperationNotPermittedException, UnknownLeaderException {
    if (isReadOnlyMode(subject)) {
      throw new OperationNotPermittedException("Subject " + subject + " is in read-only mode");
    }
    ConfigKey configKey = new ConfigKey(subject);
    try {
      kafkaStore.waitUntilKafkaReaderReachesLastOffset(subject, kafkaStoreTimeoutMs);
      ConfigValue oldConfig = (ConfigValue) kafkaStore.get(configKey);
      ConfigValue newConfig = new ConfigValue(subject, config, ruleSetHandler);
      kafkaStore.put(configKey, ConfigValue.update(oldConfig, newConfig));
      log.debug("Wrote new config : " + config + " to the Kafka data store with key " + configKey);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Failed to write new config value to the store",
                                             e);
    }
  }

  public void updateConfigOrForward(String subject, Config newConfig,
                                    Map<String, String> headerProperties)
      throws SchemaRegistryStoreException, SchemaRegistryRequestForwardingException,
      UnknownLeaderException, OperationNotPermittedException {
    kafkaStore.lockFor(subject).lock();
    try {
      if (isLeader()) {
        updateConfig(subject, newConfig);
      } else {
        // forward update config request to the leader
        if (leaderIdentity != null) {
          forwardUpdateConfigRequestToLeader(subject, newConfig, headerProperties);
        } else {
          throw new UnknownLeaderException("Update config request failed since leader is "
                                           + "unknown");
        }
      }
    } finally {
      kafkaStore.lockFor(subject).unlock();
    }
  }

  public void deleteSubjectConfig(String subject)
      throws SchemaRegistryStoreException, OperationNotPermittedException {
    if (isReadOnlyMode(subject)) {
      throw new OperationNotPermittedException("Subject " + subject + " is in read-only mode");
    }
    try {
      kafkaStore.waitUntilKafkaReaderReachesLastOffset(subject, kafkaStoreTimeoutMs);
      deleteConfig(subject);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Failed to delete subject config value from store",
          e);
    }
  }

  public void deleteConfigOrForward(String subject,
                                    Map<String, String> headerProperties)
      throws SchemaRegistryStoreException, SchemaRegistryRequestForwardingException,
      OperationNotPermittedException, UnknownLeaderException {
    kafkaStore.lockFor(subject).lock();
    try {
      if (isLeader()) {
        deleteSubjectConfig(subject);
      } else {
        // forward delete subject config request to the leader
        if (leaderIdentity != null) {
          forwardDeleteConfigToLeader(headerProperties, subject);
        } else {
          throw new UnknownLeaderException("Delete config request failed since leader is "
              + "unknown");
        }
      }
    } finally {
      kafkaStore.lockFor(subject).unlock();
    }
  }

  private String kafkaClusterId(SchemaRegistryConfig config) throws SchemaRegistryException {
    Properties adminClientProps = new Properties();
    KafkaStore.addSchemaRegistryConfigsToClientProperties(config, adminClientProps);
    adminClientProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapBrokers());

    try (AdminClient adminClient = AdminClient.create(adminClientProps)) {
      return adminClient
              .describeCluster()
              .clusterId()
              .get(initTimeout, TimeUnit.MILLISECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new SchemaRegistryException("Failed to get Kafka cluster ID", e);
    }
  }

  public String getKafkaClusterId() {
    return kafkaClusterId;
  }

  public String getGroupId() {
    return groupId;
  }

  public Config getConfig(String subject)
      throws SchemaRegistryStoreException {
    try {
      return lookupCache.config(subject, false, new Config(defaultCompatibilityLevel.name));
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Failed to write new config value to the store", e);
    }
  }

  public Config getConfigInScope(String subject)
      throws SchemaRegistryStoreException {
    try {
      return lookupCache.config(subject, true, new Config(defaultCompatibilityLevel.name));
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Failed to write new config value to the store", e);
    }
  }

  @Override
  public List<String> isCompatible(String subject,
                                   Schema newSchema,
                                   Schema latestSchema)
      throws SchemaRegistryException {
    if (latestSchema == null) {
      log.error("Latest schema not provided");
      throw new InvalidSchemaException("Latest schema not provided");
    }
    return isCompatible(subject, newSchema, Collections.singletonList(latestSchema));
  }

  /**
   * @param previousSchemas Full schema history in chronological order
   */
  @Override
  public List<String> isCompatible(String subject,
                                   Schema newSchema,
                                   List<Schema> previousSchemas)
      throws SchemaRegistryException {

    if (previousSchemas == null) {
      log.error("Previous schema not provided");
      throw new InvalidSchemaException("Previous schema not provided");
    }

    List<ParsedSchema> prevParsedSchemas = new ArrayList<>(previousSchemas.size());
    for (Schema previousSchema : previousSchemas) {
      ParsedSchema prevParsedSchema = parseSchema(previousSchema);
      prevParsedSchemas.add(prevParsedSchema);
    }

    ParsedSchema parsedSchema = canonicalizeSchema(newSchema, true, false);
    if (parsedSchema == null) {
      log.error("Empty schema");
      throw new InvalidSchemaException("Empty schema");
    }
    Config config = getConfigInScope(subject);
    return isCompatibleWithPrevious(config, parsedSchema, prevParsedSchemas);
  }

  private List<String> isCompatibleWithPrevious(Config config,
                                                ParsedSchema parsedSchema,
                                                List<ParsedSchema> previousSchemas)
      throws SchemaRegistryException {

    CompatibilityLevel compatibility = CompatibilityLevel.forName(config.getCompatibilityLevel());
    String compatibilityGroup = config.getCompatibilityGroup();
    if (compatibilityGroup != null) {
      String groupValue = getCompatibilityGroupValue(parsedSchema, compatibilityGroup);
      if (groupValue != null) {
        // Only check compatibility against schemas with the same compatibility group value
        previousSchemas = previousSchemas.stream()
            .filter(s -> groupValue.equals(getCompatibilityGroupValue(s, compatibilityGroup)))
            .collect(Collectors.toList());
      }
    }
    List<String> errorMessages = parsedSchema.isCompatible(compatibility, previousSchemas);
    if (errorMessages.size() > 0) {
      errorMessages.add(String.format("{compatibility: '%s'}", compatibility));
    }
    return errorMessages;
  }

  private static String getCompatibilityGroupValue(
      ParsedSchema parsedSchema, String compatibilityGroup) {
    if (parsedSchema.metadata() != null && parsedSchema.metadata().getProperties() != null) {
      return parsedSchema.metadata().getProperties().get(compatibilityGroup);
    }
    return null;
  }

  private void deleteMode(String subject) throws StoreException {
    ModeKey modeKey = new ModeKey(subject);
    this.kafkaStore.delete(modeKey);
  }

  private void deleteConfig(String subject) throws StoreException {
    ConfigKey configKey = new ConfigKey(subject);
    this.kafkaStore.delete(configKey);
  }

  public Mode getMode(String subject) throws SchemaRegistryStoreException {
    try {
      Mode globalMode = lookupCache.mode(null, false, defaultMode);
      Mode subjectMode = lookupCache.mode(subject, false, defaultMode);

      return globalMode == Mode.READONLY_OVERRIDE ? globalMode : subjectMode;
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Failed to write new config value to the store", e);
    }
  }

  public Mode getModeInScope(String subject) throws SchemaRegistryStoreException {
    try {
      Mode globalMode = lookupCache.mode(null, true, defaultMode);
      Mode subjectMode = lookupCache.mode(subject, true, defaultMode);

      return globalMode == Mode.READONLY_OVERRIDE ? globalMode : subjectMode;
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Failed to write new config value to the store", e);
    }
  }

  public void setMode(String subject, Mode mode)
      throws SchemaRegistryStoreException, OperationNotPermittedException {
    setMode(subject, mode, false);
  }

  public void setMode(String subject, Mode mode, boolean force)
      throws SchemaRegistryStoreException, OperationNotPermittedException {
    if (!allowModeChanges) {
      throw new OperationNotPermittedException("Mode changes are not allowed");
    }
    ModeKey modeKey = new ModeKey(subject);
    try {
      kafkaStore.waitUntilKafkaReaderReachesLastOffset(subject, kafkaStoreTimeoutMs);
      if (mode == Mode.IMPORT && getMode(subject) != Mode.IMPORT && !force) {
        // Changing to import mode requires that no schemas exist with matching subjects.
        if (hasSubjects(subject, false)) {
          throw new OperationNotPermittedException("Cannot import since found existing subjects");
        }
        // At this point no schemas should exist with matching subjects.
        // Write an event to clear deleted schemas from the caches.
        kafkaStore.put(new ClearSubjectKey(subject), new ClearSubjectValue(subject));
      }
      kafkaStore.put(modeKey, new ModeValue(subject, mode));
      log.debug("Wrote new mode: " + mode.name() + " to the"
          + " Kafka data store with key " + modeKey.toString());
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Failed to write new mode to the store", e);
    }
  }

  public void setModeOrForward(String subject, Mode mode, boolean force,
      Map<String, String> headerProperties)
      throws SchemaRegistryStoreException, SchemaRegistryRequestForwardingException,
      OperationNotPermittedException, UnknownLeaderException {
    kafkaStore.lockFor(subject).lock();
    try {
      if (isLeader()) {
        setMode(subject, mode, force);
      } else {
        // forward update mode request to the leader
        if (leaderIdentity != null) {
          forwardSetModeRequestToLeader(subject, mode, force, headerProperties);
        } else {
          throw new UnknownLeaderException("Update mode request failed since leader is "
              + "unknown");
        }
      }
    } finally {
      kafkaStore.lockFor(subject).unlock();
    }
  }

  public void deleteSubjectMode(String subject)
      throws SchemaRegistryStoreException, OperationNotPermittedException {
    if (!allowModeChanges) {
      throw new OperationNotPermittedException("Mode changes are not allowed");
    }
    try {
      kafkaStore.waitUntilKafkaReaderReachesLastOffset(subject, kafkaStoreTimeoutMs);
      deleteMode(subject);
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException("Failed to delete subject config value from store",
          e);
    }
  }

  public void deleteSubjectModeOrForward(String subject, Map<String, String> headerProperties)
      throws SchemaRegistryStoreException, SchemaRegistryRequestForwardingException,
      OperationNotPermittedException, UnknownLeaderException {
    kafkaStore.lockFor(subject).lock();
    try {
      if (isLeader()) {
        deleteSubjectMode(subject);
      } else {
        // forward delete subject config request to the leader
        if (leaderIdentity != null) {
          forwardDeleteSubjectModeRequestToLeader(subject, headerProperties);
        } else {
          throw new UnknownLeaderException("Delete config request failed since leader is "
              + "unknown");
        }
      }
    } finally {
      kafkaStore.lockFor(subject).unlock();
    }
  }

  KafkaStore<SchemaRegistryKey, SchemaRegistryValue> getKafkaStore() {
    return this.kafkaStore;
  }

  private List<Schema> sortSchemasByVersion(CloseableIterator<SchemaRegistryValue> schemas,
                                            LookupFilter filter) {
    return sortSchemasByVersion(schemas, filter, false);
  }

  private List<Schema> sortSchemasByVersion(CloseableIterator<SchemaRegistryValue> schemas,
                                            LookupFilter filter,
                                            boolean returnLatestOnly) {
    List<Schema> schemaList = new ArrayList<>();
    Schema previousSchema = null;
    while (schemas.hasNext()) {
      SchemaValue schemaValue = (SchemaValue) schemas.next();
      boolean shouldInclude = shouldInclude(schemaValue.isDeleted(), filter);
      if (!shouldInclude) {
        continue;
      }
      Schema schema = getSchemaEntityFromSchemaValue(schemaValue);
      if (returnLatestOnly) {
        if (previousSchema != null && !schema.getSubject().equals(previousSchema.getSubject())) {
          schemaList.add(previousSchema);
        }
      } else {
        schemaList.add(schema);
      }
      previousSchema = schema;
    }
    if (returnLatestOnly && previousSchema != null) {
      // handle last subject
      Schema lastSchema = schemaList.isEmpty() ? null : schemaList.get(schemaList.size() - 1);
      if (lastSchema == null || !lastSchema.getSubject().equals(previousSchema.getSubject())) {
        schemaList.add(previousSchema);
      }
    }
    Collections.sort(schemaList);
    return schemaList;
  }

  private List<SchemaValue> sortSchemaValuesByVersion(
          CloseableIterator<SchemaRegistryValue> schemas) {
    List<SchemaValue> schemaList = new ArrayList<>();
    while (schemas.hasNext()) {
      SchemaValue schemaValue = (SchemaValue) schemas.next();
      schemaList.add(schemaValue);
    }
    Collections.sort(schemaList);
    return schemaList;
  }

  private Schema getSchemaEntityFromSchemaValue(SchemaValue schemaValue) {
    return schemaValue != null ? schemaValue.toSchemaEntity() : null;
  }

  private boolean isSubjectVersionDeleted(String subject, int version)
      throws SchemaRegistryException {
    try {
      SchemaValue schemaValue = (SchemaValue) this.kafkaStore.get(new SchemaKey(subject, version));
      return schemaValue == null || schemaValue.isDeleted();
    } catch (StoreException e) {
      throw new SchemaRegistryStoreException(
          "Error while retrieving schema from the backend Kafka"
          + " store", e);
    }
  }

  private static boolean shouldInclude(boolean isDeleted, LookupFilter filter) {
    switch (filter) {
      case DEFAULT:
        return !isDeleted;
      case INCLUDE_DELETED:
        return true;
      case DELETED_ONLY:
        return isDeleted;
      default:
        return false;
    }
  }

  @Override
  public SchemaRegistryConfig config() {
    return config;
  }

  @Override
  public Map<String, Object> properties() {
    return props;
  }

  public HostnameVerifier getHostnameVerifier() throws SchemaRegistryStoreException {
    String sslEndpointIdentificationAlgo =
            config.getString(RestConfig.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG);

    if (sslEndpointIdentificationAlgo == null
            || sslEndpointIdentificationAlgo.equals("none")
            || sslEndpointIdentificationAlgo.isEmpty()) {
      return (hostname, session) -> true;
    }

    if (sslEndpointIdentificationAlgo.equalsIgnoreCase("https")) {
      return null;
    }

    throw new SchemaRegistryStoreException(
            RestConfig.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG
                    + " "
                    + sslEndpointIdentificationAlgo
                    + " not supported");
  }

  private static class RawSchema {
    private Schema schema;
    private boolean isNew;
    private boolean normalize;

    public RawSchema(Schema schema, boolean isNew, boolean normalize) {
      this.schema = schema;
      this.isNew = isNew;
      this.normalize = normalize;
    }

    public Schema getSchema() {
      return schema;
    }

    public boolean isNew() {
      return isNew;
    }

    public boolean isNormalize() {
      return normalize;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RawSchema that = (RawSchema) o;
      return isNew == that.isNew
          && normalize == that.normalize
          && Objects.equals(schema, that.schema);
    }

    @Override
    public int hashCode() {
      return Objects.hash(schema, isNew, normalize);
    }
  }

  public static class SchemeAndPort {
    public int port;
    public String scheme;

    public SchemeAndPort(String scheme, int port) {
      this.port = port;
      this.scheme = scheme;
    }
  }
}
