/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.domain.management.access;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ROLE_MAPPING;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.access.management.DelegatingConfigurableAuthorizer;
import org.jboss.as.controller.access.management.WritableAuthorizerConfiguration;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.domain.management._private.DomainManagementResolver;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * A {@link org.jboss.as.controller.ResourceDefinition} representing an individual role mapping.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public class RoleMappingResourceDefinition extends SimpleResourceDefinition {

    public static final String PATH_KEY = ROLE_MAPPING;

    public static final SimpleAttributeDefinition INCLUDE_ALL = new SimpleAttributeDefinitionBuilder(ModelDescriptionConstants.INCLUDE_ALL, ModelType.BOOLEAN, true)
            .setDefaultValue(ModelNode.FALSE)
            .build();

    private final DelegatingConfigurableAuthorizer authorizer;

    private RoleMappingResourceDefinition(final DelegatingConfigurableAuthorizer authorizer, final boolean domainMode) {
        super(PathElement.pathElement(PATH_KEY), DomainManagementResolver.getResolver("core.access-control.role-mapping"),
                RoleMappingAdd.create(authorizer.getWritableAuthorizerConfiguration(), domainMode),
                RoleMappingRemove.create(authorizer.getWritableAuthorizerConfiguration()));
        this.authorizer = authorizer;
    }

    public static SimpleResourceDefinition create(final DelegatingConfigurableAuthorizer authorizer, final boolean domainMode) {
        return new RoleMappingResourceDefinition(authorizer, domainMode);
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        WritableAuthorizerConfiguration authorizerConfiguration = authorizer.getWritableAuthorizerConfiguration();
        resourceRegistration.registerReadWriteAttribute(INCLUDE_ALL, null, new RoleIncludeAllWriteAttributeHandler(authorizerConfiguration));
    }

    @Override
    public void registerChildren(ManagementResourceRegistration resourceRegistration) {
        WritableAuthorizerConfiguration authorizerConfiguration = authorizer.getWritableAuthorizerConfiguration();
        resourceRegistration.registerSubModel(PrincipalResourceDefinition.includeResourceDefinition(authorizerConfiguration));
        resourceRegistration.registerSubModel(PrincipalResourceDefinition.excludeResourceDefinition(authorizerConfiguration));
    }

    @Override
    public void registerOperations(ManagementResourceRegistration resourceRegistration) {
        super.registerOperations(resourceRegistration);
        resourceRegistration.registerOperationHandler(IsCallerInRoleOperation.DEFINITION, IsCallerInRoleOperation.create(authorizer));
    }

    static String getRoleName(final ModelNode operation) {
        PathAddress address = PathAddress.pathAddress(operation.get(OP_ADDR));
        for (PathElement current : address) {
            if (ROLE_MAPPING.equals(current.getKey())) {
                return current.getValue();
            }
        }
        throw new IllegalStateException();
    }

}
