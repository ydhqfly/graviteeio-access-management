/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.am.management.handlers.management.api.resources.organizations.users;

import io.gravitee.am.management.handlers.management.api.resources.AbstractResource;
import io.gravitee.am.management.service.UserService;
import io.gravitee.am.model.Acl;
import io.gravitee.am.model.ReferenceType;
import io.gravitee.am.model.User;
import io.gravitee.am.model.common.Page;
import io.gravitee.am.model.permissions.Permission;
import io.gravitee.am.service.IdentityProviderService;
import io.gravitee.am.service.authentication.crypto.password.PasswordValidator;
import io.gravitee.am.service.exception.UserInvalidException;
import io.gravitee.am.service.model.NewUser;
import io.gravitee.common.http.MediaType;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.swagger.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.ResourceContext;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
@Api(tags = {"user"})
public class UsersResource extends AbstractResource {

    private static final int MAX_USERS_SIZE_PER_PAGE = 30;
    private static final String MAX_USERS_SIZE_PER_PAGE_STRING = "30";

    @Context
    private ResourceContext resourceContext;

    @Autowired
    private UserService userService;

    @Autowired
    private IdentityProviderService identityProviderService;

    @Autowired
    private PasswordValidator passwordValidator;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List users of the organization",
            notes = "User must have the ORGANIZATION_USER[LIST] permission on the specified organization. " +
                    "Each returned user is filtered and contains only basic information such as id and username and displayname. " +
                    "Last login and identity provider name will be also returned if current user has ORGANIZATION_USER[READ] permission on the organization.")
    @ApiResponses({
            @ApiResponse(code = 200, message = "List users of the organization", response = User.class, responseContainer = "Set"),
            @ApiResponse(code = 500, message = "Internal server error")})
    public void list(
            @PathParam("organizationId") String organizationId,
            @QueryParam("q") String query,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue(MAX_USERS_SIZE_PER_PAGE_STRING) int size,
            @Suspended final AsyncResponse response) {

        io.gravitee.am.identityprovider.api.User authenticatedUser = getAuthenticatedUser();
        final Single<Page<User>> usersPageObs;

        if (query != null) {
            usersPageObs = userService.search(ReferenceType.ORGANIZATION, organizationId, query, page, Integer.min(size, MAX_USERS_SIZE_PER_PAGE));
        } else {
            usersPageObs = userService.findAll(ReferenceType.ORGANIZATION, organizationId, page, Integer.min(size, MAX_USERS_SIZE_PER_PAGE));
        }

        permissionService.findAllPermissions(authenticatedUser, ReferenceType.ORGANIZATION, organizationId)
                .flatMap(organizationPermissions ->
                        checkPermission(organizationPermissions, Permission.ORGANIZATION_USER, Acl.LIST)
                                .andThen(usersPageObs.flatMap(pagedUsers ->
                                        Observable.fromIterable(pagedUsers.getData())
                                                .flatMapSingle(user -> filterUserInfos(organizationPermissions, user))
                                                .toSortedList(Comparator.comparing(User::getUsername))
                                                .map(users -> new Page<>(users, pagedUsers.getCurrentPage(), pagedUsers.getTotalCount())))))
                .subscribe(response::resume, response::resume);
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create a platform user",
            notes = "User must have the ORGANIZATION_USER[READ] permission on the specified organization")
    @ApiResponses({
            @ApiResponse(code = 201, message = "User successfully created"),
            @ApiResponse(code = 500, message = "Internal server error")})
    public void create(
            @PathParam("organizationId") String organizationId,
            @ApiParam(name = "user", required = true) @Valid @NotNull final NewUser newUser,
            @Suspended final AsyncResponse response) {
        final io.gravitee.am.identityprovider.api.User authenticatedUser = getAuthenticatedUser();

        // user must have a password in no pre registration mode
        if (!newUser.isPreRegistration() && newUser.getPassword() == null) {
            response.resume(new UserInvalidException(("Field [password] is required")));
            return;
        }

        // check password policy
        if (newUser.getPassword() != null) {
            if (!passwordValidator.validate(newUser.getPassword())) {
                response.resume(new UserInvalidException(("Field [password] is invalid")));
                return;
            }
        }

        checkPermission(ReferenceType.ORGANIZATION, organizationId, Permission.ORGANIZATION_USER, Acl.CREATE)
                .andThen(userService.create(ReferenceType.ORGANIZATION, organizationId, newUser, authenticatedUser)
                        .map(user -> Response
                                .created(URI.create("/organizations/" + organizationId + "/users/" + user.getId()))
                                .entity(user)
                                .build()))
                .subscribe(response::resume, response::resume);
    }

    @Path("{user}")
    public UserResource getUserResource() {
        return resourceContext.getResource(UserResource.class);
    }

    private Single<User> filterUserInfos(Map<Permission, Set<Acl>> organizationPermissions, User user) {

        User filteredUser = new User();
        filteredUser.setId(user.getId());
        filteredUser.setUsername(user.getUsername());
        filteredUser.setDisplayName(user.getDisplayName());
        filteredUser.setPicture(user.getPicture());

        if (hasPermission(organizationPermissions, Permission.ORGANIZATION_USER, Acl.READ)) {
            filteredUser.setLoggedAt(user.getLoggedAt());
            filteredUser.setAdditionalInformation(user.getAdditionalInformation());
            if (user.getSource() != null) {
                return identityProviderService.findById(user.getSource())
                        .map(idP -> {
                            filteredUser.setSource(idP.getName());
                            return filteredUser;
                        })
                        .defaultIfEmpty(filteredUser)
                        .toSingle();
            }
        }

        return Single.just(filteredUser);
    }
}
