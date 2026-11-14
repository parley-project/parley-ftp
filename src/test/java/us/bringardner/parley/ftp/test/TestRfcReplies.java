package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileUserRegistry;
import us.bringardner.parley.net.server.IServer;

/**
 * Replies as RFC 959 (with section 5.4's lists), RFC 2389, RFC 2428 and RFC 3659 give them, and
 * the permission each command needs (the design doc's "Permission per command").
 */
public class TestRfcReplies {

	private static final int PORT = 8068;
	private static FtpServer server;
	private static File root;

	@BeforeAll
	public static void startServer() throws Exception {
		File base = new File(FtpTestSupport.scratchDir("TestRfcReplies")).getAbsoluteFile();
		root = new File(base, "root");
		assertTrue(new File(root, "dir").mkdirs());
		Files.write(new File(root, "a.txt").toPath(), "hello".getBytes(StandardCharsets.UTF_8));
		File acl = new File(base, "acl.txt");
		Files.write(acl.toPath(), (" #name , credentials, permissions\n"
				+ " reader , r , READ\n"
				+ " writer , w , READ|WRITE\n"
				+ " admin  , a , READ|WRITE|ADMIN\n"
				+ " nobody , n , \n").getBytes(StandardCharsets.UTF_8));
		System.setProperty(FtpServer.FTP_PREFIX + "." + FileUserRegistry.PROP_FILE_NAME, acl.getPath());
		System.setProperty(FtpServer.FTP_PREFIX + "." + IServer.AUTHENTICATION_PROVIDER_PROPERTY,
				FileUserRegistry.class.getCanonicalName());
		server = RawFtp.startServer(PORT, root);
	}

	@AfterAll
	public static void stopServer() throws Exception {
		System.clearProperty(FtpServer.FTP_PREFIX + "." + FileUserRegistry.PROP_FILE_NAME);
		System.clearProperty(FtpServer.FTP_PREFIX + "." + IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		if (server != null) {
			server.stop();
		}
	}

	private static RawFtp as(String user, String password) throws Exception {
		RawFtp c = new RawFtp(PORT);
		assertEquals(331, RawFtp.code(c.send("USER " + user)));
		assertEquals(230, RawFtp.code(c.send("PASS " + password)));
		return c;
	}

	private static String first(RawFtp c, String command) throws Exception {
		return c.oneReply(command).get(0);
	}

	@Test
	public void noLoginIs530AndAMissingPermission550() throws Exception {
		try (RawFtp c = new RawFtp(PORT)) {
			assertEquals("530 RETR refused: nobody is logged in", first(c, "RETR a.txt"));
			// login only: refused the same way
			assertEquals("530 PASV refused: nobody is logged in", first(c, "PASV"));
			assertEquals("530 STAT refused: nobody is logged in", first(c, "STAT a.txt"));
			// no login needed
			assertEquals(200, c.one("TYPE I"));
			assertEquals(503, c.one("PASS x"), "PASS before USER");
			// anonymous is the registry's policy: this one doesn't accept it, so the PASS is refused
			assertEquals(331, c.one("USER anonymous"));
			assertEquals(530, c.one("PASS guest@example.com"));
			// the classes that answered these names are gone
			assertEquals(500, c.one("AUTHERROR"));
			assertEquals(500, c.one("UNAUTHERROR"));
		}
		try (RawFtp c = as("nobody", "n")) {
			assertEquals("550 PWD refused: nobody doesn't have the read permission", first(c, "PWD"));
			// login only: no permission needed
			assertEquals(227, c.one("PASV"));
			assertEquals(200, c.one("MODE S"));
			assertEquals(350, c.one("REST 0"));
			assertEquals(202, c.one("ALLO 10"));
		}
		try (RawFtp c = as("reader", "r")) {
			assertEquals("550 DELE refused: reader doesn't have the write permission", first(c, "DELE a.txt"));
			assertEquals("550 SITE CHMOD refused: reader doesn't have the write permission", first(c, "SITE CHMOD 600 a.txt"));
			assertEquals("550 SITE modDate refused: reader doesn't have the write permission", first(c, "SITE modDate 1600000000000 a.txt"));
		}
		try (RawFtp c = as("writer", "w")) {
			assertEquals("550 DEBUG refused: writer doesn't have the admin permission", first(c, "DEBUG"));
		}
	}

	@Test
	public void directories() throws Exception {
		try (RawFtp c = as("writer", "w")) {
			assertEquals(501, c.one("CWD"));
			assertEquals(550, c.one("CWD nope"));
			assertEquals(550, c.one("CWD a.txt"));
			assertEquals(550, c.one("CDUP"), "at the root");
			// RFC 959 Appendix II: quoted, with a quote in the name doubled. (A quote is not allowed in
			// a Windows file name, so there the folder is qx and the reply has nothing to double.)
			boolean quoteInName = File.separatorChar != '\\';
			String name = quoteInName ? "q\"x" : "qx";
			String quoted = quoteInName ? "\"/q\"\"x\"" : "\"/qx\"";
			assertEquals("257 "+quoted+" created", first(c, "MKD "+name));
			assertEquals(550, c.one("MKD "+name), "it exists");
			assertEquals(250, c.one("CWD "+name));
			assertEquals("257 "+quoted+" is the current directory", first(c, "PWD"));
			assertEquals(250, c.one("CDUP"));
			assertEquals(550, c.one("DELE "+name), "DELE of a directory");
			assertTrue(new File(root, name).isDirectory());
			assertEquals(550, c.one("RMD nope"));
			assertEquals(550, c.one("RMD a.txt"));
			assertEquals(250, c.one("RMD "+name));
			assertEquals(550, c.one("DELE nope"));
		}
	}

	@Test
	public void renames() throws Exception {
		try (RawFtp c = as("writer", "w")) {
			assertEquals(503, c.one("RNTO a.txt"), "RNTO without RNFR, even onto a file that exists");
			assertEquals(550, c.one("RNFR nope"));
			assertEquals(350, c.one("RNFR a.txt"));
			assertEquals(553, c.one("RNTO dir"), "the new name exists");
		}
	}

	@Test
	public void transfersAndFacts() throws Exception {
		try (RawFtp c = as("writer", "w")) {
			assertEquals(550, c.one("RETR nope"));
			List<String> r = c.oneReply("RETR dir");
			assertEquals(550, RawFtp.code(r));
			assertFalse(r.get(0).contains("Exception"), r.toString());
			assertEquals(553, c.one("STOR dir"));
			assertEquals(501, c.one("REST"));
			assertEquals(501, c.one("REST x"));
			assertEquals(501, c.one("MLSD a.txt"), "RFC 3659 section 7.1");
			assertEquals(550, c.one("MLSD nope"));
			assertEquals(550, c.one("MLST nope"));
			assertEquals(550, c.one("MDTM nope"));
			assertEquals(550, c.one("SIZE nope"));
			assertEquals(450, c.one("LIST nope"), "RFC 959 lists 450 for LIST");
		}
	}

	@Test
	public void informationAndOptions() throws Exception {
		try (RawFtp c = new RawFtp(PORT)) {
			assertEquals("215 UNIX Type: L8", first(c, "SYST"));
			List<String> help = c.oneReply("HELP");
			assertEquals("214-The commands recognized are:", help.get(0));
			assertTrue(String.join(" ", help).contains("RETR"), help.toString());
			assertEquals(501, c.one("HELP NOSUCH"));
			List<String> feat = c.oneReply("FEAT");
			assertTrue(feat.contains(" EPRT") && feat.contains(" EPSV"), feat.toString());
			assertEquals(501, c.one("OPTS UTF8 OFF"), "RFC 2389: 200, 451 or 501");
			assertEquals(533, c.one("CCC"), "RFC 4217: the connection isn't TLS");
			assertEquals("221 Goodbye", c.send("QUIT").get(0));
		}
	}

	@Test
	public void epsvAllRefusesTheOtherWaysUntilRein() throws Exception {
		try (RawFtp c = as("reader", "r")) {
			assertEquals(200, c.one("EPSV ALL"));
			for (String command : new String[] { "PASV", "PORT 127,0,0,1,4,1", "EPRT |1|127.0.0.1|1025|", "LPSV", "SPSV" }) {
				List<String> r = c.oneReply(command);
				assertEquals(503, RawFtp.code(r), command + " " + r);
			}
			assertEquals(229, c.one("EPSV"));
			assertEquals(220, RawFtp.code(c.send("REIN")));
			assertEquals(331, RawFtp.code(c.send("USER reader")));
			assertEquals(230, RawFtp.code(c.send("PASS r")));
			assertEquals(227, c.one("PASV"), "REIN ends EPSV ALL");
		}
	}
}
