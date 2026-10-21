package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.ftp.server.commands.Host;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * HOST (RFC 7151): virtual hosts on the server, and the client sending HOST before logging in.
 */
public class TestHost {

	private static final int VHOST_PORT = 8061;
	private static final int PLAIN_PORT = 8062;
	private static FtpServer vhosts;
	private static FtpServer plain;
	private static File base;
	private static SSLContext trustAll;

	private static FtpServer start(int port, File root) throws Exception {
		FtpServer s = new FtpServer();
		s.setSecure(false);
		s.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.getAbsolutePath()));
		s.setPort(port);
		s.setLoginFailureDelay(0);
		s.getLogger().setLevel(Level.ERROR);
		return s;
	}

	private static void run(FtpServer s) throws Exception {
		s.start();
		long t = System.currentTimeMillis();
		while (!s.isRunning() && System.currentTimeMillis() - t < 5000) {
			Thread.sleep(20);
		}
		assertTrue(s.isRunning());
	}

	private static File dir(String name, String file) throws IOException {
		File d = new File(base, name);
		d.mkdirs();
		Files.write(new File(d, file).toPath(), name.getBytes(StandardCharsets.US_ASCII));
		return d;
	}

	@BeforeAll
	public static void startServers() throws Exception {
		File keystore = new File(TestFtpBaseTestClass.TEST_KEYSTORE);
		TestFtpBaseTestClass.makeTestKeystore(keystore);
		System.setProperty("parley.ftp.keyStoreName", keystore.getPath());
		System.setProperty("parley.ftp.keyStorePassword", "peekab00");
		System.setProperty("parley.ftp.keyStoreType", "PKCS12");
		System.setProperty("parley.ftp.algorithm", "SunX509");
		System.clearProperty(IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);

		base = new File(FtpTestSupport.scratchDir("FtpHost")).getAbsoluteFile();
		File def = dir("default", "default.txt");
		vhosts = start(VHOST_PORT, def);
		FileSourceFactory f = FileSourceFactory.getDefaultFactory();
		vhosts.addVirtualHost("A.Example", f.createFileSource(dir("a", "a.txt").getAbsolutePath()));
		vhosts.addVirtualHost("b.example.", f.createFileSource(dir("b", "b.txt").getAbsolutePath()));
		run(vhosts);
		plain = start(PLAIN_PORT, def);
		run(plain);

		trustAll = SSLContext.getInstance("TLS");
		trustAll.init(null, new TrustManager[] { new TestFtpBaseTestClass.TrustAll() }, null);
	}

	@AfterAll
	public static void stopServers() throws Exception {
		vhosts.stop();
		plain.stop();
	}

	/** A raw control connection that returns every line of each reply. */
	private static final class Control implements AutoCloseable {
		final Socket plainSocket = new Socket();
		BufferedReader in;
		Writer out;

		Control(int port) throws IOException {
			plainSocket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
			attach(plainSocket);
			assertEquals(220, code(read()));
		}

		void attach(Socket s) throws IOException {
			s.setSoTimeout(10000);
			in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
		}

		/** AUTH TLS, asking for this server name (SNI) */
		void tls(String sni) throws IOException {
			assertEquals(234, code(send("AUTH TLS")));
			SSLSocket tls = (SSLSocket) trustAll.getSocketFactory().createSocket(plainSocket, "localhost", 0, false);
			tls.setUseClientMode(true);
			SSLParameters p = tls.getSSLParameters();
			p.setServerNames(java.util.Collections.singletonList(new SNIHostName(sni)));
			tls.setSSLParameters(p);
			tls.startHandshake();
			attach(tls);
		}

		void login() throws IOException {
			assertEquals(331, code(send("USER anonymous")));
			assertEquals(230, code(send("PASS x")));
		}

		List<String> send(String command) throws IOException {
			out.write(command + "\r\n");
			out.flush();
			return read();
		}

		List<String> read() throws IOException {
			List<String> lines = new ArrayList<>();
			String line = in.readLine();
			assertTrue(line != null, "connection closed");
			lines.add(line);
			if (line.length() > 3 && line.charAt(3) == '-') {
				String end = line.substring(0, 3) + " ";
				do {
					line = in.readLine();
					assertTrue(line != null, "connection closed inside a multi-line reply");
					lines.add(line);
				} while (!line.startsWith(end));
			}
			return lines;
		}

		/** The command's reply, then NOOP must get its own reply. */
		int one(String command) throws IOException {
			List<String> reply = send(command);
			assertEquals("200 NOOP", send("NOOP").get(0), "a second reply to '" + command + "' was waiting");
			return code(reply);
		}

		/** The names STAT / lists at the root */
		String rootListing() throws IOException {
			return String.join("\n", send("STAT /"));
		}

		@Override
		public void close() throws IOException {
			plainSocket.close();
		}
	}

	private static int code(List<String> reply) {
		return Integer.parseInt(reply.get(0).substring(0, 3));
	}

	@Test
	public void names() {
		assertEquals("a.example", Host.normalize("A.Example."));
		assertEquals("192.0.2.1", Host.normalize("192.0.2.1"));
		assertEquals("[2001:db8::c000:201]", Host.normalize("[2001:DB8::c000:201]"));
		assertEquals(Host.HAS_PORT, Host.normalize("a.example:21"));
		assertEquals(Host.HAS_PORT, Host.normalize("[::1]:21"));
		assertNull(Host.normalize("::1"), "IPv6 needs brackets");
		assertNull(Host.normalize("bad_name"));
		assertNull(Host.normalize("-a.example"));
		assertNull(Host.normalize("a..example"));
		assertNull(Host.normalize("300.1.1.1"));
		assertNull(Host.normalize(""));
	}

	@Test
	public void choosesTheVirtualHostsRoot() throws Exception {
		try (Control c = new Control(VHOST_PORT)) {
			assertEquals(220, c.one("HOST a.EXAMPLE"));
			c.login();
			String list = c.rootListing();
			assertTrue(list.contains(" a.txt"), list);
			assertFalse(list.contains("default.txt"), list);
			assertTrue(c.send("PWD").get(0).startsWith("257 \"/\""));
			assertTrue(String.join("\n", c.send("STAT")).contains("Virtual host: a.example"));
		}
	}

	@Test
	public void theLastHostCountsAndNoHostIsTheDefault() throws Exception {
		try (Control c = new Control(VHOST_PORT)) {
			assertEquals(220, c.one("HOST a.example"));
			assertEquals(220, c.one("HOST b.example"));
			c.login();
			assertTrue(c.rootListing().contains(" b.txt"));
		}
		try (Control c = new Control(VHOST_PORT)) {
			c.login();
			assertTrue(c.rootListing().contains(" default.txt"));
		}
	}

	@Test
	public void refusals() throws Exception {
		try (Control c = new Control(VHOST_PORT)) {
			assertEquals(504, c.one("HOST unknown.example"));
			assertEquals(501, c.one("HOST a.example:21"));
			assertEquals(501, c.one("HOST"));
			assertEquals(501, c.one("HOST bad_name"));
			assertEquals(501, c.one("HOST ::1"));
			assertEquals(501, c.one("HOST a.example b.example"));
			assertEquals(504, c.one("HOST 192.0.2.1"), "not this server's address");
			// still the default host
			c.login();
			assertTrue(c.rootListing().contains(" default.txt"));
			assertEquals(503, c.one("HOST a.example"), "after login");
		}
	}

	@Test
	public void thisServersAddressIsTheDefaultHost() throws Exception {
		try (Control c = new Control(VHOST_PORT)) {
			assertEquals(220, c.one("HOST 127.0.0.1"));
			c.login();
			assertTrue(c.rootListing().contains(" default.txt"));
		}
	}

	@Test
	public void hostForgetsUserAndReinForgetsHost() throws Exception {
		try (Control c = new Control(VHOST_PORT)) {
			assertEquals(331, code(c.send("USER anonymous")));
			assertEquals(220, c.one("HOST a.example"));
			assertNotEquals(230, code(c.send("PASS x")), "USER was forgotten");
			c.login();
			assertTrue(c.rootListing().contains(" a.txt"));
			assertEquals(220, code(c.send("REIN")));
			c.login();
			assertTrue(c.rootListing().contains(" default.txt"), "REIN forgot the host");
			assertEquals(220, code(c.send("REIN")));
			assertEquals(220, c.one("HOST b.example"));
			c.login();
			assertTrue(c.rootListing().contains(" b.txt"));
		}
	}

	@Test
	public void withoutVirtualHostsAnyNameIsTheServer() throws Exception {
		try (Control c = new Control(PLAIN_PORT)) {
			assertEquals(220, c.one("HOST anything.example"));
			c.login();
			assertTrue(c.rootListing().contains(" default.txt"));
		}
	}

	@Test
	public void mustMatchTheTlsServerName() throws Exception {
		// HOST after AUTH TLS
		try (Control c = new Control(VHOST_PORT)) {
			c.tls("a.example");
			assertEquals(504, c.one("HOST b.example"));
			assertEquals(220, c.one("HOST a.example"));
			c.login();
			assertTrue(c.rootListing().contains(" a.txt"));
		}
		// HOST before AUTH TLS: a different name closes the connection
		try (Control c = new Control(VHOST_PORT)) {
			assertEquals(220, c.one("HOST b.example"));
			c.tls("a.example");
			List<String> r = c.read();
			assertEquals(421, code(r), r.toString());
		}
	}

	// ------------------------------------------------------------------ the client

	private static FtpClient client(String host, int port) {
		FtpClient c = new FtpClient(host, port);
		c.setRequestSecure(false);
		c.getLogger().setLevel(Level.ERROR);
		return c;
	}

	private static String read(InputStream in) throws IOException {
		try (InputStream i = in) {
			return new String(i.readAllBytes(), StandardCharsets.US_ASCII);
		}
	}

	@Test
	public void clientAsksForItsVirtualHost() throws Exception {
		FtpClient c = client("127.0.0.1", VHOST_PORT);
		c.setVirtualHost("b.example");
		try {
			assertTrue(c.connect("anonymous", "x", null));
			assertTrue(c.getDialog().contains("Write:HOST b.example"), c.getDialog());
			assertEquals("b", read(c.getInputStream("b.txt", false)));
		} finally {
			c.close();
		}
	}

	@Test
	public void clientSendsTheHostItConnectsTo() throws Exception {
		FtpClient c = client("127.0.0.1", VHOST_PORT);
		try {
			assertTrue(c.connect("anonymous", "x", null));
			assertTrue(c.getDialog().contains("Write:HOST 127.0.0.1"), c.getDialog());
		} finally {
			c.close();
		}
		// a name the server doesn't know: refused, and the login goes on
		c = client("localhost", VHOST_PORT);
		try {
			assertTrue(c.connect("anonymous", "x", null));
			assertTrue(c.getDialog().contains("Write:HOST localhost"), c.getDialog());
			assertEquals("default", read(c.getInputStream("default.txt", false)));
		} finally {
			c.close();
		}
		c = client("127.0.0.1", VHOST_PORT);
		c.setSendHost(false);
		try {
			assertTrue(c.connect("anonymous", "x", null));
			assertFalse(c.getDialog().contains("Write:HOST"), c.getDialog());
		} finally {
			c.close();
		}
	}

	@Test
	public void clientFailsWhenItsVirtualHostIsRefused() throws Exception {
		FtpClient c = client("127.0.0.1", VHOST_PORT);
		c.setVirtualHost("unknown.example");
		try {
			assertFalse(c.connect("anonymous", "x", null));
		} finally {
			c.close();
		}
	}

	@Test
	public void clientUsesTheVirtualHostForTls() throws Exception {
		// the test certificate is for localhost: checked against the virtual host's name, it fails
		FtpClient c = client("127.0.0.1", VHOST_PORT);
		c.setTrustManagers(new TrustManager[] { new TestFtpBaseTestClass.TrustAll() });
		c.setRequestSecure(true);
		c.setVirtualHost("a.example");
		try {
			assertThrows(IOException.class, () -> c.connect("anonymous", "x", null));
		} finally {
			c.close();
		}
		// without the check, SNI is a.example, which matches the HOST sent before AUTH
		FtpClient c2 = client("127.0.0.1", VHOST_PORT);
		c2.setTrustManagers(new TrustManager[] { new TestFtpBaseTestClass.TrustAll() });
		c2.setRequestSecure(true);
		c2.setVerifyHostname(false);
		c2.setVirtualHost("a.example");
		try {
			assertTrue(c2.connect("anonymous", "x", null));
			assertTrue(c2.isChannelSecure());
			assertEquals("a", read(c2.getInputStream("a.txt", false)));
		} finally {
			c2.close();
		}
	}
}
