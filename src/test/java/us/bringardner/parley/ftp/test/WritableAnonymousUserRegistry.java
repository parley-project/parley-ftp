package us.bringardner.parley.ftp.test;

import us.bringardner.parley.ftp.server.AnonymousUserRegistry;
import us.bringardner.parley.iam.permission.StandardPermissions;

/**
 * Anonymous FTP for the tests: anonymous may read and write, so the tests can upload, rename
 * and delete as {@code anonymous} or {@code ftp}. A test server names it in the
 * {@code authenticationProvider} setting, which needs a no-argument constructor.
 * {@link AnonymousUserRegistry} itself gives read only.
 */
public class WritableAnonymousUserRegistry extends AnonymousUserRegistry {

	/** Anonymous holds read and write. */
	public WritableAnonymousUserRegistry() {
		super(StandardPermissions.READ, StandardPermissions.WRITE);
	}
}
