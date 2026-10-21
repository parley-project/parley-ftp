package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * BJL-4: login replies follow RFC 959. A failed PASS gets 530 (not 332), and an account
 * (user@account) can still be given with ACCT after it, or as part of the user name.
 */
public class TestLoginReplies {

	private static final int PORT = 8038;
	private static FtpServer server;
	private static File base;

	@BeforeAll
	public static void startServer() throws Exception {
		base = new File(FtpTestSupport.scratchDir("TestLoginReplies")).getAbsoluteFile();
		File root = new File(base, "root");
		assertTrue(root.mkdirs() || root.isDirectory());
		File acl = new File(base, "acl.txt");
		Files.write(acl.toPath(), (
				" #name , credentials, permissions\n"
				+ " plain      , 1234 , READ\n"
				+ " tony@sales , 5678 , READ\n").getBytes(StandardCharsets.UTF_8));
		System.setProperty(FtpServer.FTP_PREFIX+"."+FileBasedAcl.PROP_FILE_NAME, acl.getPath());
		System.setProperty(FtpServer.FTP_PREFIX+"."+IServer.AUTHENTICATION_PROVIDER_PROPERTY, FileBasedAcl.class.getCanonicalName());

		FileSource fs = FileSourceFactory.getDefaultFactory().createFileSource(root.getAbsolutePath());
		server = new FtpServer();
		server.setSecure(false);
		server.setFtpRoot(fs);
		server.setPort(PORT);
		server.setLoginFailureDelay(0);
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
		System.clearProperty(FtpServer.FTP_PREFIX+"."+FileBasedAcl.PROP_FILE_NAME);
		System.clearProperty(FtpServer.FTP_PREFIX+"."+IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		if (server != null) {
			server.stop();
			long start = System.currentTimeMillis();
			while (server.isRunning() && System.currentTimeMillis() - start < 10000) {
				Thread.sleep(10);
			}
		}
	}

	/** A raw control connection: send a command, get the reply code. */
	private static final class Control implements AutoCloseable {
		final Socket socket = new Socket();
		final BufferedReader in;
		final Writer out;

		Control() throws IOException {
			socket.connect(new InetSocketAddress("127.0.0.1", PORT), 5000);
			socket.setSoTimeout(5000);
			in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
			assertEquals(220, read()); // greeting (RFC 959)
		}

		int send(String command) throws IOException {
			out.write(command + "\r\n");
			out.flush();
			return read();
		}

		/** The code of the next reply (multi-line replies are read to their last line). */
		int read() throws IOException {
			String line = in.readLine();
			while (line != null && line.length() > 3 && line.charAt(3) == '-') {
				String code = line.substring(0, 3);
				do {
					line = in.readLine();
				} while (line != null && !(line.startsWith(code) && (line.length() == 3 || line.charAt(3) == ' ')));
			}
			assertTrue(line != null, "connection closed");
			return Integer.parseInt(line.substring(0, 3));
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}

	@Test
	public void aWrongPasswordGets530() throws Exception {
		try (Control c = new Control()) {
			assertEquals(331, c.send("USER plain"));
			assertEquals(530, c.send("PASS wrong"));
		}
	}

	@Test
	public void anUnknownUserGets530() throws Exception {
		try (Control c = new Control()) {
			assertEquals(331, c.send("USER nobody-here"));
			assertEquals(530, c.send("PASS 1234"));
		}
	}

	@Test
	public void theRightPasswordGets230() throws Exception {
		try (Control c = new Control()) {
			assertEquals(331, c.send("USER plain"));
			assertEquals(230, c.send("PASS 1234"));
		}
	}

	@Test
	public void anAccountCanBePartOfTheUserName() throws Exception {
		try (Control c = new Control()) {
			assertEquals(331, c.send("USER tony@sales"));
			assertEquals(230, c.send("PASS 5678"));
		}
	}

	@Test
	public void anAccountCanFollowAFailedPass() throws Exception {
		try (Control c = new Control()) {
			assertEquals(331, c.send("USER tony"));
			assertEquals(530, c.send("PASS 5678"));
			assertEquals(230, c.send("ACCT sales"));
		}
		try (Control c = new Control()) {
			assertEquals(331, c.send("USER tony"));
			assertEquals(530, c.send("PASS 5678"));
			assertEquals(530, c.send("ACCT marketing"));
		}
	}

	@Test
	public void theClientLogsInWithAnAccount() throws Exception {
		assertTrue(connect("tony", "5678", "sales"));
		assertTrue(connect("plain", "1234", null));
		assertFalse(connect("tony", "5678", null));
		assertFalse(connect("plain", "wrong", null));
	}

	private static boolean connect(String user, String password, String account) throws IOException {
		FtpClient client = new FtpClient("localhost", PORT);
		client.getLogger().setLevel(Level.ERROR);
		client.setRequestSecure(false);
		try {
			return client.connect(user, password, account);
		} finally {
			try {
				client.close();
			} catch (Exception e) {
				// already closed
			}
		}
	}

	/** Fail logins until the server closes the connection; @return how many it took */
	private static int failuresUntilClosed(int max) throws Exception {
		try (Control c = new Control()) {
			for (int i = 1; i <= max+1; i++) {
				assertEquals(331, c.send("USER plain"));
				assertEquals(530, c.send("PASS wrong"));
				c.socket.setSoTimeout(i < max ? 5000 : 2000);
				try {
					// A closed connection reads end of stream (or a reset)
					c.out.write("NOOP\r\n");
					c.out.flush();
					if( c.in.readLine() == null ) {
						return i;
					}
				} catch (IOException e) {
					return i;
				}
			}
			return -1;
		}
	}

	/** The shared maxLoginAttempts setting: 3 by default, as the other servers */
	@Test
	public void closedAfterMaxLoginAttempts() throws Exception {
		assertEquals(3, failuresUntilClosed(3), "closed after the 3rd failed login");
		server.setMaxLoginAttempts(2);
		try {
			assertEquals(2, failuresUntilClosed(2));
		} finally {
			server.setMaxLoginAttempts(3);
		}
	}
}
