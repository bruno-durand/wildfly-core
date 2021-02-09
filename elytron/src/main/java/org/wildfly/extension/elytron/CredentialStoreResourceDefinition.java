/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.extension.elytron;

import static org.jboss.as.controller.security.CredentialReference.handleCredentialReferenceUpdate;
import static org.jboss.as.controller.security.CredentialReference.rollbackCredentialStoreUpdate;
import static org.wildfly.extension.elytron.Capabilities.CREDENTIAL_STORE_CAPABILITY;
import static org.wildfly.extension.elytron.Capabilities.CREDENTIAL_STORE_RUNTIME_CAPABILITY;
import static org.wildfly.extension.elytron.Capabilities.PROVIDERS_CAPABILITY;
import static org.wildfly.extension.elytron.ElytronDefinition.commonDependencies;
import static org.wildfly.extension.elytron.ElytronExtension.isServerOrHostController;
import static org.wildfly.extension.elytron.FileAttributeDefinitions.pathName;
import static org.wildfly.extension.elytron._private.ElytronSubsystemMessages.ROOT_LOGGER;
import static org.wildfly.security.encryption.SecretKeyUtil.exportSecretKey;
import static org.wildfly.security.encryption.SecretKeyUtil.generateSecretKey;
import static org.wildfly.security.encryption.SecretKeyUtil.importSecretKey;

import java.security.GeneralSecurityException;
import java.security.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import javax.crypto.SecretKey;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ObjectTypeAttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleMapAttributeDefinition;
import org.jboss.as.controller.SimpleOperationDefinition;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.StandardResourceDescriptionResolver;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.security.CredentialReference;
import org.jboss.as.controller.services.path.PathManager;
import org.jboss.as.controller.services.path.PathManagerService;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartException;
import org.wildfly.security.credential.Credential;
import org.wildfly.security.credential.PasswordCredential;
import org.wildfly.security.credential.SecretKeyCredential;
import org.wildfly.security.credential.store.CredentialStore;
import org.wildfly.security.credential.store.CredentialStoreException;
import org.wildfly.security.credential.store.impl.KeyStoreCredentialStore;

/**
 * A {@link ResourceDefinition} for a CredentialStore.
 *
 * @author <a href="mailto:pskopek@redhat.com">Peter Skopek</a>
 */
final class CredentialStoreResourceDefinition extends AbstractCredentialStoreResourceDefinition {

    // KeyStore backed credential store supported attributes
    private static final String CS_KEY_STORE_TYPE_ATTRIBUTE = "keyStoreType";
    private static final List<String> filebasedKeystoreTypes = Collections.unmodifiableList(Arrays.asList("JKS", "JCEKS", "PKCS12"));



    static final SimpleAttributeDefinition LOCATION = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.LOCATION, ModelType.STRING, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(true)
            .setMinSize(1)
            .setRestartAllServices()
            .build();

    static final SimpleAttributeDefinition MODIFIABLE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.MODIFIABLE, ModelType.BOOLEAN, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setDefaultValue(ModelNode.TRUE)
            .setAllowExpression(false)
            .setRestartAllServices()
            .build();

    static final SimpleAttributeDefinition CREATE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.CREATE, ModelType.BOOLEAN, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(false)
            .setDefaultValue(ModelNode.FALSE)
            .setRestartAllServices()
            .build();

    static final SimpleMapAttributeDefinition IMPLEMENTATION_PROPERTIES = new SimpleMapAttributeDefinition.Builder(ElytronDescriptionConstants.IMPLEMENTATION_PROPERTIES, ModelType.STRING, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(true)
            .setRestartAllServices()
            .build();

    static final ObjectTypeAttributeDefinition CREDENTIAL_REFERENCE = CredentialReference.getAttributeDefinition(true);

    static final SimpleAttributeDefinition TYPE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.TYPE, ModelType.STRING, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(true)
            .setMinSize(1)
            .setRestartAllServices()
            .build();

    static final SimpleAttributeDefinition PROVIDER_NAME = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.PROVIDER_NAME, ModelType.STRING, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(true)
            .setMinSize(1)
            .setRestartAllServices()
            .build();

    static final SimpleAttributeDefinition PROVIDERS = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.PROVIDERS, ModelType.STRING, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(false)
            .setMinSize(1)
            .setRestartAllServices()
            .setCapabilityReference(PROVIDERS_CAPABILITY, CREDENTIAL_STORE_CAPABILITY)
            .build();

    static final SimpleAttributeDefinition OTHER_PROVIDERS = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.OTHER_PROVIDERS, ModelType.STRING, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(false)
            .setMinSize(1)
            .setRestartAllServices()
            .setCapabilityReference(PROVIDERS_CAPABILITY, CREDENTIAL_STORE_CAPABILITY)
            .build();

    static final SimpleAttributeDefinition RELATIVE_TO = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.RELATIVE_TO, ModelType.STRING, true)
            .setAllowExpression(false)
            .setMinSize(1)
            .setAttributeGroup(ElytronDescriptionConstants.FILE)
            .setRestartAllServices()
            .build();

    // Resource Resolver
    private static final StandardResourceDescriptionResolver RESOURCE_RESOLVER = ElytronExtension.getResourceDescriptionResolver(ElytronDescriptionConstants.CREDENTIAL_STORE);

    // Operations parameters


    static final SimpleAttributeDefinition KEY_SIZE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.KEY_SIZE, ModelType.INT, true)
            .setMinSize(1)
            .setDefaultValue(new ModelNode(256))
            .setAllowedValues(128, 192, 256)
            .build();

    static final SimpleAttributeDefinition ADD_ENTRY_TYPE;
    static final SimpleAttributeDefinition REMOVE_ENTRY_TYPE;

    static {
        String[] addEntryTypes = new String[] { PasswordCredential.class.getCanonicalName() };
        ADD_ENTRY_TYPE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.ENTRY_TYPE, ModelType.STRING, true)
                .setAllowedValues(addEntryTypes)
                .build();
        String[] removeEntryTypes = new String[] { PasswordCredential.class.getCanonicalName(), PasswordCredential.class.getSimpleName(),
                                                   SecretKeyCredential.class.getCanonicalName(), SecretKeyCredential.class.getSimpleName()};
        REMOVE_ENTRY_TYPE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.ENTRY_TYPE, ModelType.STRING, true)
                .setAllowedValues(removeEntryTypes)
                .setDefaultValue(new ModelNode(PasswordCredential.class.getSimpleName()))
                .build();
    }

    static final SimpleAttributeDefinition SECRET_VALUE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.SECRET_VALUE, ModelType.STRING, true)
            .setMinSize(0)
            .build();

    // Operations
    private static final SimpleOperationDefinition RELOAD = new SimpleOperationDefinitionBuilder(ElytronDescriptionConstants.RELOAD, RESOURCE_RESOLVER)
            .setRuntimeOnly()
            .build();

    private static final SimpleOperationDefinition READ_ALIASES = new SimpleOperationDefinitionBuilder(ElytronDescriptionConstants.READ_ALIASES, RESOURCE_RESOLVER)
            .setRuntimeOnly()
            .setReadOnly()
            .build();

    private static final SimpleOperationDefinition ADD_ALIAS = new SimpleOperationDefinitionBuilder(ElytronDescriptionConstants.ADD_ALIAS, RESOURCE_RESOLVER)
            .setParameters(ALIAS, ADD_ENTRY_TYPE, SECRET_VALUE)
            .setRuntimeOnly()
            .build();

    private static final SimpleOperationDefinition REMOVE_ALIAS = new SimpleOperationDefinitionBuilder(ElytronDescriptionConstants.REMOVE_ALIAS, RESOURCE_RESOLVER)
            .setParameters(ALIAS, REMOVE_ENTRY_TYPE)
            .setRuntimeOnly()
            .build();

    private static final SimpleOperationDefinition SET_SECRET = new SimpleOperationDefinitionBuilder(ElytronDescriptionConstants.SET_SECRET, RESOURCE_RESOLVER)
            .setParameters(ALIAS, ADD_ENTRY_TYPE, SECRET_VALUE)
            .setRuntimeOnly()
            .build();

    private static final SimpleOperationDefinition GENERATE_SECRET_KEY = new SimpleOperationDefinitionBuilder(ElytronDescriptionConstants.GENERATE_SECRET_KEY, RESOURCE_RESOLVER)
            .setParameters(ALIAS, KEY_SIZE)
            .setRuntimeOnly()
            .build();

    private static final SimpleOperationDefinition IMPORT_SECRET_KEY = new SimpleOperationDefinitionBuilder(ElytronDescriptionConstants.IMPORT_SECRET_KEY, RESOURCE_RESOLVER)
            .setParameters(ALIAS, KEY)
            .setRuntimeOnly()
            .build();

    static final SimpleOperationDefinition EXPORT_SECRET_KEY = new SimpleOperationDefinitionBuilder(ElytronDescriptionConstants.EXPORT_SECRET_KEY, RESOURCE_RESOLVER)
            .setParameters(ALIAS)
            .setRuntimeOnly()
            .build();

    private static final AttributeDefinition[] CONFIG_ATTRIBUTES = new AttributeDefinition[] {LOCATION, CREATE, MODIFIABLE, IMPLEMENTATION_PROPERTIES, CREDENTIAL_REFERENCE, TYPE, PROVIDER_NAME, PROVIDERS, OTHER_PROVIDERS, RELATIVE_TO};

    private static final CredentialStoreAddHandler ADD = new CredentialStoreAddHandler();
    private static final OperationStepHandler REMOVE = new TrivialCapabilityServiceRemoveHandler(ADD, CREDENTIAL_STORE_RUNTIME_CAPABILITY);


    CredentialStoreResourceDefinition() {
        super(new Parameters(PathElement.pathElement(ElytronDescriptionConstants.CREDENTIAL_STORE), RESOURCE_RESOLVER)
                .setAddHandler(ADD)
                .setRemoveHandler(REMOVE)
                .setAddRestartLevel(OperationEntry.Flag.RESTART_NONE)
                .setRemoveRestartLevel(OperationEntry.Flag.RESTART_NONE)
                .setCapabilities(CREDENTIAL_STORE_RUNTIME_CAPABILITY)
        );
    }

    @Override
    protected AttributeDefinition[] getAttributeDefinitions() {
        return CONFIG_ATTRIBUTES;
    }

    @Override
    public void registerOperations(ManagementResourceRegistration resourceRegistration) {
        super.registerOperations(resourceRegistration);
        resourceRegistration.registerOperationHandler(RELOAD, CredentialStoreHandler.INSTANCE);
        resourceRegistration.registerOperationHandler(READ_ALIASES, CredentialStoreReadAliasesHandler.INSTANCE);
        if (isServerOrHostController(resourceRegistration)) {
            resourceRegistration.registerOperationHandler(ADD_ALIAS, CredentialStoreHandler.INSTANCE);
            resourceRegistration.registerOperationHandler(REMOVE_ALIAS, CredentialStoreHandler.INSTANCE);
            resourceRegistration.registerOperationHandler(SET_SECRET, CredentialStoreHandler.INSTANCE);
            resourceRegistration.registerOperationHandler(GENERATE_SECRET_KEY, CredentialStoreHandler.INSTANCE);
            resourceRegistration.registerOperationHandler(EXPORT_SECRET_KEY, CredentialStoreHandler.INSTANCE);
            resourceRegistration.registerOperationHandler(IMPORT_SECRET_KEY, CredentialStoreHandler.INSTANCE);
        }
    }

    private static class CredentialStoreAddHandler extends BaseAddHandler {

        private CredentialStoreAddHandler() {
            super(CREDENTIAL_STORE_RUNTIME_CAPABILITY, CONFIG_ATTRIBUTES);
        }

        @Override
        protected void populateModel(final OperationContext context, final ModelNode operation, final Resource resource) throws  OperationFailedException {
            super.populateModel(context, operation, resource);
            handleCredentialReferenceUpdate(context, resource.getModel());
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, Resource resource) throws OperationFailedException {

            ModelNode model = resource.getModel();
            String location = LOCATION.resolveModelAttribute(context, model).asStringOrNull();
            boolean modifiable =  MODIFIABLE.resolveModelAttribute(context, model).asBoolean();
            boolean create = CREATE.resolveModelAttribute(context, model).asBoolean();
            final Map<String, String> implementationAttributes = new HashMap<>();
            ModelNode implAttrModel = IMPLEMENTATION_PROPERTIES.resolveModelAttribute(context, model);
            if (implAttrModel.isDefined()) {
                for (String s : implAttrModel.keys()) {
                    implementationAttributes.put(s, implAttrModel.require(s).asString());
                }
            }
            String type = TYPE.resolveModelAttribute(context, model).asStringOrNull();
            String providers = PROVIDERS.resolveModelAttribute(context, model).asStringOrNull();
            String otherProviders = OTHER_PROVIDERS.resolveModelAttribute(context, model).asStringOrNull();
            String providerName = PROVIDER_NAME.resolveModelAttribute(context, model).asStringOrNull();
            String name = credentialStoreName(operation);
            String relativeTo = RELATIVE_TO.resolveModelAttribute(context, model).asStringOrNull();
            ServiceTarget serviceTarget = context.getServiceTarget();

            if (type == null || type.equals(KeyStoreCredentialStore.KEY_STORE_CREDENTIAL_STORE)) {
                implementationAttributes.putIfAbsent(CS_KEY_STORE_TYPE_ATTRIBUTE, "JCEKS");
            }

            String implAttrKeyStoreType = implementationAttributes.get(CS_KEY_STORE_TYPE_ATTRIBUTE);
            if (location == null && implAttrKeyStoreType != null && filebasedKeystoreTypes.contains(implAttrKeyStoreType.toUpperCase(Locale.ENGLISH))) {
                throw ROOT_LOGGER.filebasedKeystoreLocationMissing(implAttrKeyStoreType);
            }

            // ----------- credential store service ----------------
            final CredentialStoreService csService;
            try {
                csService = CredentialStoreService.createCredentialStoreService(name, location, modifiable, create, implementationAttributes, type, providerName, relativeTo, providers, otherProviders);
            } catch (CredentialStoreException e) {
                throw new OperationFailedException(e);
            }
            ServiceName credentialStoreServiceName = CREDENTIAL_STORE_UTIL.serviceName(operation);
            ServiceBuilder<CredentialStore> credentialStoreServiceBuilder = serviceTarget.addService(credentialStoreServiceName, csService)
                    .setInitialMode(Mode.ACTIVE);

            if (relativeTo != null) {
                credentialStoreServiceBuilder.addDependency(PathManagerService.SERVICE_NAME, PathManager.class, csService.getPathManagerInjector());
                credentialStoreServiceBuilder.requires(pathName(relativeTo));
            }
            if (providers != null) {
                String providersCapabilityName = RuntimeCapability.buildDynamicCapabilityName(PROVIDERS_CAPABILITY, providers);
                ServiceName providerLoaderServiceName = context.getCapabilityServiceName(providersCapabilityName, Provider[].class);
                credentialStoreServiceBuilder.addDependency(providerLoaderServiceName, Provider[].class, csService.getProvidersInjector());
            }
            if (otherProviders != null) {
                String providersCapabilityName = RuntimeCapability.buildDynamicCapabilityName(PROVIDERS_CAPABILITY, otherProviders);
                ServiceName otherProvidersLoaderServiceName = context.getCapabilityServiceName(providersCapabilityName, Provider[].class);
                credentialStoreServiceBuilder.addDependency(otherProvidersLoaderServiceName, Provider[].class, csService.getOtherProvidersInjector());
            }

            csService.getCredentialSourceSupplierInjector()
                    .inject(CredentialReference.getCredentialSourceSupplier(context, CredentialStoreResourceDefinition.CREDENTIAL_REFERENCE, model, credentialStoreServiceBuilder));

            commonDependencies(credentialStoreServiceBuilder).install();
        }

        @Override
        protected void rollbackRuntime(OperationContext context, final ModelNode operation, final Resource resource) {
            rollbackCredentialStoreUpdate(CREDENTIAL_REFERENCE, context, resource);
        }
    }

    /*
     * Runtime Attribute and Operation Handlers
     */

    abstract static class CredentialStoreRuntimeOnlyHandler extends ElytronRuntimeOnlyHandler {

        private final boolean serviceMustBeUp;
        private final boolean writeAccess;

        CredentialStoreRuntimeOnlyHandler(final boolean serviceMustBeUp, final boolean writeAccess) {
            this.serviceMustBeUp = serviceMustBeUp;
            this.writeAccess = writeAccess;
        }

        CredentialStoreRuntimeOnlyHandler(final boolean serviceMustBeUp) {
            this(serviceMustBeUp, false);
        }


        @Override
        protected void executeRuntimeStep(OperationContext context, ModelNode operation) throws OperationFailedException {
            ServiceName credentialStoreServiceName = CREDENTIAL_STORE_UTIL.serviceName(operation);
            ServiceController<?> credentialStoreServiceController = context.getServiceRegistry(writeAccess).getRequiredService(credentialStoreServiceName);
            State serviceState;
            if ((serviceState = credentialStoreServiceController.getState()) != State.UP) {
                if (serviceMustBeUp) {
                    try {
                        // give it another chance to wait at most 500 mill-seconds
                        credentialStoreServiceController.awaitValue(500, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException | IllegalStateException | TimeoutException e) {
                        throw ROOT_LOGGER.requiredServiceNotUp(credentialStoreServiceName, credentialStoreServiceController.getState());
                    }
                }
                serviceState = credentialStoreServiceController.getState();
                if (serviceState != State.UP) {
                    if (serviceMustBeUp) {
                        throw ROOT_LOGGER.requiredServiceNotUp(credentialStoreServiceName, serviceState);
                    }
                    return;
                }
            }
            CredentialStoreService service = (CredentialStoreService) credentialStoreServiceController.getService();
            performRuntime(context.getResult(), context, operation, service);
        }

        protected abstract void performRuntime(ModelNode result, OperationContext context, ModelNode operation,  CredentialStoreService credentialStoreService) throws OperationFailedException ;

    }


    private static class CredentialStoreHandler extends CredentialStoreRuntimeOnlyHandler {

        private static final CredentialStoreHandler INSTANCE = new CredentialStoreHandler();

        private CredentialStoreHandler() {
            super(true, true);
        }

        @Override
        protected void performRuntime(ModelNode result, OperationContext context, ModelNode operation, CredentialStoreService credentialStoreService) throws OperationFailedException {

            String operationName = operation.require(ModelDescriptionConstants.OP).asString();
            switch (operationName) {
                case ElytronDescriptionConstants.RELOAD:
                    try {
                        credentialStoreService.stop(null);
                        credentialStoreService.start(null);
                    } catch (StartException e) {
                        throw ROOT_LOGGER.unableToCompleteOperation(e, dumpCause(e));
                    }
                    break;

                case ElytronDescriptionConstants.ADD_ALIAS:
                    try {
                        String alias = ALIAS.resolveModelAttribute(context, operation).asString();
                        String entryType = ADD_ENTRY_TYPE.resolveModelAttribute(context, operation).asStringOrNull();
                        String secretValue = SECRET_VALUE.resolveModelAttribute(context, operation).asStringOrNull();
                        CredentialStore credentialStore = credentialStoreService.getValue();
                        if (entryType == null || entryType.equals(PasswordCredential.class.getCanonicalName())) {
                            if (credentialStore.exists(alias, PasswordCredential.class)) {
                                throw ROOT_LOGGER.credentialAlreadyExists(alias, PasswordCredential.class.getName());
                            }
                            storeSecret(credentialStore, alias, secretValue);
                        } else {
                            String credentialStoreName = CredentialStoreResourceDefinition.credentialStoreName(operation);
                            throw ROOT_LOGGER.credentialStoreEntryTypeNotSupported(credentialStoreName, entryType);
                        }
                    } catch (CredentialStoreException e) {
                        throw ROOT_LOGGER.unableToCompleteOperation(e, dumpCause(e));
                    }
                    break;

                case ElytronDescriptionConstants.REMOVE_ALIAS:
                    try {
                        String alias = ALIAS.resolveModelAttribute(context, operation).asString();
                        String entryType = REMOVE_ENTRY_TYPE.resolveModelAttribute(context, operation).asString();
                        CredentialStore credentialStore = credentialStoreService.getValue();
                        Class<? extends Credential> credentialType = fromEntryType(entryType);
                        Credential retrieved = credentialStore.retrieve(alias, credentialType);
                        if (retrieved == null) {
                            throw ROOT_LOGGER.credentialDoesNotExist(alias, entryType);
                        }
                        credentialStore.remove(alias, credentialType);
                        context.addResponseWarning(Level.WARNING, ROOT_LOGGER.updateDependantServices(alias));
                        try {
                            credentialStore.flush();
                        } catch (CredentialStoreException e) {
                            // the operation fails, return removed entry back to the store to avoid an inconsistency
                            // between the store on the FS and in the memory
                            credentialStore.store(alias, retrieved);
                            throw e;
                        }
                    } catch (CredentialStoreException e) {
                        throw ROOT_LOGGER.unableToCompleteOperation(e, dumpCause(e));
                    }
                    break;

                case ElytronDescriptionConstants.SET_SECRET:
                    try {
                        String alias = ALIAS.resolveModelAttribute(context, operation).asString();
                        String entryType = ADD_ENTRY_TYPE.resolveModelAttribute(context, operation).asStringOrNull();
                        String secretValue = SECRET_VALUE.resolveModelAttribute(context, operation).asStringOrNull();
                        CredentialStore credentialStore = credentialStoreService.getValue();

                        if (entryType == null || entryType.equals(PasswordCredential.class.getCanonicalName())) {
                            if ( ! credentialStore.exists(alias, PasswordCredential.class)) {
                                throw ROOT_LOGGER.credentialDoesNotExist(alias, PasswordCredential.class.getName());
                            }
                            storeSecret(credentialStore, alias, secretValue);
                            context.addResponseWarning(Level.WARNING, ROOT_LOGGER.reloadDependantServices());
                        } else {
                            String credentialStoreName = CredentialStoreResourceDefinition.credentialStoreName(operation);
                            throw ROOT_LOGGER.credentialStoreEntryTypeNotSupported(credentialStoreName, entryType);
                        }
                    } catch (CredentialStoreException e) {
                        throw ROOT_LOGGER.unableToCompleteOperation(e, dumpCause(e));
                    }
                    break;
                case ElytronDescriptionConstants.GENERATE_SECRET_KEY:
                    try {
                        String alias = ALIAS.resolveModelAttribute(context, operation).asString();
                        int keySize = KEY_SIZE.resolveModelAttribute(context, operation).asInt();
                        CredentialStore credentialStore = credentialStoreService.getValue();

                        SecretKey secretKey = generateSecretKey(keySize);
                        storeSecretKey(credentialStore, alias, secretKey);

                    } catch (GeneralSecurityException e) {
                        throw ROOT_LOGGER.unableToCompleteOperation(e, dumpCause(e));
                    }
                    break;
                case ElytronDescriptionConstants.EXPORT_SECRET_KEY:
                    try {
                        String alias = ALIAS.resolveModelAttribute(context, operation).asString();

                        CredentialStore credentialStore = credentialStoreService.getValue();
                        SecretKey secretKey = credentialStore.retrieve(alias, SecretKeyCredential.class).getSecretKey();
                        String exportedKey = exportSecretKey(secretKey);

                        result.get(ElytronDescriptionConstants.KEY).set(exportedKey);
                    } catch (GeneralSecurityException e) {
                        throw ROOT_LOGGER.unableToCompleteOperation(e, dumpCause(e));
                    }
                    break;
                case ElytronDescriptionConstants.IMPORT_SECRET_KEY:
                    try {
                        String alias = ALIAS.resolveModelAttribute(context, operation).asString();
                        String rawKey = KEY.resolveModelAttribute(context, operation).asString();

                        SecretKey secretKey = importSecretKey(rawKey);

                        CredentialStore credentialStore = credentialStoreService.getValue();
                        storeSecretKey(credentialStore, alias, secretKey);

                    } catch (GeneralSecurityException e) {
                        throw ROOT_LOGGER.unableToCompleteOperation(e, dumpCause(e));
                    }
                    break;
                default:
                    // TODO - Why does this report a single expected operation name?
                    throw ROOT_LOGGER.invalidOperationName(operationName, ElytronDescriptionConstants.LOAD);
            }
        }
    }

    private static class CredentialStoreReadAliasesHandler extends CredentialStoreRuntimeOnlyHandler {
        private static final CredentialStoreReadAliasesHandler INSTANCE = new CredentialStoreReadAliasesHandler();

        private CredentialStoreReadAliasesHandler() {
            super(true);
        }

        @Override
        protected void performRuntime(ModelNode result, OperationContext context, ModelNode operation, CredentialStoreService credentialStoreService) throws OperationFailedException {
            try {
                List<ModelNode> list = new ArrayList<>();
                for (String s : credentialStoreService.getValue().getAliases()) {
                    ModelNode modelNode = new ModelNode(s);
                    list.add(modelNode);
                }
                result.set(list);
            } catch (CredentialStoreException e) {
                throw ROOT_LOGGER.unableToCompleteOperation(e, dumpCause(e));
            }
        }
    }

    static String credentialStoreName(ModelNode operation) {
        String credentialStoreName = null;
        PathAddress pa = PathAddress.pathAddress(operation.require(ModelDescriptionConstants.OP_ADDR));
        for (int i = pa.size() - 1; i > 0; i--) {
            PathElement pe = pa.getElement(i);
            if (ElytronDescriptionConstants.CREDENTIAL_STORE.equals(pe.getKey())) {
                credentialStoreName = pe.getValue();
                break;
            }
        }

        if (credentialStoreName == null) {
            throw ROOT_LOGGER.operationAddressMissingKey(ElytronDescriptionConstants.CREDENTIAL_STORE);
        }

        return credentialStoreName;
    }


    private static Class<? extends Credential> fromEntryType(final String entryTyoe) {
        if (PasswordCredential.class.getCanonicalName().equals(entryTyoe) || PasswordCredential.class.getSimpleName().equals(entryTyoe)) {
            return PasswordCredential.class;
        } else if (SecretKeyCredential.class.getCanonicalName().equals(entryTyoe) || SecretKeyCredential.class.getSimpleName().equals(entryTyoe)) {
            return SecretKeyCredential.class;
        }

        return null;
    }
}
