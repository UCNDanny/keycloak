/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.organization.admin.resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.keycloak.authorization.fgap.AdminPermissionsSchema;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.ModelException;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.organization.utils.Organizations;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MemberRepresentation;
import org.keycloak.representations.idm.MembershipType;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.resources.KeycloakOpenAPI;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.UserResource;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;
import org.keycloak.userprofile.UserProfile;
import org.keycloak.userprofile.UserProfileProvider;
import org.keycloak.utils.StringUtil;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import static org.keycloak.userprofile.UserProfileContext.USER_API;

@Extension(name = KeycloakOpenAPI.Profiles.ADMIN, value = "")
public class OrganizationMemberResource {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final OrganizationProvider provider;
    private final OrganizationModel organization;
    private final AdminEventBuilder adminEvent;
    private final AdminPermissionEvaluator auth;

    public OrganizationMemberResource(KeycloakSession session, OrganizationModel organization, AdminEventBuilder adminEvent, AdminPermissionEvaluator auth) {
        this.session = session;
        this.realm = session.getContext().getRealm();
        this.provider = session.getProvider(OrganizationProvider.class);
        this.organization = organization;
        this.adminEvent = adminEvent.resource(ResourceType.ORGANIZATION_MEMBERSHIP);
        this.auth = auth;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation(summary = "Adds the user with the specified id as a member of the organization", description = "Adds, or associates, " +
            "an existing user with the organization. If no user is found, or if it is already associated with the organization, " +
            "an error response is returned")
    @RequestBody(description = "Payload should contain only id of the user to be added to the organization (UUID with or without quotes). " +
            "Surrounding whitespace characters will be trimmed.", required = true)
    @APIResponses(value = {
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Bad Request"),
        @APIResponse(responseCode = "403", description = "Forbidden"),
        @APIResponse(responseCode = "409", description = "Conflict")
    })
    public Response addMember(String id) {
        auth.orgs().requireManage(organization);
        id = id.trim().replaceAll("^\"|\"$", ""); // fixes https://github.com/keycloak/keycloak/issues/34401

        UserModel user = getUser(id);
        auth.users().requireManage(user);

        try {
            if (provider.addMember(organization, user)) {
                adminEvent.operation(OperationType.CREATE).resource(ResourceType.ORGANIZATION_MEMBERSHIP)
                        .representation(ModelToRepresentation.toRepresentation(organization))
                        .resourcePath(session.getContext().getUri())
                        .detail(UserModel.USERNAME, user.getUsername())
                        .detail(UserModel.EMAIL, user.getEmail())
                        .success();
                return Response.created(session.getContext().getUri().getAbsolutePathBuilder().path(user.getId()).build()).build();
            }
        } catch (ModelException me) {
            throw ErrorResponse.error(me.getMessage(), Status.BAD_REQUEST);
        }

        throw ErrorResponse.error("User is already a member of the organization.", Status.CONFLICT);
    }

    /**
     * Precondition: caller must have passed through {@link OrganizationsResource#get(String)}
     * which enforces {@code auth.orgs().requireView(organization)}. This method additionally
     * requires {@code auth.orgs().requireManage(organization)} and either the organization admin permission
     * ({@link #canManageMembers()}) or {@code auth.users().requireManage()}.
     */
    @Path("create-user")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation(summary = "Creates a new user and adds it as a member of the organization", description = "Only the username, " +
            "email, first name, last name, and enabled fields of the representation are taken into account. The email is not " +
            "verified. If the organization has domains, the email must belong to one of them.")
    @RequestBody(description = "The user to be created", required = true)
    @APIResponses(value = {
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Bad Request"),
        @APIResponse(responseCode = "403", description = "Forbidden"),
        @APIResponse(responseCode = "409", description = "Conflict")
    })
    public Response createUser(UserRepresentation rep) {
        auth.orgs().requireManage(organization);

        if (!canManageMembers()) {
            auth.users().requireManage();
        }

        if (rep == null) {
            throw ErrorResponse.error("User representation cannot be null", Status.BAD_REQUEST);
        }

        if (!organization.isEnabled()) {
            throw ErrorResponse.error("Organization is disabled", Status.BAD_REQUEST);
        }

        if (StringUtil.isNotBlank(rep.getEmail()) && organization.getDomains().findAny().isPresent()) {
            String emailDomain = Organizations.getEmailDomain(rep.getEmail());

            if (emailDomain == null || Organizations.getMatchingDomain(emailDomain, organization) == null) {
                throw ErrorResponse.error("Email domain does not match any domain from the organization", Status.BAD_REQUEST);
            }
        }

        // only accept the basic fields so that credentials, groups, roles, or custom attributes cannot be set
        UserRepresentation sanitized = new UserRepresentation();
        sanitized.setUsername(rep.getUsername());
        sanitized.setEmail(rep.getEmail());
        sanitized.setEmailVerified(false);
        sanitized.setFirstName(rep.getFirstName());
        sanitized.setLastName(rep.getLastName());
        sanitized.setEnabled(rep.isEnabled() == null ? Boolean.TRUE : rep.isEnabled());

        UserProfile profile = session.getProvider(UserProfileProvider.class).create(USER_API, sanitized.getRawAttributes());

        try {
            UserResource.validateUserProfile(profile, session, auth.adminAuth());
            UserModel user = profile.create();
            UserResource.updateUserFromRep(profile, user, sanitized, session, false);

            adminEvent.clone(session).resource(ResourceType.USER).operation(OperationType.CREATE)
                    .resourcePath("users", user.getId())
                    .representation(sanitized)
                    .success();

            if (!provider.addMember(organization, user)) {
                throw ErrorResponse.error("Could not add user as a member of the organization", Status.BAD_REQUEST);
            }

            adminEvent.operation(OperationType.CREATE).resource(ResourceType.ORGANIZATION_MEMBERSHIP)
                    .representation(ModelToRepresentation.toRepresentation(organization))
                    .resourcePath(session.getContext().getUri())
                    .detail(UserModel.USERNAME, user.getUsername())
                    .detail(UserModel.EMAIL, user.getEmail())
                    .success();

            // resolves the location relative to the members collection (.../members/{member-id})
            return Response.created(session.getContext().getUri().getAbsolutePath().resolve(user.getId())).build();
        } catch (ModelDuplicateException e) {
            session.getTransactionManager().setRollbackOnly();
            throw ErrorResponse.exists("User exists with same username or email");
        } catch (ModelException me) {
            session.getTransactionManager().setRollbackOnly();
            throw ErrorResponse.error(me.getMessage(), Status.BAD_REQUEST);
        } catch (RuntimeException e) {
            session.getTransactionManager().setRollbackOnly();
            throw e;
        }
    }

    @Path("invite-user")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation(summary = "Invites an existing user or sends a registration link to a new user, based on the provided e-mail address.",
            description = "If the user with the given e-mail address exists, it sends an invitation link, otherwise it sends a registration link. " +
                    "The client_id query parameter is optional. If no client_id is provided, the account client is used. " +
                    "After accepting the invitation the user is redirected to the selected client's home URL; for the account client the " +
                    "organization redirect URL is used instead when configured.")
    @APIResponses(value = {
        @APIResponse(responseCode = "204", description = "No Content"),
        @APIResponse(responseCode = "400", description = "Bad Request"),
        @APIResponse(responseCode = "403", description = "Forbidden"),
        @APIResponse(responseCode = "409", description = "Conflict"),
        @APIResponse(responseCode = "500", description = "Internal Server Error")
    })
    public Response inviteUser(@FormParam("email") String email,
                               @FormParam("firstName") String firstName,
                               @FormParam("lastName") String lastName,
                               @Parameter(description = "Client id") @QueryParam(OIDCLoginProtocol.CLIENT_ID_PARAM) String clientId) {
        return new OrganizationInvitationResource(session, organization, adminEvent, auth).inviteUser(email, firstName, lastName, clientId);
    }

    @POST
    @Path("invite-existing-user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation(summary = "Invites an existing user to the organization, using the specified user id or username",
            description = "Exactly one of the id or username form parameters must be provided.")
    @APIResponses(value = {
        @APIResponse(responseCode = "204", description = "No Content"),
        @APIResponse(responseCode = "400", description = "Bad Request"),
        @APIResponse(responseCode = "403", description = "Forbidden"),
        @APIResponse(responseCode = "500", description = "Internal Server Error")
    })
    public Response inviteExistingUser(@Parameter(description = "The id of the user to invite") @FormParam("id") String id,
                                       @Parameter(description = "The username of the user to invite") @FormParam("username") String username) {
        return new OrganizationInvitationResource(session, organization, adminEvent, auth).inviteExistingUser(id, username);
    }

    /**
     * Precondition: caller must have passed through {@link OrganizationsResource#get(String)}
     * which enforces {@code auth.orgs().requireView(organization)}. This method additionally
     * requires either the organization admin permission ({@link #canManageMembers()}), in which case all members of
     * the organization are returned (as brief representations unless the caller can view the user, and at most
     * {@link Constants#DEFAULT_MAX_RESULTS}), or {@code auth.users().canQuery()}, in which case results are filtered
     * by user permissions.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation( summary = "Returns a paginated list of organization members filtered according to the specified parameters")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = MemberRepresentation.class, type = SchemaType.ARRAY))),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Stream<MemberRepresentation> search(
            @Parameter(description = "A String representing either a member's username, e-mail, first name, or last name.") @QueryParam("search") String search,
            @Parameter(description = "Boolean which defines whether the param 'search' must match exactly or not") @QueryParam("exact") Boolean exact,
            @Parameter(description = "The position of the first result to be processed (pagination offset)") @QueryParam("first") @DefaultValue("0") Integer first,
            @Parameter(description = "The maximum number of results to be returned. Defaults to 10") @QueryParam("max") @DefaultValue("10") Integer max,
            @Parameter(description = "The membership type") @QueryParam("membershipType") String membershipType,
            @Parameter(description = "Boolean to return either a brief or a full user representation. If not specified, the brief representation is returned by default.")
            @QueryParam("briefRepresentation") @DefaultValue("true") boolean briefRepresentation
    ) {
        boolean canManageMembers = canManageMembers();

        if (!canManageMembers) {
            auth.users().requireQuery();

            // if a dedicated admin can query, but cannot view (and FGAP is not enabled) - we can return empty list right away to save a roundtrip to the DB
            if (!AdminPermissionsSchema.SCHEMA.isAdminPermissionsEnabled(realm) && !auth.users().canView()) {
                return Stream.empty();
            }
        }

        Map<String, String> filters = new HashMap<>();

        if (search != null) {
            filters.put(UserModel.SEARCH, search);
        }

        if (membershipType != null) {
            filters.put(MembershipType.NAME, MembershipType.valueOf(membershipType.toUpperCase()).name());
        }

        if (!canManageMembers) {
            return provider.getMembersStream(organization, filters, exact, first, max).map(m -> toRepresentation(m, briefRepresentation));
        }

        int firstResult = first == null || first < 0 ? 0 : first;
        int maxResults = max == null || max < 0 || max > Constants.DEFAULT_MAX_RESULTS ? Constants.DEFAULT_MAX_RESULTS : max;

        // organization admins see all members of the organization regardless of user permissions
        List<UserModel> members = AdminPermissionsSchema.runWithoutAuthorization(session,
                () -> provider.getMembersStream(organization, filters, exact, firstResult, maxResults).toList());

        return members.stream().map(m -> toRepresentation(m, briefRepresentation || !auth.users().canView(m)));
    }

    /**
     * Precondition: caller must have passed through {@link OrganizationsResource#get(String)}
     * which enforces {@code auth.orgs().requireView(organization)}. This method additionally
     * requires {@code auth.users().canView(member)} or the organization admin permission ({@link #canManageMembers()}),
     * in which case the brief representation is returned.
     */
    @Path("{member-id}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation( summary = "Returns the member of the organization with the specified id", description = "Searches for a" +
            "user with the given id. If one is found, and is currently a member of the organization, returns it. Otherwise," +
            "an error response with status NOT_FOUND is returned")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = MemberRepresentation.class))),
        @APIResponse(responseCode = "400", description = "Bad Request"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public MemberRepresentation get(@PathParam("member-id") String memberId) {
        if (StringUtil.isBlank(memberId)) {
            throw ErrorResponse.error("id cannot be null", Status.BAD_REQUEST);
        }

        UserModel member = getMember(memberId);

        if (auth.users().canView(member)) {
            return toRepresentation(member, false);
        }

        requireManageMembers();
        return toRepresentation(member, true);
    }

    /**
     * Requires {@code auth.orgs().requireManage(organization)} and {@code auth.users().requireManage(member)}.
     */
    @Path("{member-id}/membership-type")
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation(summary = "Updates the membership type of the member with the specified id")
    @APIResponses(value = {
        @APIResponse(responseCode = "204", description = "No Content"),
        @APIResponse(responseCode = "400", description = "Bad Request"),
        @APIResponse(responseCode = "403", description = "Forbidden"),
        @APIResponse(responseCode = "404", description = "Not Found")
    })
    public Response updateMembershipType(@PathParam("member-id") String memberId, MembershipType membershipType) {
        auth.orgs().requireManage(organization);
        if (StringUtil.isBlank(memberId)) {
            throw ErrorResponse.error("id cannot be null", Status.BAD_REQUEST);
        }
        if (membershipType == null) {
            throw ErrorResponse.error("membershipType cannot be null", Status.BAD_REQUEST);
        }

        UserModel member = getMember(memberId);
        auth.users().requireManage(member);

        MembershipType currentType = provider.isManagedMember(organization, member) ? MembershipType.MANAGED : MembershipType.UNMANAGED;
        if (membershipType.equals(currentType)) {
            return Response.noContent().build();
        }

        try {
            if (provider.updateMembershipType(organization, member, membershipType)) {
                adminEvent.operation(OperationType.UPDATE)
                        .representation(toRepresentation(member, false))
                        .resourcePath(session.getContext().getUri())
                        .detail(UserModel.USERNAME, member.getUsername())
                        .detail(UserModel.EMAIL, member.getEmail())
                        .detail(MembershipType.NAME, membershipType.name())
                        .success();
                return Response.noContent().build();
            }
        } catch (ModelException me) {
            throw ErrorResponse.error(me.getMessage(), Status.BAD_REQUEST);
        }

        throw ErrorResponse.error("Not a member of the organization", Status.NOT_FOUND);
    }

    /**
     * Requires {@code auth.orgs().requireManage(organization)} and additionally {@code auth.users().canManage(member)},
     * or the organization admin permission ({@link #canManageMembers()}) when the member is
     * {@link MembershipType#UNMANAGED}. Removing a managed member deletes the user account, so organization admins
     * cannot remove managed members.
     */
    @Path("{member-id}")
    @DELETE
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation(summary = "Removes the user with the specified id from the organization", description = "Breaks the association " +
            "between the user and organization. The user itself is deleted in case the membership is managed, otherwise the user is not deleted. " +
            "If no user is found, or if they are not a member of the organization, an error response is returned")
    @APIResponses(value = {
        @APIResponse(responseCode = "204", description = "No Content"),
        @APIResponse(responseCode = "400", description = "Bad Request"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Response delete(@PathParam("member-id") String memberId) {
        auth.orgs().requireManage(organization);
        if (StringUtil.isBlank(memberId)) {
            throw ErrorResponse.error("id cannot be null", Status.BAD_REQUEST);
        }

        UserModel member = getMember(memberId);

        if (!auth.users().canManage(member) && (!canManageMembers() || provider.isManagedMember(organization, member))) {
            throw new ForbiddenException();
        }

        if (provider.removeMember(organization, member)) {
            adminEvent.operation(OperationType.DELETE).resource(ResourceType.ORGANIZATION_MEMBERSHIP)
                    .representation(ModelToRepresentation.toRepresentation(organization))
                    .resourcePath(session.getContext().getUri())
                    .detail(UserModel.USERNAME, member.getUsername())
                    .detail(UserModel.EMAIL, member.getEmail())
                    .success();
            return Response.noContent().build();
        }

        throw ErrorResponse.error("Not a member of the organization", Status.BAD_REQUEST);
    }

    /**
     * Precondition: when reached via the per-org path, the caller must have passed through
     * {@link OrganizationsResource#get(String)} which enforces {@code auth.orgs().requireView(organization)}.
     * When reached via the collection-level path ({@code /organizations/members/{id}/organizations}),
     * the caller passes through {@link OrganizationsResource#getOrganizations(String)} which enforces
     * {@code auth.orgs().requireQuery()}. This method additionally requires
     * {@code auth.users().requireView(member)} and filters returned organizations by
     * {@code auth.orgs().canView(org)}.
     */
    @Path("{member-id}/organizations")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation(summary = "Returns the organizations associated with the user that has the specified id")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = OrganizationRepresentation.class, type = SchemaType.ARRAY))),
        @APIResponse(responseCode = "400", description = "Bad Request"),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Stream<OrganizationRepresentation> getOrganizations(
            @PathParam("member-id") String memberId,
            @Parameter(description = "if false, return the full representation. Otherwise, only the basic fields are returned.")
            @QueryParam("briefRepresentation") @DefaultValue("true") boolean briefRepresentation) {
        if (StringUtil.isBlank(memberId)) {
            throw ErrorResponse.error("id cannot be null", Status.BAD_REQUEST);
        }

        UserModel member = organization == null ? getUser(memberId) : getMember(memberId);
        auth.users().requireView(member);

        // if a dedicated admin can query, but cannot view (and FGAP is not enabled) - we can return empty list right away to save a roundtrip to the DB
        if (!AdminPermissionsSchema.SCHEMA.isAdminPermissionsEnabled(realm) && !auth.orgs().canView()) {
            return Stream.empty();
        }

        return provider.getByMember(member)
                .filter(org -> auth.orgs().canView(org))
                .map(model -> OrganizationResource.toRepresentation(model, briefRepresentation, auth));
    }

    /**
     * Precondition: caller must have passed through {@link OrganizationsResource#get(String)}
     * which enforces {@code auth.orgs().requireView(organization)}. This method additionally
     * requires {@code auth.users().canView(member)} or the organization admin permission ({@link #canManageMembers()}),
     * in which case brief group representations are returned.
     */
    @Path("{member-id}/groups")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation( summary = "Returns the organization group memberships for a member with the specified id", description = "Searches for a" +
            "user with the given id. If one is found, and is currently a member of the organization, returns the groups from the organization" +
            "where the user is member of. Otherwise, an error response with status NOT_FOUND is returned")
    @APIResponses(value = {
            @APIResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = GroupRepresentation.class, type = SchemaType.ARRAY))),
            @APIResponse(responseCode = "400", description = "Bad Request"),
            @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Stream<GroupRepresentation> groupMemberships(@PathParam("member-id") String memberId,
                                                        @QueryParam("first") Integer firstResult,
                                                        @QueryParam("max") Integer maxResults,
                                                        @QueryParam("search") String search,
                                                        @QueryParam("briefRepresentation") @DefaultValue("true") boolean briefRepresentation) {
        if (StringUtil.isBlank(memberId)) {
            throw ErrorResponse.error("id cannot be null", Status.BAD_REQUEST);
        }

        UserModel member = getMember(memberId);
        boolean brief = briefRepresentation;

        if (!auth.users().canView(member)) {
            requireManageMembers();
            brief = true;
        }

        boolean full = !brief;

        return provider.getOrganizationGroupsByMember(organization, member, search, firstResult, maxResults)
                .map(group -> ModelToRepresentation.toRepresentation(group, full));
    }

    /**
     * Precondition: caller must have passed through {@link OrganizationsResource#get(String)}
     * which enforces {@code auth.orgs().requireView(organization)}. This method additionally
     * requires either the organization admin permission ({@link #canManageMembers()}), in which case all members of
     * the organization are counted, or {@code auth.users().requireQuery()}.
     */
    @Path("count")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    @Tag(name = KeycloakOpenAPI.Admin.Tags.ORGANIZATIONS)
    @Operation( summary = "Returns number of members in the organization.")
    @APIResponses(value = {
        @APIResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = Long.class))),
        @APIResponse(responseCode = "403", description = "Forbidden")
    })
    public Long count() {
        if (canManageMembers()) {
            // organization admins count all members of the organization regardless of user permissions
            return AdminPermissionsSchema.runWithoutAuthorization(session, () -> provider.getMembersCount(organization));
        }

        auth.users().requireQuery();

        // if a dedicated admin can query, but cannot view (and FGAP is not enabled) - we can return 0L right away to save a roundtrip to the DB
        if (!AdminPermissionsSchema.SCHEMA.isAdminPermissionsEnabled(realm) && !auth.users().canView()) {
            return 0L;
        }

        return provider.getMembersCount(organization);
    }

    private UserModel getMember(String id) {
        UserModel member = provider.getMemberById(organization, id);

        if (member == null) {
            throw (auth.users().canQuery() || canManageMembers()) ? new NotFoundException() : new ForbiddenException();
        }

        return member;
    }

    /**
     * Organization admins are granted access to the membership of the organization they manage, without requiring
     * realm-wide user permissions. This authority comes only from an admin permission (FGAP v2) granting
     * {@code manage} on the organization, not from the realm-wide organization admin roles. It never allows changing
     * user accounts.
     */
    private boolean canManageMembers() {
        return organization != null && auth.orgs().hasManagePermission(organization);
    }

    private void requireManageMembers() {
        if (!canManageMembers()) {
            throw new ForbiddenException();
        }
    }

    private UserModel getUser(String id) {
        UserModel user = session.users().getUserById(realm, id);

        if (user == null) {
            throw (auth.users().canQuery()) ? new NotFoundException() : new ForbiddenException();
        }

        return user;
    }

    private MemberRepresentation toRepresentation(UserModel member, boolean brief) {
        MemberRepresentation result = new MemberRepresentation(ModelToRepresentation.toRepresentation(session, member, brief));
        result.setMembershipType(provider.isManagedMember(organization, member) ? MembershipType.MANAGED : MembershipType.UNMANAGED);
        return result;
    }
}
