/**
 * <PRE>
 *
 * Copyright 2026 <A href="http://bringardner.us/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 *
 *
 */
package us.bringardner.parley.ftp.server;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.Credential;
import us.bringardner.parley.iam.EntityId;
import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.ProtectedResource;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.StoredCredential;
import us.bringardner.parley.iam.StoredCredentialType;
import us.bringardner.parley.iam.UserRegistry;
import us.bringardner.parley.iam.credential.PasswordCredential;
import us.bringardner.parley.iam.permission.StandardPermissions;
import us.bringardner.parley.iam.principal.BasicParleyPrincipal;

/**
 * A {@link UserRegistry} with one user, anonymous FTP (RFC 1635): the user names
 * {@code anonymous} and {@code ftp} (any case) log in as the principal {@code anonymous}
 * with any password (by custom the client's e-mail address). It has no attributes, so its
 * root is the server's (or the virtual host's). For a root of its own, use a registry
 * that gives the principal a {@link FtpRequestProcessor#PARAMETER_ROOT} attribute.
 * <p>
 * Anonymous holds the permissions given to the constructor, on the server as a whole; nobody
 * logged in holds nothing, and {@code admin} is always refused. The no-argument constructor,
 * which the {@code authenticationProvider} setting uses, gives {@code read} only: anonymous can
 * list, download and change directory, and every upload, rename and delete is refused. An
 * application that wants anonymous uploads gives the server
 * {@code new AnonymousUserRegistry(StandardPermissions.READ, StandardPermissions.WRITE)}, or names
 * a subclass whose no-argument constructor calls that one.
 * <p>
 * A server uses it only when it is configured, like any other registry:
 * <pre>
 * parley.ftp.authenticationProvider=us.bringardner.parley.ftp.server.AnonymousUserRegistry
 * </pre>
 * Before 2.0.0 this was what an FTP server used when no access control list was configured;
 * now a server with no registry refuses every login (parley-iam: no stand-in users).
 *
 * @since 2.0.0
 */
public class AnonymousUserRegistry implements UserRegistry {

	/** The anonymous principal's name */
	public static final String ANONYMOUS = "anonymous";
	/** The other user name RFC 1635 gives anonymous FTP */
	public static final String FTP = "ftp";
	/** The login method of an anonymous login */
	public static final String METHOD = "anonymous";

	private final ParleyPrincipal anonymous = BasicParleyPrincipal.user(EntityId.of("user:"+ANONYMOUS), ANONYMOUS);

	/** The permission names anonymous holds, in lower case */
	private final Set<String> permissions;

	/** Anonymous holds {@code read} only. */
	public AnonymousUserRegistry() {
		this(StandardPermissions.READ);
	}

	/**
	 * Anonymous holds these permissions on the server as a whole ({@code admin} is refused even
	 * when it is listed).
	 *
	 * @param permissions the permission names, such as {@link StandardPermissions#READ} and
	 * {@link StandardPermissions#WRITE}; case doesn't matter
	 * @throws NullPointerException when the array or one of its names is null
	 */
	public AnonymousUserRegistry(String... permissions) {
		Objects.requireNonNull(permissions, "permissions");
		Set<String> names = new HashSet<>();
		for(String name : permissions) {
			Objects.requireNonNull(name, "a permission name");
			names.add(name.toLowerCase(Locale.ROOT));
		}
		this.permissions = Collections.unmodifiableSet(names);
	}

	/** @return the permission names anonymous holds, in lower case (admin is refused even if here) */
	public Set<String> getPermissions() {
		return permissions;
	}

	/** @return true for anonymous and ftp, in any case */
	private static boolean isAnonymous(String name) {
		if( name == null ) {
			return false;
		}
		String n = name.toLowerCase(Locale.ROOT);
		return n.equals(ANONYMOUS) || n.equals(FTP);
	}

	/** Passwords only. */
	@Override
	public boolean supports(Class<? extends Credential> kind, StoredCredentialType stored) {
		return kind == PasswordCredential.class && stored == null;
	}

	/** Any password for anonymous or ftp; every other user is refused. */
	@Override
	public AuthenticationResult authenticate(Credential credential) {
		if( !(credential instanceof PasswordCredential) ) {
			return AuthenticationResult.unsupported("AnonymousUserRegistry checks passwords only");
		}
		if( !isAnonymous(credential.getUser()) ) {
			return AuthenticationResult.refused("no user "+credential.getUser());
		}
		return AuthenticationResult.authenticated(anonymous, METHOD);
	}

	/** There are no stored secrets. */
	@Override
	public StoredCredential findStoredCredential(String user, StoredCredentialType type) {
		return null;
	}

	@Override
	public ParleyPrincipal findPrincipal(String name) {
		return isAnonymous(name) ? anonymous : null;
	}

	@Override
	public ParleyPrincipal findPrincipal(EntityId id) {
		return anonymous.getId().equals(id) ? anonymous : null;
	}

	/**
	 * Allowed when anonymous is logged in, the permission is on the server as a whole, it is one
	 * that anonymous holds, and it isn't {@code admin}.
	 */
	@Override
	public AuthorizationResult checkPermission(Requester requester, ParleyPermission needed,
			ProtectedResource resource) {
		ParleyPrincipal principal = requester.getPrincipal();
		if( principal == null ) {
			return AuthorizationResult.denied("nobody logged in");
		}
		if( !anonymous.getId().equals(principal.getId()) ) {
			return AuthorizationResult.denied(principal.getName()+" isn't known here");
		}
		if( resource != null || needed.getResourceId() != null ) {
			return AuthorizationResult.denied("anonymous holds nothing on a resource");
		}
		if( needed.getName().equalsIgnoreCase(StandardPermissions.ADMIN) ) {
			return AuthorizationResult.denied("anonymous isn't an administrator");
		}
		if( !permissions.contains(needed.getName().toLowerCase(Locale.ROOT)) ) {
			return AuthorizationResult.denied("anonymous doesn't hold "+needed.getName());
		}
		return AuthorizationResult.allowed("anonymous holds "+needed.getName());
	}
}
