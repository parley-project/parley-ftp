package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * The rest of RFC 959's required commands: STAT (session, transfer and path), REIN and ALLO. Every
 * command gets exactly one reply: each is followed by NOOP, which must get its own 200.
 */
public class TestRfc959Commands {

	private static final int PORT = 8060;
	private static final int BIG = 32 * 1024 * 1024;
	private static final Pattern PASV = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");
	private static FtpServer server;
	private static File root;
	private static SSLContext trustAll;

	@BeforeAll
	public static void start() throws Exception {
		File keystore = new File(TestFtpBaseTestClass.TEST_KEYSTORE);
		TestFtpBaseTestClass.makeTestKeystore(keystore);
		System.setProperty("FtpServer.KeyStoreName", keystore.getPath());
		System.setProperty("FtpServer.KeyStorePassword", "peekab00");
		System.setProperty("FtpServer.KeyStoreType", "PKCS12");
		System.setProperty("FtpServer.Algorithm", "SunX509");
		System.clearProperty(IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);

		root = new File(FtpTestSupport.scratchDir("FtpRfc959")).getAbsoluteFile();
		root.mkdirs();
		new File(root, "sub").mkdirs();
		Files.write(new File(root, "file.txt").toPath(), "hello".getBytes(StandardCharsets.US_ASCII));
		Files.write(new File(root, "sub/inner.txt").toPath(), "x".getBytes(StandardCharsets.US_ASCII));
		Files.write(new File(root, "big.bin").toPath(), new byte[BIG]);

		server = new FtpServer();
		server.setSecure(false);
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.getAbsolutePath()));
		server.setPort(PORT);
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		long t = System.currentTimeMillis();
		while (!server.isRunning() && System.currentTimeMillis() - t < 5000) {
			Thread.sleep(20);
		}
		assertTrue(server.isRunning());

		trustAll = SSLContext.getInstance("TLS");
		trustAll.init(null, new TrustManager[] { new TestFtpBaseTestClass.TrustAll() }, null);
	}

	@AfterAll
	public static void stop() throws Exception {
		if (server != null) {
			server.stop();
		}
		new File(root, "big.bin").delete();
	}

	/** A raw control connection that returns every line of each reply. */
	private static final class Control implements AutoCloseable {
		Socket plain;
		Socket socket;
		BufferedReader in;
		Writer out;

		Control() throws IOException {
			plain = new Socket();
			plain.connect(new InetSocketAddress("127.0.0.1", PORT), 5000);
			attach(plain);
			assertEquals(220, code(read()));
		}

		void attach(Socket s) throws IOException {
			socket = s;
			s.setSoTimeout(10000);
			in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
		}

		void login() throws IOException {
			assertEquals(331, code(send("USER anonymous")));
			assertEquals(230, code(send("PASS x")));
		}

		void write(String command) throws IOException {
			out.write(command + "\r\n");
			out.flush();
		}

		List<String> send(String command) throws IOException {
			write(command);
			return read();
		}

		List<String> read() throws IOException {
			List<String> lines = new ArrayList<>();
			String line = in.readLine();
			assertTrue(line != null, "connection closed");
			assertTrue(line.matches("\\d{3}[ -].*") || line.matches("\\d{3}"), "not a reply line: '" + line + "'");
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

		/** The command's reply, then NOOP must get its own reply (no stray second reply). */
		List<String> sendOne(String command) throws IOException {
			List<String> reply = send(command);
			assertEquals("200 NOOP", send("NOOP").get(0), "a second reply to '" + command + "' was waiting");
			return reply;
		}

		/** PASV, connect, RETR name, and the 150; the data isn't read. */
		Socket startRetr(String name) throws IOException {
			Matcher m = PASV.matcher(send("PASV").get(0));
			assertTrue(m.find());
			Socket data = new Socket();
			data.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6))), 5000);
			data.setSoTimeout(10000);
			assertEquals(150, code(send("RETR " + name)));
			return data;
		}

		@Override
		public void close() throws IOException {
			plain.close();
		}
	}

	private static int code(List<String> reply) {
		return Integer.parseInt(reply.get(0).substring(0, 3));
	}

	private static long drain(Socket data) throws IOException {
		long n = 0;
		try (InputStream in = data.getInputStream()) {
			byte[] buf = new byte[64 * 1024];
			int r;
			while ((r = in.read(buf)) > 0) {
				n += r;
			}
		}
		return n;
	}

	// ------------------------------------------------------------------ ALLO

	@Test
	public void allo() throws Exception {
		try (Control c = new Control()) {
			assertEquals(500, code(c.sendOne("ALLO 100")), "needs a login");
			c.login();
			assertEquals(202, code(c.sendOne("ALLO 1000")));
			assertEquals(202, code(c.sendOne("ALLO 1000 R 80")));
			assertEquals(501, code(c.sendOne("ALLO")));
			assertEquals(501, code(c.sendOne("ALLO x")));
			assertEquals(501, code(c.sendOne("ALLO 10 Q 5")));
		}
	}

	// ------------------------------------------------------------------ STAT

	@Test
	public void statSession() throws Exception {
		try (Control c = new Control()) {
			List<String> r = c.sendOne("STAT");
			assertEquals(211, code(r));
			assertTrue(r.toString().contains("Not logged in"), r.toString());
			assertTrue(r.get(r.size() - 1).startsWith("211 "), r.toString());

			c.login();
			c.sendOne("TYPE I");
			c.sendOne("CWD sub");
			r = c.sendOne("STAT");
			assertEquals(211, code(r));
			String all = String.join("\n", r);
			assertTrue(all.contains("Logged in as"), all);
			assertTrue(all.contains("TYPE: BINARY"), all);
			assertTrue(all.contains("Current directory: /sub"), all);
			assertTrue(all.contains("Data connection: none"), all);
			assertTrue(all.contains("Control connection: clear"), all);
		}
	}

	@Test
	public void statPath() throws Exception {
		try (Control c = new Control()) {
			assertEquals(530, code(c.sendOne("STAT file.txt")), "needs a login");
			c.login();

			List<String> r = c.sendOne("STAT file.txt");
			assertEquals(213, code(r));
			assertEquals(3, r.size(), r.toString());
			assertTrue(r.get(1).startsWith("-") && r.get(1).endsWith(" file.txt"), r.toString());

			r = c.sendOne("STAT /sub");
			assertEquals(212, code(r));
			assertTrue(r.get(0).startsWith("212-"), r.toString());
			assertTrue(r.stream().anyMatch(l -> l.endsWith(" inner.txt")), r.toString());

			r = c.sendOne("STAT /");
			assertTrue(r.stream().anyMatch(l -> l.startsWith("d") && l.endsWith(" sub")), r.toString());

			assertEquals(450, code(c.sendOne("STAT nosuch.txt")));
			assertEquals(450, code(c.sendOne("STAT ../../../etc")));
		}
	}

	@Test
	public void statDuringATransfer() throws Exception {
		try (Control c = new Control()) {
			c.login();
			c.sendOne("TYPE I");
			Socket data = c.startRetr("big.bin");
			// the data isn't read, so the transfer is still going
			List<String> r = c.send("STAT");
			assertEquals(213, code(r), r.toString());
			assertTrue(r.get(0).contains("bytes transferred"), r.toString());
			assertEquals(BIG, drain(data));
			assertEquals(226, code(c.read()));
			assertEquals(211, code(c.sendOne("STAT")), "no transfer now");
		}
	}

	// ------------------------------------------------------------------ REIN

	@Test
	public void reinLogsOutAndResets() throws Exception {
		try (Control c = new Control()) {
			c.login();
			c.sendOne("TYPE I");
			c.sendOne("CWD sub");
			c.sendOne("REST 5");
			c.send("PASV");
			List<String> r = c.sendOne("REIN");
			assertEquals(220, code(r));

			assertEquals(500, code(c.sendOne("PWD")), "logged out");
			String all = String.join("\n", c.sendOne("STAT"));
			assertTrue(all.contains("Not logged in"), all);
			assertTrue(all.contains("TYPE: ASCII"), all);
			assertTrue(all.contains("Data connection: none"), all);

			c.login();
			assertTrue(c.sendOne("PWD").get(0).startsWith("257 \"/\""), "back at the root");
			// the REST was forgotten: the whole file
			Socket data = c.startRetr("file.txt");
			assertEquals(5, drain(data));
			assertEquals(226, code(c.read()));
		}
	}

	@Test
	public void reinLetsATransferComplete() throws Exception {
		try (Control c = new Control()) {
			c.login();
			c.sendOne("TYPE I");
			Socket data = c.startRetr("big.bin");
			c.write("REIN");
			assertEquals(BIG, drain(data));
			// the transfer's reply, then REIN's
			assertEquals(226, code(c.read()));
			assertEquals(220, code(c.read()));
			assertEquals(500, code(c.sendOne("PWD")), "logged out");
		}
	}

	@Test
	public void reinAfterAuthTlsGoesBackToClearText() throws Exception {
		try (Control c = new Control()) {
			assertEquals(234, code(c.send("AUTH TLS")));
			SSLSocket tls = (SSLSocket) trustAll.getSocketFactory().createSocket(c.plain, "localhost", PORT, false);
			tls.setUseClientMode(true);
			tls.startHandshake();
			c.attach(tls);
			c.login();
			assertTrue(String.join("\n", c.sendOne("STAT")).contains("Control connection: TLS"));

			// the reply comes protected, then the server ends TLS (close_notify)
			assertEquals(220, code(c.send("REIN")));
			assertNull(c.in.readLine(), "TLS ended");

			c.attach(c.plain);
			c.login();
			assertTrue(String.join("\n", c.sendOne("STAT")).contains("Control connection: clear"));
		}
	}
}
