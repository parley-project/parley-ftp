package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.bringardner.parley.ftp.test.RawFtp.code;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.ftp.client.ClientFtpOutputStream;
import us.bringardner.parley.ftp.client.ClientPassiveDataConnection;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * The rest of RFC 959 (STOU, the RFC 775 X forms, 504 for parameters that aren't implemented, SMNT)
 * and RFC 1639 (LPRT, LPSV), on the server and in the client.
 */
public class TestMoreCommands {

	private static final int PORT = 8063;
	private static FtpServer server;
	private static File root;

	@BeforeAll
	public static void start() throws Exception {
		System.clearProperty(IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);
		root = new File(FtpTestSupport.scratchDir("FtpMoreCommands")).getAbsoluteFile();
		root.mkdirs();
		Files.write(new File(root, "file.txt").toPath(), "hello".getBytes(StandardCharsets.US_ASCII));
		server = RawFtp.startServer(PORT, root);
	}

	@AfterAll
	public static void stop() throws Exception {
		server.stop();
	}

	@Test
	public void xForms() throws Exception {
		try (RawFtp c = new RawFtp(PORT).login()) {
			assertTrue(c.oneReply("XPWD").get(0).startsWith("257 \"/\""));
			assertEquals(257, c.one("XMKD xdir"));
			assertEquals(250, c.one("XCWD xdir"));
			assertTrue(c.oneReply("XPWD").get(0).startsWith("257 \"/xdir\""));
			assertEquals(250, c.one("XCUP"));
			assertEquals(250, c.one("XRMD xdir"));
			assertFalse(new File(root, "xdir").exists());
		}
	}

	@Test
	public void parametersThatArentImplemented() throws Exception {
		try (RawFtp c = new RawFtp(PORT).login()) {
			assertEquals(504, c.one("TYPE E"));
			assertEquals(504, c.one("TYPE L 16"));
			assertEquals(504, c.one("TYPE A T"));
			assertEquals(501, c.one("TYPE"));
			assertEquals(501, c.one("TYPE L"));
			assertEquals(200, c.one("TYPE L 8"));
			assertTrue(String.join("", c.send("STAT")).contains("TYPE: BINARY"));
			assertEquals(200, c.one("TYPE A N"));
			assertEquals(200, c.one("TYPE U"));
			assertTrue(String.join("", c.send("STAT")).contains("TYPE: ASCII"));
			assertEquals(504, c.one("MODE B"));
			assertEquals(504, c.one("MODE C"));
			assertEquals(504, c.one("STRU R"));
			assertEquals(504, c.one("STRU P"));
			assertEquals(502, c.one("SMNT /mnt"));
		}
	}

	@Test
	public void stou() throws Exception {
		try (RawFtp c = new RawFtp(PORT).login()) {
			c.one("TYPE I");
			String prelim = c.sendData("STOU", "one".getBytes(StandardCharsets.US_ASCII));
			assertTrue(prelim.startsWith("150 FILE: ftp-"), prelim);
			String name = prelim.substring("150 FILE: ".length());
			assertEquals("one", read(new File(root, name)));

			prelim = c.sendData("STOU up.txt", "a".getBytes(StandardCharsets.US_ASCII));
			assertEquals("150 FILE: up.txt", prelim);
			prelim = c.sendData("STOU up.txt", "b".getBytes(StandardCharsets.US_ASCII));
			assertEquals("150 FILE: up.txt.1", prelim);
			assertEquals("a", read(new File(root, "up.txt")));
			assertEquals("b", read(new File(root, "up.txt.1")));

			assertEquals(553, c.one("STOU a/b"));
			// no data connection: 425, and the name it made is gone again
			assertEquals(425, c.one("STOU nodata.txt"));
			assertFalse(new File(root, "nodata.txt").exists());
		}
	}

	@Test
	public void lpsvAndLprt() throws Exception {
		try (RawFtp c = new RawFtp(PORT).login()) {
			c.one("TYPE I");
			List<String> r = c.send("LPSV");
			assertEquals(228, code(r), r.toString());
			InetSocketAddress addr = ClientPassiveDataConnection.parseLong(r.get(0));
			assertNotNull(addr, r.toString());
			try (Socket data = new Socket()) {
				data.connect(new InetSocketAddress("127.0.0.1", addr.getPort()), 5000);
				assertEquals(150, code(c.send("RETR file.txt")));
				assertEquals("hello", new String(RawFtp.drain(data), StandardCharsets.US_ASCII));
			}
			assertEquals(226, code(c.read()));

			try (ServerSocket listen = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
				int p = listen.getLocalPort();
				assertEquals(200, code(c.send("LPRT 4,4,127,0,0,1,2," + (p / 256) + "," + (p % 256))));
				assertEquals(150, code(c.send("RETR file.txt")));
				try (Socket data = listen.accept()) {
					assertEquals("hello", new String(RawFtp.drain(data), StandardCharsets.US_ASCII));
				}
				assertEquals(226, code(c.read()));
			}
			assertEquals(501, c.one("LPRT 4,4,127,0,0,1"));
			assertEquals(501, c.one("LPRT 5,4,127,0,0,1,2,1,1"));
			assertEquals(501, c.one("LPRT 4,4,127,0,0,300,2,1,1"));
		}
	}

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
	}

	@Test
	public void clientStoreUnique() throws Exception {
		FtpClient c = new FtpClient("127.0.0.1", PORT);
		c.setRequestSecure(false);
		c.getLogger().setLevel(Level.ERROR);
		assertTrue(c.connect("anonymous", "x", null));
		try {
			String first;
			try (ClientFtpOutputStream out = c.storeUnique("cu.txt", false, 0)) {
				out.write("first".getBytes(StandardCharsets.US_ASCII));
				first = out.getStoredName();
			}
			String second;
			try (ClientFtpOutputStream out = c.storeUnique("cu.txt", false, 0)) {
				out.write("second".getBytes(StandardCharsets.US_ASCII));
				second = out.getStoredName();
			}
			assertEquals("cu.txt", first);
			assertEquals("cu.txt.1", second);
			assertNotEquals(first, second);
			try (InputStream in = c.getInputStream(second, false)) {
				assertArrayEquals("second".getBytes(StandardCharsets.US_ASCII), in.readAllBytes());
			}
			try (OutputStream out = c.storeUnique(null, false, 0)) {
				out.write('x');
			}
		} finally {
			c.close();
		}
	}

	@Test
	public void lang() throws Exception {
		try (RawFtp c = new RawFtp(PORT)) {
			assertTrue(String.join("\n", c.send("FEAT")).contains("\n LANG EN*"));
			assertEquals(200, c.one("LANG"));
			assertEquals(200, c.one("LANG EN"));
			assertEquals(200, c.one("lang en-US"));
			assertEquals(504, c.one("LANG fr"));
			assertEquals(501, c.one("LANG 12"));
			assertEquals(501, c.one("LANG en us"));
		}
		FtpClient c = new FtpClient("127.0.0.1", PORT);
		c.setRequestSecure(false);
		c.getLogger().setLevel(Level.ERROR);
		assertTrue(c.connect("anonymous", "x", null));
		try {
			assertEquals(java.util.Collections.singletonList("EN*"), c.getLanguages());
			assertTrue(c.setLanguage("en"));
			assertFalse(c.setLanguage("de"));
			assertTrue(c.getDialog().contains("Write:LANG de"), c.getDialog());
		} finally {
			c.close();
		}
	}

	private static String hex(String algorithm, String text) throws Exception {
		if( algorithm.equals("CRC32") ) {
			java.util.zip.CRC32 crc = new java.util.zip.CRC32();
			crc.update(text.getBytes(StandardCharsets.US_ASCII));
			return String.format("%08x", crc.getValue());
		}
		StringBuilder ret = new StringBuilder();
		for(byte b : java.security.MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.US_ASCII))) {
			ret.append(String.format("%02x", b & 0xff));
		}
		return ret.toString();
	}

	@Test
	public void hash() throws Exception {
		new File(root, "hdir").mkdirs();
		Files.write(new File(root, "my file 2.txt").toPath(), "spaces".getBytes(StandardCharsets.US_ASCII));
		try (RawFtp c = new RawFtp(PORT).login()) {
			String feat = String.join("\n", c.send("FEAT"));
			assertTrue(feat.contains("\n HASH SHA-256*;SHA-512;SHA-1;MD5;CRC32"), feat);
			assertTrue(feat.contains("\n XCRC") && feat.contains("\n XSHA512"), feat);

			assertEquals("213 SHA-256 0-4 " + hex("SHA-256", "hello") + " file.txt", c.oneReply("HASH file.txt").get(0));
			assertEquals("200 SHA-256", c.oneReply("OPTS HASH").get(0));
			assertEquals("200 MD5", c.oneReply("OPTS HASH md5").get(0));
			assertEquals(501, c.one("OPTS HASH nosuch"));
			assertTrue(String.join("\n", c.send("FEAT")).contains("SHA-1;MD5*;CRC32"));
			assertEquals("213 MD5 0-4 " + hex("MD5", "hello") + " file.txt", c.oneReply("HASH file.txt").get(0));
			assertEquals(350, code(c.send("RANG 1 3")));
			assertEquals("213 MD5 1-3 " + hex("MD5", "ell") + " file.txt", c.oneReply("HASH file.txt").get(0));
			// the range was used up
			assertTrue(c.oneReply("HASH file.txt").get(0).startsWith("213 MD5 0-4 "));
			assertEquals(550, c.one("HASH nosuch.txt"));
			assertEquals(553, c.one("HASH hdir"));

			assertEquals("250 " + hex("MD5", "hello"), c.oneReply("XMD5 file.txt").get(0));
			assertEquals("250 " + hex("CRC32", "he"), c.oneReply("XCRC file.txt 0 2").get(0));
			assertEquals("250 " + hex("SHA-1", "ello"), c.oneReply("XSHA1 \"file.txt\" 1").get(0));
			assertEquals("250 " + hex("SHA-256", "hello"), c.oneReply("XSHA256 file.txt").get(0));
			assertEquals("250 " + hex("SHA-512", "hello"), c.oneReply("XSHA512 file.txt").get(0));
			assertEquals("250 " + hex("MD5", "spaces"), c.oneReply("XMD5 my file 2.txt").get(0));
			assertEquals("250 " + hex("MD5", "pac"), c.oneReply("XMD5 my file 2.txt 1 4").get(0));
			assertEquals(550, c.one("XMD5 nosuch.txt"));
			assertEquals(501, c.one("XMD5 file.txt 4 2"));
		}
		FtpClient c = new FtpClient("127.0.0.1", PORT);
		c.setRequestSecure(false);
		c.getLogger().setLevel(Level.ERROR);
		assertTrue(c.connect("anonymous", "x", null));
		try {
			assertEquals(hex("SHA-256", "hello"), c.hash("file.txt"));
			assertEquals(hex("MD5", "ell"), c.hash("file.txt", "md5", 1, 3));
			assertEquals(hex("SHA-1", "llo"), c.hash("file.txt", "SHA1", 2, -1));
			assertEquals(hex("CRC32", "hello"), c.hash("file.txt", "CRC32"));
			assertEquals(hex("SHA-512", "spaces"), c.hash("my file 2.txt", "SHA-512"));
			assertThrowsIo(() -> c.hash("nosuch.txt"));
		} finally {
			c.close();
		}
	}

	private interface Io {
		void run() throws IOException;
	}

	private static void assertThrowsIo(Io r) {
		try {
			r.run();
		} catch (IOException e) {
			return;
		}
		throw new AssertionError("expected an IOException");
	}
}
