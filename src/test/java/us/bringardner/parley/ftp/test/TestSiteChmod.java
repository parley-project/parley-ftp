package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.FTP;
import us.bringardner.parley.ftp.client.ClientFtpResponse;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.ftp.server.commands.Site;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * SITE CHMOD changes a file's permissions. That takes owning the file, not being able to write
 * it, so a file that has been made read-only can be made writable again (this was refused with
 * 450, which left read-only files and directories nobody could put right through the server).
 */
public class TestSiteChmod {

	private static final int PORT = 8041;
	private static final String ROOT_PATH = FtpTestSupport.scratchDir("FtpRootSiteChmod");

	private static FtpServer server;
	private static File rootDir;

	@BeforeAll
	public static void startServer() throws Exception {
		System.clearProperty(IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);

		rootDir = new File(ROOT_PATH).getAbsoluteFile();
		deleteAll(rootDir);
		assertTrue(rootDir.mkdirs());

		FileSource root = FileSourceFactory.getDefaultFactory().createFileSource(rootDir.getAbsolutePath());
		server = new FtpServer();
		server.setSecure(false);
		server.setFtpRoot(root);
		server.setPort(PORT);
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		long start = System.currentTimeMillis();
		while (!server.isRunning() && System.currentTimeMillis() - start < 5000) {
			Thread.sleep(20);
		}
		assertTrue(server.isRunning(), "server did not start");
	}

	@AfterAll
	public static void stopServer() throws Exception {
		if (server != null) {
			server.stop();
			long start = System.currentTimeMillis();
			while (server.isRunning() && System.currentTimeMillis() - start < 10000) {
				Thread.sleep(10);
			}
		}
		deleteAll(rootDir);
	}

	/** Makes everything writable first: a case that fails must not leave a tree that can't be removed. */
	private static void deleteAll(File f) {
		if( f == null || !f.exists() ) {
			return;
		}
		f.setWritable(true);
		f.setExecutable(true);
		File[] kids = f.listFiles();
		if( kids != null ) {
			for(File k : kids) {
				deleteAll(k);
			}
		}
		f.delete();
	}

	private static FtpClient connect() throws Exception {
		FtpClient client = new FtpClient("localhost", PORT);
		client.setRequestSecure(false);
		client.getLogger().setLevel(Level.ERROR);
		assertTrue(client.connect("anonymous", "x", null));
		return client;
	}

	private static String mode(File f) throws Exception {
		return PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()));
	}

	private static ClientFtpResponse chmod(FtpClient c, String number, String path) throws Exception {
		return c.executeCommand(FTP.SITE, Site.CMD_CHMOD, number, path);
	}

	@org.junit.jupiter.api.condition.DisabledOnOs(value = org.junit.jupiter.api.condition.OS.WINDOWS, disabledReason = "POSIX permission modes: Windows has ACLs and no group or other")
	@Test
	public void aReadOnlyFileCanBeMadeWritableAgain() throws Exception {
		File f = new File(rootDir, "readonly.txt");
		Files.write(f.toPath(), new byte[3]);
		FtpClient c = connect();
		try {
			assertTrue(chmod(c, "444", "/readonly.txt").isPositiveComplet());
			assertEquals("r--r--r--", mode(f));
			// the file can no longer be written, which is what used to make this refuse
			assertTrue(chmod(c, "644", "/readonly.txt").isPositiveComplet(), "owning it is enough");
			assertEquals("rw-r--r--", mode(f));
		} finally {
			c.close();
		}
	}

	@org.junit.jupiter.api.condition.DisabledOnOs(value = org.junit.jupiter.api.condition.OS.WINDOWS, disabledReason = "POSIX permission modes: Windows has ACLs and no group or other")
	@Test
	public void aReadOnlyDirectoryCanBeMadeWritableAgain() throws Exception {
		File d = new File(rootDir, "readonlydir");
		assertTrue(d.mkdir());
		FtpClient c = connect();
		try {
			assertTrue(chmod(c, "555", "/readonlydir").isPositiveComplet());
			assertEquals("r-xr-xr-x", mode(d));
			assertTrue(chmod(c, "755", "/readonlydir").isPositiveComplet());
			assertEquals("rwxr-xr-x", mode(d));
		} finally {
			c.close();
		}
	}

	@Test
	public void aPathThatIsntThereIsStillRefused() throws Exception {
		FtpClient c = connect();
		try {
			ClientFtpResponse r = chmod(c, "644", "/nothing-here.txt");
			assertFalse(r.isPositiveComplet());
			assertTrue(String.valueOf(r).contains("non existant"), String.valueOf(r));
		} finally {
			c.close();
		}
	}
}
