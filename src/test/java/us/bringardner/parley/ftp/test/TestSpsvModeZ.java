package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.bringardner.parley.ftp.test.RawFtp.code;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
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
 * SPSV / SPDT (draft-rosenau-ftp-single-port) and MODE Z (draft-preston-ftpext-deflate), server and client.
 */
public class TestSpsvModeZ {

	private static final int PORT = 8066;
	private static FtpServer server;
	private static File root;
	private static byte[] big;

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
		root = new File(FtpTestSupport.scratchDir("FtpSpsvZ")).getAbsoluteFile();
		root.mkdirs();
		Files.write(new File(root, "file.txt").toPath(), "hello".getBytes(StandardCharsets.US_ASCII));
		// compressible, and big enough for several deflate blocks
		StringBuilder b = new StringBuilder();
		Random r = new Random(1);
		while (b.length() < 300_000) {
			b.append("line ").append(r.nextInt(1000)).append('\n');
		}
		big = b.toString().getBytes(StandardCharsets.US_ASCII);
		Files.write(new File(root, "big.txt").toPath(), big);
		server = RawFtp.startServer(PORT, root);
	}

	@AfterAll
	public static void stop() throws Exception {
		server.stop();
	}

	private static Socket spdt(String id) throws Exception {
		Socket s = new Socket();
		s.connect(new InetSocketAddress("127.0.0.1", PORT), 5000);
		s.setSoTimeout(10000);
		RawFtp r = new RawFtp(s);
		assertEquals("200 DATA", r.send("SPDT " + id).get(0));
		return s;
	}

	private static String id(List<String> reply) {
		String l = reply.get(0);
		return l.substring(l.lastIndexOf('(') + 1, l.lastIndexOf(')'));
	}

	@Test
	public void spsvRetrieveAndStore() throws Exception {
		try (RawFtp c = new RawFtp(PORT).login()) {
			assertTrue(String.join("\n", c.send("FEAT")).contains("\n SPSV"));
			c.one("TYPE I");
			List<String> r = c.send("SPSV");
			assertEquals(227, code(r), r.toString());
			String id = id(r);
			Socket data = spdt(id);
			assertEquals(150, code(c.send("RETR file.txt")));
			assertEquals("hello", new String(RawFtp.drain(data), StandardCharsets.US_ASCII));
			assertEquals(226, code(c.read()));

			// the identifier was used up
			try (Socket again = new Socket()) {
				again.connect(new InetSocketAddress("127.0.0.1", PORT), 5000);
				again.setSoTimeout(10000);
				RawFtp a = new RawFtp(again);
				assertEquals(504, code(a.send("SPDT " + id)));
			}

			data = spdt(id(c.send("SPSV")));
			assertEquals(150, code(c.send("STOR up.txt")));
			try (Socket d = data) {
				d.getOutputStream().write("uploaded".getBytes(StandardCharsets.US_ASCII));
			}
			assertEquals(226, code(c.read()));
			assertEquals("uploaded", new String(Files.readAllBytes(new File(root, "up.txt").toPath()), StandardCharsets.US_ASCII));
			assertEquals(200, c.one("NOOP"));
		}
	}

	@Test
	public void spsvIsRefusedWhenTheProtectionDiffers() throws Exception {
		SSLContext trustAll = SSLContext.getInstance("TLS");
		trustAll.init(null, new TrustManager[] { new TestFtpBaseTestClass.TrustAll() }, null);
		try (RawFtp c = new RawFtp(PORT)) {
			assertEquals(234, code(c.send("AUTH TLS")));
			SSLSocket tls = (SSLSocket) trustAll.getSocketFactory().createSocket(c.plain, "localhost", PORT, false);
			tls.setUseClientMode(true);
			tls.startHandshake();
			c.attach(tls);
			c.login();
			c.send("PBSZ 0");
			c.send("PROT P");
			assertEquals(504, c.one("SPSV"));
			c.send("PROT C");
			assertEquals(227, c.one("SPSV"));
		}
	}

	private static byte[] inflate(byte[] z) throws Exception {
		try (InputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(z))) {
			return in.readAllBytes();
		}
	}

	@Test
	public void modeZ() throws Exception {
		try (RawFtp c = new RawFtp(PORT).login()) {
			assertTrue(String.join("\n", c.send("FEAT")).contains("\n MODE Z"));
			c.one("TYPE I");
			assertEquals(200, c.one("MODE Z"));
			assertEquals("200 MODE Z LEVEL 9", c.oneReply("OPTS MODE Z LEVEL 9").get(0));
			byte[] z = c.receive("RETR big.txt");
			assertTrue(z.length < big.length / 2, "compressed: " + z.length);
			assertArrayEquals(big, inflate(z));
			String list = new String(inflate(c.receive("LIST")), StandardCharsets.UTF_8);
			assertTrue(list.contains("big.txt"), list);

			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			try (OutputStream d = new DeflaterOutputStream(buf)) {
				d.write(big);
			}
			c.sendData("STOR zup.txt", buf.toByteArray());
			assertArrayEquals(big, Files.readAllBytes(new File(root, "zup.txt").toPath()));

			assertEquals(504, c.one("REST 10"));
			assertEquals(504, c.one("RANG 0 10"));
			assertEquals(200, c.one("MODE S"));
			assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), c.receive("RETR file.txt"));
			assertEquals(350, code(c.send("REST 1")));
		}
	}

	private static FtpClient client() throws Exception {
		FtpClient c = new FtpClient("127.0.0.1", PORT);
		c.setRequestSecure(false);
		c.getLogger().setLevel(Level.ERROR);
		assertTrue(c.connect("anonymous", "x", null));
		return c;
	}

	@Test
	public void clientSpsvAndModeZ() throws Exception {
		FtpClient c = client();
		try {
			c.setUseSinglePort(true);
			assertTrue(c.setCompression(true));
			try (InputStream in = c.getInputStream("big.txt", false)) {
				assertArrayEquals(big, in.readAllBytes());
			}
			try (OutputStream out = c.getOutputStream("cz.txt", false)) {
				out.write(big);
			}
			assertArrayEquals(big, Files.readAllBytes(new File(root, "cz.txt").toPath()));
			assertTrue(java.util.Arrays.toString(c.executeList()).contains("big.txt"), "listing in MODE Z");
			// from a position: REST isn't used in MODE Z, the client skips
			try (InputStream in = c.getInputStream("big.txt", false, 100)) {
				assertEquals(big.length - 100, in.readAllBytes().length);
			}
			String d = c.getDialog();
			assertTrue(d.contains("Write:SPSV") && d.contains("Write:MODE Z"), d);
			assertTrue(c.setCompression(false));
			try (InputStream in = c.getInputStream("file.txt", false)) {
				assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
			}
		} finally {
			c.close();
		}
	}
}
