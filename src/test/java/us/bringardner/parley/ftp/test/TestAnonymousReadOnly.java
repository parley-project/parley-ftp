package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.AnonymousUserRegistry;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.ftp.test.TestTransferReliability.Session;
import us.bringardner.parley.iam.AuthenticationResult;
import us.bringardner.parley.iam.AuthorizationResult;
import us.bringardner.parley.iam.Requester;
import us.bringardner.parley.iam.credential.PasswordCredential;
import us.bringardner.parley.iam.permission.BasicParleyPermission;
import us.bringardner.parley.iam.permission.StandardPermissions;

/**
 * {@link AnonymousUserRegistry} gives anonymous read only, unless the constructor is given more;
 * admin is always refused.
 */
public class TestAnonymousReadOnly {

	private static final int PORT = 8071;
	private static FtpServer server;
	private static File root;

	@BeforeAll
	public static void start() throws Exception {
		root = new File(FtpTestSupport.scratchDir("FtpAnonymousReadOnly")).getAbsoluteFile();
		deleteAll(root);
		assertTrue(root.mkdirs());
		Files.write(new File(root, "readme.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));
		assertTrue(new File(root, "dir").mkdir());

		server = new FtpServer();
		server.setSecure(false);
		server.setUserRegistry(new AnonymousUserRegistry());
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.getAbsolutePath()));
		server.setPort(PORT);
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		long t = System.currentTimeMillis();
		while (!server.isRunning() && System.currentTimeMillis() - t < 5000) {
			Thread.sleep(20);
		}
		assertTrue(server.isRunning());
	}

	@AfterAll
	public static void stop() throws Exception {
		if (server != null) {
			server.stop();
		}
		deleteAll(root);
	}

	@Test
	public void anonymousCanReadOverFtp() throws Exception {
		try (Session s = new Session(PORT)) {
			assertEquals("hello", new String(s.get("readme.txt"), StandardCharsets.UTF_8));
			s.send("SIZE readme.txt");
			assertEquals(213, s.read(5000).code);
			s.send("CWD dir");
			assertEquals(250, s.read(5000).code);
			s.send("CDUP");
			assertEquals(250, s.read(5000).code);
		}
	}

	@Test
	public void anonymousCanNotWriteOverFtp() throws Exception {
		try (Session s = new Session(PORT)) {
			String[] commands = {
					"STOR new.txt", "APPE readme.txt", "DELE readme.txt", "MKD made", "RMD dir",
					"RNFR readme.txt", "SITE CHMOD 777 readme.txt" };
			for (String command : commands) {
				s.send(command);
				assertEquals(550, s.read(5000).code, command);
			}
		}
		assertTrue(new File(root, "readme.txt").exists());
		assertTrue(new File(root, "dir").exists());
		assertFalse(new File(root, "new.txt").exists());
		assertFalse(new File(root, "made").exists());
	}

	@Test
	public void theNoArgumentConstructorGivesReadOnly() {
		AnonymousUserRegistry registry = new AnonymousUserRegistry();
		Requester anonymous = loggedIn(registry);
		assertEquals(AuthorizationResult.Status.ALLOWED, check(registry, anonymous, StandardPermissions.READ));
		assertEquals(AuthorizationResult.Status.DENIED, check(registry, anonymous, StandardPermissions.WRITE));
		assertEquals(AuthorizationResult.Status.DENIED, check(registry, anonymous, StandardPermissions.DELETE));
		assertEquals(AuthorizationResult.Status.DENIED, check(registry, anonymous, StandardPermissions.ADMIN));
	}

	@Test
	public void theConstructorsPermissionsAreHeldButNeverAdmin() {
		AnonymousUserRegistry registry = new AnonymousUserRegistry("READ", StandardPermissions.WRITE,
				StandardPermissions.ADMIN);
		Requester anonymous = loggedIn(registry);
		assertEquals(AuthorizationResult.Status.ALLOWED, check(registry, anonymous, StandardPermissions.READ));
		assertEquals(AuthorizationResult.Status.ALLOWED, check(registry, anonymous, "Write"));
		assertEquals(AuthorizationResult.Status.DENIED, check(registry, anonymous, StandardPermissions.DELETE));
		assertEquals(AuthorizationResult.Status.DENIED, check(registry, anonymous, StandardPermissions.ADMIN));
	}

	@Test
	public void nobodyLoggedInHoldsNothing() {
		AnonymousUserRegistry registry = new AnonymousUserRegistry(StandardPermissions.READ, StandardPermissions.WRITE);
		Requester nobody = Requester.withoutAddress("test");
		assertEquals(AuthorizationResult.Status.DENIED, check(registry, nobody, StandardPermissions.READ));
	}

	@Test
	public void aNullPermissionNameIsRefused() {
		assertThrows(NullPointerException.class, new Executable() {
			@Override
			public void execute() {
				new AnonymousUserRegistry(StandardPermissions.READ, null);
			}
		});
	}

	/** A requester logged in to the registry as ftp. */
	private static Requester loggedIn(AnonymousUserRegistry registry) {
		Requester connected = Requester.withoutAddress("test");
		AuthenticationResult result = registry.authenticate(
				new PasswordCredential("ftp", "test@example.com".toCharArray(), connected));
		assertEquals(AuthenticationResult.Status.AUTHENTICATED, result.getStatus());
		return connected.loggedIn(result);
	}

	private static AuthorizationResult.Status check(AnonymousUserRegistry registry, Requester requester, String permission) {
		return registry.checkPermission(requester, new BasicParleyPermission(permission, null), null).getStatus();
	}

	private static void deleteAll(File f) {
		if (f == null || !f.exists()) {
			return;
		}
		File[] children = f.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteAll(child);
			}
		}
		f.delete();
	}
}
