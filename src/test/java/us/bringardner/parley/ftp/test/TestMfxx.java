package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.bringardner.parley.ftp.test.RawFtp.code;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * MFMT, MFCT and MFF (draft-somers-ftp-mfxx) on the server, and the client's setModificationTime,
 * setCreationTime and setFacts.
 */
public class TestMfxx {

	private static final int PORT = 8064;
	private static FtpServer server;
	private static File root;
	private static final long T = 1026940035000L; // 2002-07-17 21:07:15 UTC

	@BeforeAll
	public static void start() throws Exception {
		File base = new File(FtpTestSupport.scratchDir("FtpMfxx")).getAbsoluteFile();
		root = new File(base, "root");
		root.mkdirs();
		File acl = new File(base, "acl.txt");
		Files.write(acl.toPath(), (" admin , 0000 , ADMIN|SITE|READ|WRITE\n plain , 1234 , READ|WRITE\n").getBytes(StandardCharsets.UTF_8));
		System.setProperty(FileBasedAcl.PROP_FILE_NAME, acl.getPath());
		System.setProperty(IServer.AUTHENTICATION_PROVIDER_PROPERTY, FileBasedAcl.class.getCanonicalName());
		server = RawFtp.startServer(PORT, root);
	}

	@AfterAll
	public static void stop() throws Exception {
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);
		System.clearProperty(IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		server.stop();
	}

	private static RawFtp login(String user, String pw) throws Exception {
		RawFtp c = new RawFtp(PORT);
		assertEquals(331, code(c.send("USER " + user)));
		assertEquals(230, code(c.send("PASS " + pw)));
		return c;
	}

	private static File file(String name) throws Exception {
		File f = new File(root, name);
		Files.write(f.toPath(), "data".getBytes(StandardCharsets.US_ASCII));
		return f;
	}

	@Test
	public void feat() throws Exception {
		try (RawFtp c = login("plain", "1234")) {
			String feat = String.join("\n", c.send("FEAT"));
			assertTrue(feat.contains("\n MFMT\n") || feat.contains("\n MFMT"), feat);
			assertTrue(feat.contains("\n MFCT"), feat);
			assertTrue(feat.contains("\n MFF Modify;Create;UNIX.mode;UNIX.owner;UNIX.group;"), feat);
		}
	}

	@Test
	public void mfmt() throws Exception {
		File f = file("m.txt");
		try (RawFtp c = login("plain", "1234")) {
			List<String> r = c.oneReply("MFMT 20020717210715 m.txt");
			assertEquals(213, code(r), r.toString());
			assertTrue(r.get(0).startsWith("213 Modify=20020717210715") && r.get(0).endsWith("; m.txt"), r.toString());
			assertEquals(T, f.lastModified());
			assertEquals(501, c.one("MFMT 20020230120000 m.txt"), "February 30");
			assertEquals(501, c.one("MFMT 2002 m.txt"));
			assertEquals(501, c.one("MFMT 20020717210715"));
			assertEquals(550, c.one("MFMT 20020717210715 nosuch.txt"));
			// MFCT: where the file system can't set a creation time it is 450
			int mfct = c.one("MFCT 20020717210715 m.txt");
			assertTrue(mfct == 213 || mfct == 450, "" + mfct);
		}
	}

	@Test
	public void mff() throws Exception {
		File f = file("f.txt");
		boolean posix = Files.getFileStore(f.toPath()).supportsFileAttributeView("posix");
		try (RawFtp c = login("plain", "1234")) {
			List<String> r = c.oneReply("MFF Modify=20020717210715;" + (posix ? "UNIX.mode=640;" : "") + " f.txt");
			assertEquals(213, code(r), r.toString());
			assertTrue(r.get(0).startsWith("213 Modify=20020717210715"), r.toString());
			assertTrue(r.get(0).endsWith("; f.txt"), r.toString());
			assertEquals(T, f.lastModified());
			if (posix) {
				assertTrue(r.get(0).contains(";UNIX.mode=640;"), r.toString());
				assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())));
			}
			assertEquals("504 Parameter Not Implemented (Foo;X.bar)", c.oneReply("MFF Foo=1;X.bar=2;Modify=20020717210715; f.txt").get(0));
			assertEquals(501, c.one("MFF Modify=20020717210715 f.txt"), "no ';'");
			assertEquals(501, c.one("MFF UNIX.mode=999; f.txt"));
			assertEquals(550, c.one("MFF UNIX.owner=root; f.txt"), "needs ADMIN");
			assertEquals(550, c.one("MFF UNIX.group=root; f.txt"), "needs ADMIN");
		}
	}

	@Test
	public void adminChangesTheGroup() throws Exception {
		File f = file("g.txt");
		Assumptions.assumeTrue(Files.getFileStore(f.toPath()).supportsFileAttributeView("posix"));
		String group = Files.readAttributes(f.toPath(), java.nio.file.attribute.PosixFileAttributes.class).group().getName();
		try (RawFtp c = login("admin", "0000")) {
			List<String> r = c.oneReply("MFF UNIX.group=" + group + "; g.txt");
			assertEquals("213 UNIX.group=" + group + "; g.txt", r.get(0));
		}
	}

	@Test
	public void client() throws Exception {
		File f = file("c.txt");
		FtpClient c = new FtpClient("127.0.0.1", PORT);
		c.setRequestSecure(false);
		c.getLogger().setLevel(Level.ERROR);
		assertTrue(c.connect("plain", "1234", null));
		try {
			assertTrue(c.setModificationTime("c.txt", T + 999));
			assertEquals(T, f.lastModified(), "whole seconds");
			assertTrue(c.getDialog().contains("Write:MFMT 20020717210715 c.txt"), c.getDialog());
			Map<String, String> facts = new LinkedHashMap<>();
			facts.put("Modify", FtpClient.timeVal(T + 60000));
			Map<String, String> set = c.setFacts("c.txt", facts);
			assertTrue(set.get("Modify").startsWith("20020717210815"), set.toString());
			assertEquals(T + 60000, f.lastModified());
			facts.put("Nope", "1");
			assertTrue(c.setFacts("c.txt", facts).isEmpty());
		} finally {
			c.close();
		}
	}
}
