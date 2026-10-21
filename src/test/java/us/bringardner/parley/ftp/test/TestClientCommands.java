package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import javax.net.ssl.TrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * The client's STAT, NLST, REIN, CCC, ABOR-on-close and CLNT, and the server's CLNT and CCC.
 */
public class TestClientCommands {

	private static final int PORT = 8067;
	private static FtpServer server;
	private static File root;
	private static byte[] big = new byte[8 * 1024 * 1024];

	@BeforeAll
	public static void start() throws Exception {
		File keystore = new File(TestFtpBaseTestClass.TEST_KEYSTORE);
		TestFtpBaseTestClass.makeTestKeystore(keystore);
		System.setProperty("parley.ftp.keyStoreName", keystore.getPath());
		System.setProperty("parley.ftp.keyStorePassword", "peekab00");
		System.setProperty("parley.ftp.keyStoreType", "PKCS12");
		System.setProperty("parley.ftp.algorithm", "SunX509");
		System.clearProperty(IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);
		root = new File(FtpTestSupport.scratchDir("FtpClientCommands")).getAbsoluteFile();
		new File(root, "sub").mkdirs();
		Files.write(new File(root, "file.txt").toPath(), "hello".getBytes(StandardCharsets.US_ASCII));
		new java.util.Random(3).nextBytes(big);
		Files.write(new File(root, "big.bin").toPath(), big);
		server = RawFtp.startServer(PORT, root);
	}

	@AfterAll
	public static void stop() throws Exception {
		server.stop();
		new File(root, "big.bin").delete();
	}

	private static FtpClient client(boolean tls) throws Exception {
		FtpClient c = new FtpClient("127.0.0.1", PORT);
		c.setRequestSecure(tls);
		if (tls) {
			c.setTrustManagers(new TrustManager[] { new TestFtpBaseTestClass.TrustAll() });
			c.setVerifyHostname(false);
		}
		c.getLogger().setLevel(Level.ERROR);
		assertTrue(c.connect("anonymous", "x", null));
		return c;
	}

	private static String read(FtpClient c, String name) throws Exception {
		try (InputStream in = c.getInputStream(name, false)) {
			return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
		}
	}

	@Test
	public void serverClntAndCcc() throws Exception {
		try (RawFtp c = new RawFtp(PORT)) {
			assertTrue(String.join("\n", c.send("FEAT")).contains("\n CLNT"));
			assertEquals(200, c.one("CLNT MyClient 1.0"));
			assertEquals(501, c.one("CLNT"));
			assertEquals(533, c.one("CCC"), "nothing to clear");
			c.login();
			assertTrue(String.join("\n", c.send("STAT")).contains("Client: MyClient 1.0"));
		}
	}

	@Test
	public void clnt() throws Exception {
		FtpClient c = client(false);
		try {
			assertTrue(c.getDialog().contains("Write:CLNT parley-ftp"), c.getDialog());
			assertTrue(c.status().contains("Client: parley-ftp"), c.status());
		} finally {
			c.close();
		}
		FtpClient q = new FtpClient("127.0.0.1", PORT);
		q.setRequestSecure(false);
		q.setSendClientName(false);
		q.getLogger().setLevel(Level.ERROR);
		assertTrue(q.connect("anonymous", "x", null));
		try {
			assertFalse(q.getDialog().contains("Write:CLNT"), q.getDialog());
		} finally {
			q.close();
		}
	}

	@Test
	public void statusAndNameList() throws Exception {
		FtpClient c = client(false);
		try {
			assertTrue(c.status().contains("Logged in as"), c.status());
			String[] f = c.status("file.txt");
			assertEquals(1, f.length, Arrays.toString(f));
			assertTrue(f[0].endsWith(" file.txt"), f[0]);
			assertTrue(Arrays.toString(c.status("/")).contains(" sub"));
			assertTrue(Arrays.asList(c.nameList(null)).contains("file.txt"), Arrays.toString(c.nameList(null)));
			assertEquals(0, c.nameList("nosuch").length);
			assertEquals("hello", read(c, "file.txt"));
		} finally {
			c.close();
		}
	}

	@Test
	public void abortOnClose() throws Exception {
		for (boolean abort : new boolean[] { true, false }) {
			FtpClient c = client(false);
			c.setAbortOnClose(abort);
			try {
				try (InputStream in = c.getInputStream("big.bin", false)) {
					byte[] b = new byte[10];
					assertEquals(10, in.readNBytes(b, 0, 10));
					assertArrayEquals(Arrays.copyOf(big, 10), b);
				}
				assertEquals(abort, c.getDialog().contains("Write:ABOR"), c.getDialog());
				// still in step
				assertEquals("hello", read(c, "file.txt"));
				assertTrue(c.executeCommand("NOOP").isPositiveComplet());
			} finally {
				c.close();
			}
		}
	}

	@Test
	public void reinitialize() throws Exception {
		for (boolean tls : new boolean[] { false, true }) {
			FtpClient c = client(tls);
			try {
				assertTrue(c.setCurrentDir("/sub"));
				assertTrue(c.reinitialize(), "tls=" + tls);
				assertEquals(tls, c.isChannelSecure());
				assertEquals("/", c.getCurrentDir());
				assertEquals("hello", read(c, "file.txt"));
			} finally {
				c.close();
			}
		}
	}

	@Test
	public void clearCommandChannel() throws Exception {
		FtpClient c = client(true);
		try {
			assertTrue(c.isChannelSecure());
			assertTrue(c.clearCommandChannel());
			assertFalse(c.isChannelSecure());
			assertTrue(c.isDataChannelSecure(), "data connections stay protected");
			assertTrue(c.status().contains("Control connection: clear; data connections: TLS"), c.status());
			assertEquals("hello", read(c, "file.txt"));
			assertFalse(c.clearCommandChannel(), "already clear");
			// a new connection starts plain again and negotiates TLS
			c.close();
			assertTrue(c.connect("anonymous", "x", null));
			assertTrue(c.isChannelSecure());
			assertEquals("hello", read(c, "file.txt"));
		} finally {
			c.close();
		}
	}
}
