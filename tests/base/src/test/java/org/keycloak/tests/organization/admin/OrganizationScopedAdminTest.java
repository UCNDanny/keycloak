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

package org.keycloak.tests.organization.admin;

import java.net.URI;
import java.util.List;
import java.util.Set;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.OrganizationResource;
import org.keycloak.admin.client.resource.OrganizationsResource;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.Constants;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.MembershipType;
import org.keycloak.representations.idm.OrganizationDomainRepresentation;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.authorization.UserPolicyRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.representations.userprofile.config.UPConfig.UnmanagedAttributePolicy;
import org.keycloak.testframework.annotations.InjectAdminClient;
import org.keycloak.testframework.annotations.InjectKeycloakUrls;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.mail.MailServer;
import org.keycloak.testframework.mail.annotations.InjectMailServer;
import org.keycloak.testframework.realm.ClientBuilder;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.RealmBuilder;
import org.keycloak.testframework.realm.RealmConfig;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.server.KeycloakUrls;
import org.keycloak.testframework.util.ApiUtil;
import org.keycloak.tests.admin.authz.fgap.PermissionTestUtils;
import org.keycloak.tests.utils.admin.AdminApiUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.keycloak.authorization.fgap.AdminPermissionsSchema.MANAGE;
import static org.keycloak.authorization.fgap.AdminPermissionsSchema.ORGANIZATIONS_RESOURCE_TYPE;
import static org.keycloak.authorization.fgap.AdminPermissionsSchema.VIEW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Organization-scoped admins: users holding only {@code query-organizations} plus an FGAP v2 permission granting
 * {@code view} and {@code manage} on a specific organization.
 */
@KeycloakIntegrationTest
public class OrganizationScopedAdminTest {

    private static final String ORG_ADMIN = "orgadmin";
    private static final String REALM_ADMIN = "realmadmin";
    private static final String ORG_MANAGER = "orgmanager";

    @InjectRealm(config = OrganizationScopedAdminConfig.class, lifecycle = LifeCycle.METHOD)
    ManagedRealm realm;

    @InjectAdminClient(ref = ORG_ADMIN, mode = InjectAdminClient.Mode.MANAGED_REALM, client = "myclient", user = ORG_ADMIN)
    Keycloak orgAdminClient;

    @InjectAdminClient(ref = REALM_ADMIN, mode = InjectAdminClient.Mode.MANAGED_REALM, client = "myclient", user = REALM_ADMIN)
    Keycloak realmAdminClient;

    @InjectAdminClient(ref = ORG_MANAGER, mode = InjectAdminClient.Mode.MANAGED_REALM, client = "myclient", user = ORG_MANAGER)
    Keycloak orgManagerClient;

    @InjectAdminClient
    Keycloak adminClient;

    @InjectKeycloakUrls
    KeycloakUrls keycloakUrls;

    @InjectMailServer
    MailServer mailServer;

    private String orgAId;
    private String orgBId;
    private String memberAId;
    private String memberBId;

    @BeforeEach
    public void setup() {
        orgAId = createOrg("orgA", "orga.org");
        orgBId = createOrg("orgB", "orgb.org");
        memberAId = createUnmanagedMember(orgAId, "membera", "membera@orga.org");
        memberBId = createUnmanagedMember(orgBId, "memberb", "memberb@orgb.org");

        ClientResource permissionsClient = AdminApiUtil.findClientByClientId(realm.admin(), Constants.ADMIN_PERMISSIONS_CLIENT_ID);
        String orgAdminId = realm.admin().users().search(ORG_ADMIN, true).get(0).getId();
        UserPolicyRepresentation policy = PermissionTestUtils.createUserPolicy(realm, permissionsClient, "Organization A admins", orgAdminId);
        PermissionTestUtils.createPermission(permissionsClient, orgAId, ORGANIZATIONS_RESOURCE_TYPE, Set.of(VIEW, MANAGE), policy);
    }

    @Test
    public void testListReturnsOnlyOwnOrganization() {
        List<OrganizationRepresentation> orgs = orgAdminOrgs().list(-1, -1);

        assertEquals(1, orgs.size());
        OrganizationRepresentation orgA = orgs.get(0);
        assertEquals(orgAId, orgA.getId());
        assertEquals(Boolean.TRUE, orgA.getAccess().get(VIEW));
        assertEquals(Boolean.TRUE, orgA.getAccess().get(MANAGE));
    }

    @Test
    public void testGetAndUpdateOwnOrganization() {
        OrganizationResource orgA = orgAdminOrgs().get(orgAId);
        OrganizationRepresentation rep = orgA.toRepresentation();
        assertEquals("orgA", rep.getName());
        assertEquals(Boolean.TRUE, rep.getAccess().get(VIEW));
        assertEquals(Boolean.TRUE, rep.getAccess().get(MANAGE));

        rep.setName("orgA-updated");
        rep.setAccess(null);
        try (Response response = orgA.update(rep)) {
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        }
        assertEquals("orgA-updated", realm.admin().organizations().get(orgAId).toRepresentation().getName());
    }

    @Test
    public void testCannotAccessOtherOrganization() {
        OrganizationResource orgB = orgAdminOrgs().get(orgBId);
        assertThrows(ForbiddenException.class, orgB::toRepresentation);

        OrganizationRepresentation rep = realm.admin().organizations().get(orgBId).toRepresentation();
        rep.setName("orgB-updated");
        try (Response response = orgB.update(rep)) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
        try (Response response = orgB.delete()) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
        assertEquals("orgB", realm.admin().organizations().get(orgBId).toRepresentation().getName());
    }

    @Test
    public void testCannotCreateOrganization() {
        OrganizationRepresentation rep = new OrganizationRepresentation();
        rep.setName("orgC");
        rep.setAlias("orgC");
        OrganizationDomainRepresentation domain = new OrganizationDomainRepresentation();
        domain.setName("orgc.org");
        rep.addDomain(domain);

        try (Response response = orgAdminOrgs().create(rep)) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
    }

    @Test
    public void testListAndCountOwnMembersWithoutUserRoles() {
        List<MemberRepresentation> members = orgAdminOrgs().get(orgAId).members().list(-1, -1);
        assertEquals(1, members.size());
        assertEquals(memberAId, members.get(0).getId());
        assertEquals(MembershipType.UNMANAGED, members.get(0).getMembershipType());
        assertEquals(1L, orgAdminOrgs().get(orgAId).members().count());

        MemberRepresentation member = orgAdminOrgs().get(orgAId).members().member(memberAId).toRepresentation();
        assertEquals("membera", member.getUsername());

        assertThrows(ForbiddenException.class, () -> orgAdminOrgs().get(orgBId).members().list(-1, -1));
        assertThrows(ForbiddenException.class, () -> orgAdminOrgs().get(orgBId).members().count());
        assertThrows(ForbiddenException.class, () -> orgAdminOrgs().get(orgBId).members().member(memberBId).toRepresentation());
    }

    @Test
    public void testCannotChangeDomains() {
        OrganizationResource orgA = orgAdminOrgs().get(orgAId);
        OrganizationRepresentation rep = orgA.toRepresentation();
        rep.setAccess(null);
        OrganizationDomainRepresentation domain = new OrganizationDomainRepresentation();
        domain.setName("other.org");
        rep.addDomain(domain);
        try (Response response = orgA.update(rep)) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
        assertEquals(1, realm.admin().organizations().get(orgAId).toRepresentation().getDomains().size());

        rep = orgA.toRepresentation();
        rep.setAccess(null);
        rep.setDescription("updated description");
        try (Response response = orgA.update(rep)) {
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        }
        OrganizationRepresentation updated = realm.admin().organizations().get(orgAId).toRepresentation();
        assertEquals("updated description", updated.getDescription());
        assertEquals("orga.org", updated.getDomains().iterator().next().getName());
    }

    @Test
    public void testOrganizationManagerCannotListMembersWithoutUserPermission() {
        assertThrows(ForbiddenException.class, () -> orgManagerClient.realm(realm.getName()).organizations().get(orgAId).members().list(-1, -1));
    }

    @Test
    public void testOrganizationManagerCannotListMembersWithoutAdminPermissions() {
        realm.updateWithCleanup(r -> r.adminPermissionsEnabled(false));

        assertThrows(ForbiddenException.class, () -> orgManagerClient.realm(realm.getName()).organizations().get(orgAId).members().list(-1, -1));
    }

    @Test
    public void testCreateMember() {
        String userId;
        try (Response response = createUser(orgAdminClient, orgAId).create(newUser("created", "created@orga.org"))) {
            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            userId = ApiUtil.getCreatedId(response);
        }

        MemberRepresentation member = realm.admin().organizations().get(orgAId).members().member(userId).toRepresentation();
        assertEquals("created", member.getUsername());
        assertEquals("created@orga.org", member.getEmail());
        assertEquals(MembershipType.UNMANAGED, member.getMembershipType());

        try (Response response = createUser(orgAdminClient, orgAId).create(newUser("created", "other@orga.org"))) {
            assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        }

        try (Response response = createUser(orgAdminClient, orgAId).create(newUser("outside", "outside@example.org"))) {
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        }
        assertTrue(realm.admin().users().search("outside", true).isEmpty());

        try (Response response = createUser(orgAdminClient, orgBId).create(newUser("createdb", "createdb@orgb.org"))) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
        assertTrue(realm.admin().users().search("createdb", true).isEmpty());
    }

    @Test
    public void testMemberRepresentationIsBrief() {
        UPConfig upConfig = realm.admin().users().userProfile().getConfiguration();
        upConfig.setUnmanagedAttributePolicy(UnmanagedAttributePolicy.ENABLED);
        realm.admin().users().userProfile().update(upConfig);
        UserRepresentation user = realm.admin().users().get(memberAId).toRepresentation();
        user.singleAttribute("secret", "value");
        realm.admin().users().get(memberAId).update(user);
        assertNotNull(realm.admin().organizations().get(orgAId).members().member(memberAId).toRepresentation().getAttributes());

        assertNull(orgAdminOrgs().get(orgAId).members().member(memberAId).toRepresentation().getAttributes());
        List<MemberRepresentation> members = orgAdminOrgs().get(orgAId).members().search("membera", true, null, -1, -1, false);
        assertEquals(1, members.size());
        assertNull(members.get(0).getAttributes());
    }

    @Test
    public void testCannotChangeMembershipType() {
        String managedId = createManagedMember();

        try (Response response = orgAdminOrgs().get(orgAId).members().member(memberAId).updateMembershipType(MembershipType.MANAGED)) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
        assertEquals(MembershipType.UNMANAGED, realm.admin().organizations().get(orgAId).members().member(memberAId).toRepresentation().getMembershipType());

        try (Response response = orgAdminOrgs().get(orgAId).members().member(managedId).updateMembershipType(MembershipType.UNMANAGED)) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
        assertEquals(MembershipType.MANAGED, realm.admin().organizations().get(orgAId).members().member(managedId).toRepresentation().getMembershipType());
    }

    @Test
    public void testCannotRemoveManagedMember() {
        String managedId = createManagedMember();

        try (Response response = orgAdminOrgs().get(orgAId).members().member(managedId).delete()) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
        assertEquals(MembershipType.MANAGED, realm.admin().organizations().get(orgAId).members().member(managedId).toRepresentation().getMembershipType());
        assertNotNull(realm.admin().users().get(managedId).toRepresentation());
    }

    @Test
    public void testRemoveMember() {
        try (Response response = orgAdminOrgs().get(orgAId).members().member(memberAId).delete()) {
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        }
        assertEquals(0L, realm.admin().organizations().get(orgAId).members().count());
        assertNotNull(realm.admin().users().get(memberAId).toRepresentation());

        try (Response response = orgAdminOrgs().get(orgBId).members().member(memberBId).delete()) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
        assertEquals(1L, realm.admin().organizations().get(orgBId).members().count());
    }

    @Test
    public void testInviteExistingUserByUsername() {
        try (Response response = realm.admin().users().create(UserBuilder.create().username("invitee").email("invitee@example.com").build())) {
            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        }

        try (Response response = orgAdminOrgs().get(orgAId).members().inviteExistingUserByUsername("invitee")) {
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        }
        assertTrue(mailServer.waitForIncomingEmail(10_000, 1), "invitation email not received");

        try (Response response = orgAdminOrgs().get(orgAId).members().inviteExistingUserByUsername("unknown")) {
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        }
        assertFalse(mailServer.waitForIncomingEmail(2_000, 2), "no email expected for an unknown user");
        assertEquals(1, mailServer.getReceivedMessages().length);

        try (Response response = orgAdminOrgs().get(orgBId).members().inviteExistingUserByUsername("invitee")) {
            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), response.getStatus());
        }
    }

    @Test
    public void testCannotAccessRealmUsers() {
        assertThrows(ForbiddenException.class, () -> orgAdminClient.realm(realm.getName()).users().list());
        assertThrows(ForbiddenException.class, () -> orgAdminClient.realm(realm.getName()).users().count());
        assertThrows(ForbiddenException.class, () -> orgAdminClient.realm(realm.getName()).users().get(memberAId).toRepresentation());
    }

    @Test
    public void testRealmAdminCanManageAllOrganizations() {
        List<OrganizationRepresentation> orgs = realmAdminClient.realm(realm.getName()).organizations().list(-1, -1);

        assertEquals(2, orgs.size());
        for (OrganizationRepresentation org : orgs) {
            assertEquals(Boolean.TRUE, org.getAccess().get(VIEW), org.getName());
            assertEquals(Boolean.TRUE, org.getAccess().get(MANAGE), org.getName());
        }
    }

    private String createManagedMember() {
        String userId = createUnmanagedMember(orgAId, "managed", "managed@orga.org");
        try (Response response = realm.admin().organizations().get(orgAId).members().member(userId).updateMembershipType(MembershipType.MANAGED)) {
            assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        }
        return userId;
    }

    private OrganizationsResource orgAdminOrgs() {
        return orgAdminClient.realm(realm.getName()).organizations();
    }

    private CreateUserResource createUser(Keycloak client, String orgId) {
        URI uri = URI.create(keycloakUrls.getAdmin() + "/realms/" + realm.getName() + "/organizations/" + orgId + "/members");
        return client.proxy(CreateUserResource.class, uri);
    }

    private static UserRepresentation newUser(String username, String email) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName("First");
        user.setLastName("Last");
        return user;
    }

    private String createOrg(String name, String domainName) {
        OrganizationRepresentation orgRep = new OrganizationRepresentation();
        orgRep.setName(name);
        orgRep.setAlias(name);
        OrganizationDomainRepresentation domain = new OrganizationDomainRepresentation();
        domain.setName(domainName);
        orgRep.addDomain(domain);

        try (Response response = realm.admin().organizations().create(orgRep)) {
            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            return ApiUtil.getCreatedId(response);
        }
    }

    private String createUnmanagedMember(String orgId, String username, String email) {
        String userId;
        try (Response response = realm.admin().users().create(UserBuilder.create().username(username).email(email).build())) {
            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            userId = ApiUtil.getCreatedId(response);
        }
        try (Response response = realm.admin().organizations().get(orgId).members().addMember(userId)) {
            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        }
        return userId;
    }

    /**
     * The admin client does not expose the endpoint for creating a user as a member.
     */
    public interface CreateUserResource {

        @Path("create-user")
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        Response create(UserRepresentation user);
    }

    public static class OrganizationScopedAdminConfig implements RealmConfig {

        @Override
        public RealmBuilder configure(RealmBuilder realm) {
            realm.users(UserBuilder.create()
                    .username(ORG_ADMIN)
                    .name("Org", "Admin")
                    .email("orgadmin@localhost")
                    .emailVerified(true)
                    .password("password")
                    .clientRoles(Constants.REALM_MANAGEMENT_CLIENT_ID, AdminRoles.QUERY_ORGANIZATIONS).build());
            realm.users(UserBuilder.create()
                    .username(REALM_ADMIN)
                    .name("Realm", "Admin")
                    .email("realmadmin@localhost")
                    .emailVerified(true)
                    .password("password")
                    .clientRoles(Constants.REALM_MANAGEMENT_CLIENT_ID, AdminRoles.REALM_ADMIN).build());
            realm.users(UserBuilder.create()
                    .username(ORG_MANAGER)
                    .name("Org", "Manager")
                    .email("orgmanager@localhost")
                    .emailVerified(true)
                    .password("password")
                    .clientRoles(Constants.REALM_MANAGEMENT_CLIENT_ID, AdminRoles.MANAGE_ORGANIZATIONS).build());
            realm.clients(ClientBuilder.create()
                    .clientId("myclient")
                    .secret("mysecret")
                    .directAccessGrantsEnabled(true).build());
            return realm
                    .adminPermissionsEnabled(true)
                    .organizationsEnabled(true);
        }
    }
}
