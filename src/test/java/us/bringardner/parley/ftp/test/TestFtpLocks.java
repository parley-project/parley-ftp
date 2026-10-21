package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.ftp.test.TestTransferReliability.Reply;
import us.bringardner.parley.ftp.test.TestTransferReliability.Session;
import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.LockOptions;
import us.bringardner.parley.net.lock.Locks;
import us.bringardner.parley.net.lock.Mode;
import us.bringardner.parley.net.lock.NamedLock;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.lock.StoreClaim;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * Uploads, deletes and renames of one file keep out of each other: sessions of this server, and
 * other servers sharing the store. Downloads take no lock.
 */
public class TestFtpLocks {

	private static final int PORT = 8063;

	private FtpServer server;
	private File rootDir;
	private FileSource root;
	private String sharingBefore;

	@BeforeEach
	public void setUp() throws Exception {
		System.clearProperty(FtpServer.FTP_PREFIX+"."+IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		System.clearProperty(FtpServer.FTP_PREFIX+"."+FileBasedAcl.PROP_FILE_NAME);
		sharingBefore = System.getProperty(FtpServer.SHARED_STORE_PROPERTY);
		File scratch = new File(FtpTestSupport.scratchDir("FtpLocks"));
		scratch.mkdirs();
		rootDir = Files.createTempDirectory(scratch.toPath(), "root").toFile();
		root = FileSourceFactory.getDefaultFactory().createFileSource(rootDir.getCanonicalPath());
	}

	private void start(Sharing sharing) throws Exception {
		System.setProperty(FtpServer.SHARED_STORE_PROPERTY, sharing.name().toLowerCase());
		server = new FtpServer();
		server.setSecure(false);
		server.setFtpRoot(root);
		server.setPort(PORT);
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		long t = System.currentTimeMillis();
		while( !server.isRunning() && System.currentTimeMillis() - t < 5000 ) {
			Thread.sleep(20);
		}
		assertTrue(server.isRunning());
	}

	@AfterEach
	public void tearDown() {
		if( server != null ) {
			server.stop();
			long t = System.currentTimeMillis();
			while( server.isRunning() && System.currentTimeMillis() - t < 5000 ) {
				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
					break;
				}
			}
		}
		if( sharingBefore == null ) {
			System.clearProperty(FtpServer.SHARED_STORE_PROPERTY);
		} else {
			System.setProperty(FtpServer.SHARED_STORE_PROPERTY, sharingBefore);
		}
	}

	private static String code(Session s, String line) throws Exception {
		s.send(line);
		return "" + s.read(5000).code;
	}

	private static Reply store(Session s, String name, String content) throws Exception {
		try (Socket data = s.pasv()) {
			s.send("STOR " + name);
			Reply r = s.read(5000);
			if( r.code >= 400 ) {
				return r;
			}
			data.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
		}
		return s.read(5000);
	}

	private String read(String name) throws Exception {
		return new String(Files.readAllBytes(new File(rootDir, name).toPath()), StandardCharsets.UTF_8);
	}

	@Test
	public void aFileBeingUploadedIsKeptFromOtherSessions() throws Exception {
		start(Sharing.OFF);
		Files.write(new File(rootDir, "big.bin").toPath(), "old".getBytes(StandardCharsets.UTF_8));
		try (Session a = new Session(PORT); Session b = new Session(PORT)) {
			// a's upload is under way (its data connection is open)
			Socket data = a.pasv();
			a.send("STOR big.bin");
			a.expectPreliminary();
			OutputStream out = data.getOutputStream();
			out.write("new ".getBytes(StandardCharsets.UTF_8));
			out.flush();

			Reply r = store(b, "big.bin", "b's");
			assertEquals(450, r.code, "another upload is refused: " + r);
			assertTrue(r.text.contains("busy"), r.text);
			assertEquals("450", code(b, "DELE big.bin"), "and a delete");
			assertEquals("350", code(b, "RNFR big.bin"));
			assertEquals("450", code(b, "RNTO moved.bin"), "and a rename");
			Files.write(new File(rootDir, "other.bin").toPath(), "o".getBytes(StandardCharsets.UTF_8));
			assertEquals("350", code(b, "RNFR other.bin"));
			assertEquals("250", code(b, "RNTO other2.bin"), "other files are free");

			// a download takes no lock: the old file, whole
			try (Socket in = b.pasv()) {
				b.send("RETR big.bin");
				b.expectPreliminary();
				try (InputStream i = in.getInputStream()) {
					assertEquals("old", new String(i.readAllBytes(), StandardCharsets.UTF_8));
				}
			}
			b.expectComplete();

			out.write("data".getBytes(StandardCharsets.UTF_8));
			data.close();
			a.expectComplete();
			assertEquals("new data", read("big.bin"));

			// free once the upload is done
			assertEquals(226, store(b, "big.bin", "b's").code);
			assertEquals("b's", read("big.bin"));
			assertEquals("250", code(b, "DELE big.bin"));
		}
	}

	@Test
	public void theSameSessionCantUploadAFileTwiceAtOnce() throws Exception {
		start(Sharing.OFF);
		try (Session a = new Session(PORT)) {
			Socket data = a.pasv();
			a.send("STOR one.bin");
			a.expectPreliminary();
			// (the first transfer is still running: a second STOR of the file is refused before
			// the data connection is asked for)
			a.send("STOR one.bin");
			Reply r = a.read(5000);
			assertEquals(450, r.code, r.toString());
			data.getOutputStream().write("x".getBytes(StandardCharsets.UTF_8));
			data.close();
			a.expectComplete();
			assertEquals("x", read("one.bin"));
		}
	}

	@Test
	public void anotherServerSharingTheStoreIsKeptOutToo() throws Exception {
		start(Sharing.CLUSTER);
		try (Locks other = Locks.open(LockOptions.sharing(Sharing.CLUSTER).withOwnerId("other")
				.withLease(Duration.ofSeconds(3), Duration.ofSeconds(1)));
				Session a = new Session(PORT)) {
			LockDomain d = other.domain("other-ftp", root);
			NamedLock held = d.tryLock("file/x.txt", Mode.EXCLUSIVE);
			assertNotNull(held);
			try {
				assertEquals(450, store(a, "x.txt", "mine").code, "the other server is writing it");
			} finally {
				held.close();
			}
			assertEquals(226, store(a, "x.txt", "mine").code);
			assertEquals("mine", read("x.txt"));
		}
	}

	@Test
	public void aSecondServerIsRefusedAStoreClaimedAlone() throws Exception {
		try (Locks other = Locks.open(LockOptions.sharing(Sharing.OFF).withOwnerId("other"))) {
			StoreClaim alone = other.domain("other-ftp", root).claimStore("other");
			try {
				assertThrows(RuntimeException.class, () -> start(Sharing.OFF), "another server has it");
				server = null;
			} finally {
				alone.close();
			}
		}
		start(Sharing.OFF);
	}

	@Test
	public void theLockDirectoryCantBeReached() throws Exception {
		start(Sharing.OFF);
		Files.write(new File(rootDir, "g.txt").toPath(), "g".getBytes(StandardCharsets.UTF_8));
		assertTrue(new File(rootDir, Locks.DIRECTORY).isDirectory(), "the locks are kept in the root");
		try (Session a = new Session(PORT)) {
			try (Socket data = a.pasv()) {
				a.send("NLST");
				a.expectPreliminary();
				String names = new String(data.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				assertTrue(names.contains("g.txt"), names);
				assertFalse(names.contains(Locks.DIRECTORY), "not listed: " + names);
			}
			a.expectComplete();
			assertFalse(code(a, "CWD " + Locks.DIRECTORY).startsWith("2"), "can't go in");
			assertFalse(code(a, "DELE " + Locks.DIRECTORY + "/regions").startsWith("2"), "nor delete in it");
			assertFalse(code(a, "RMD " + Locks.DIRECTORY).startsWith("2"));
		}
	}
}
