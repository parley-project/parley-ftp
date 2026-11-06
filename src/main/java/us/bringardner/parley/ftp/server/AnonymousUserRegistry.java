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

import java.util.Locale;

import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.BasicParleyPrincipal;
import us.bringardner.parley.iam.Credential;
import us.bringardner.parley.iam.EntityId;
import us.bringardner.parley.iam.ParleyPermission;
import us.bringardner.parley.iam.ParleyPrincipal;
import us.bringardner.parley.iam.PasswordCredential;
import us.bringardner.parley.iam.ProtectedResource;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.StandardPermissions;
import us.bringardner.parley.iam.StoredCredential;
import us.bringardner.parley.iam.StoredCredentialType;
import us.bringardner.parley.iam.UserRegistry;

/**
 * A {@link UserRegistry} with one user, anonymous FTP (RFC 1635): the user names
 * {@code anonymous} and {@code ftp} (any case) log in as the principal {@code anonymous}
 * with any password (by custom the client's e-mail address). It has no attributes, so its
 * root is the server's (or the virtual host's), and it holds every permission on the server
 * except {@code admin}; nobody logged in holds nothing. For a root of its own, use a registry
 * that gives the principal a {@link FtpRequestProcessor#PARAMETER_ROOT} attribute.
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
	 * Allowed when anonymous is logged in and the permission is on the server as a whole and
	 * isn't {@code admin}.
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
		return AuthorizationResult.allowed("anonymous holds "+needed.getName());
	}
}
