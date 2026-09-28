/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
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
package org.keycloak.services.resources.admin.fgap;

import org.keycloak.models.OrganizationModel;

public interface OrganizationPermissionEvaluator {

    boolean canManage();

    boolean canManage(OrganizationModel organization);

    /**
     * Returns {@code true} if the caller holds a realm-wide organization administration role
     * ({@code manage-organizations} or {@code manage-realm}), which grants management of all organizations,
     * including settings that affect the whole realm such as organization domains.
     */
    boolean canManageAll();

    /**
     * Returns {@code true} only if admin permissions (FGAP v2) are enabled for the realm and a permission grants
     * {@code manage} on the given organization. Admin roles are not taken into account.
     */
    boolean hasManagePermission(OrganizationModel organization);

    void requireManage();

    void requireManage(OrganizationModel organization);

    boolean canView();

    boolean canView(OrganizationModel organization);

    void requireView();

    void requireView(OrganizationModel organization);

    boolean canQuery();

    void requireQuery();
}
