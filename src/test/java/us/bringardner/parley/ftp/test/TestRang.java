package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.client.ClientFtpResponse;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * RANG (draft-bryan-ftp-range-08): advertised in FEAT as "RANG STREAM"; RETR sends the inclusive
 * octet range; STOR writes it in place, with or without setStoreAtOffset, and changes nothing outside it.
 */
public class TestRang {

	private static final int ON_PORT = 8058;
	private static final int OFF_PORT = 8059;
	private static FtpServer on;
	private static FtpServer off;
	private static File onRoot;
	private static File offRoot;

	private static FtpServer start(File root, int port, boolean storeAtOffset) throws Exception {
		FtpServer server = new FtpServer();
		server.setSecure(false);
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.getAbsolutePath()));
		server.setPort(port);
		server.setStoreAtOffset(storeAtOffset);
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		long t = System.currentTimeMillis();
		while (!server.isRunning() && System.currentTimeMillis() - t < 5000) {
			Thread.sleep(20);
		}
		assertTrue(server.isRunning());
		return server;
	}

	@BeforeAll
	public static void startServers() throws Exception {
		System.clearProperty(IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);
		onRoot = new File(FtpTestSupport.scratchDir("FtpRangOn")).getAbsoluteFile();
		offRoot = new File(FtpTestSupport.scratchDir("FtpRangOff")).getAbsoluteFile();
		onRoot.mkdirs();
		offRoot.mkdirs();
		on = start(onRoot, ON_PORT, true);
		off = start(offRoot, OFF_PORT, false);
	}

	@AfterAll
	public static void stopServers() throws Exception {
		on.stop();
		off.stop();
		clear(onRoot);
		clear(offRoot);
	}

	private static void clear(File dir) {
		File[] kids = dir.listFiles();
		if( kids != null ) {
			for(File k : kids) {
				k.delete();
			}
		}
	}

	@BeforeEach
	public void empty() {
		clear(onRoot);
		clear(offRoot);
	}

	private static FtpClient client(int port) throws IOException {
		FtpClient c = new FtpClient("localhost", port);
		c.setRequestSecure(false);
		c.getLogger().setLevel(Level.ERROR);
		assertTrue(c.connect("anonymous", "x", null));
		return c;
	}

	private static File file(File root, String name, String content) throws IOException {
		File f = new File(root, name);
		Files.write(f.toPath(), content.getBytes(StandardCharsets.US_ASCII));
		return f;
	}

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
	}

	private static ClientFtpResponse rang(FtpClient c, String args) throws IOException {
		return c.executeCommand("RANG "+args);
	}

	private static String retr(FtpClient c, String name) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		try (InputStream in = c.getInputStream(name, false)) {
			in.transferTo(buf);
		}
		return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
	}

	private static void stor(FtpClient c, String name, String data) throws IOException {
		try (OutputStream out = c.getOutputStream(name, false, false)) {
			out.write(data.getBytes(StandardCharsets.US_ASCII));
		}
	}

	@Test
	public void inFeat() throws Exception {
		FtpClient c = client(ON_PORT);
		try {
			assertEquals("RANG STREAM", c.getFeatResponse().get("RANG"), c.getFeatResponse().toString());
		} finally {
			c.close();
		}
	}

	@Test
	public void replies() throws Exception {
		FtpClient c = client(ON_PORT);
		try {
			ClientFtpResponse r = rang(c, "2 5");
			assertEquals(350, r._getResponseCode(), r.toString());
			assertTrue(r.toString().contains("Restarting at 2. End byte range at 5"), r.toString());
			assertEquals(350, rang(c, "1 0")._getResponseCode());
			assertEquals(350, rang(c, "0 0")._getResponseCode());
			assertEquals(501, rang(c, "5 2")._getResponseCode());
			assertEquals(501, rang(c, "5")._getResponseCode());
			assertEquals(501, rang(c, "a b")._getResponseCode());
			assertEquals(501, rang(c, "-1 5")._getResponseCode());
			assertEquals(501, rang(c, "1 99999999999999999999")._getResponseCode());
		} finally {
			c.close();
		}
	}

	@Test
	public void retrieveARange() throws Exception {
		file(onRoot, "r.txt", "0123456789");
		FtpClient c = client(ON_PORT);
		try {
			assertEquals(350, rang(c, "2 5")._getResponseCode());
			assertEquals("2345", retr(c, "r.txt"));
			// used once: the next one is the whole file
			assertEquals("0123456789", retr(c, "r.txt"));
			assertEquals(350, rang(c, "0 0")._getResponseCode());
			assertEquals("0", retr(c, "r.txt"));
			// an end past the end of the file sends to the end
			assertEquals(350, rang(c, "7 100")._getResponseCode());
			assertEquals("789", retr(c, "r.txt"));
			// a start at or past the end sends nothing
			assertEquals(350, rang(c, "10 20")._getResponseCode());
			assertEquals("", retr(c, "r.txt"));
			assertEquals(350, rang(c, "50 60")._getResponseCode());
			assertEquals("", retr(c, "r.txt"));
		} finally {
			c.close();
		}
	}

	@Test
	public void resetAndInvalidClearTheRange() throws Exception {
		file(onRoot, "x.txt", "0123456789");
		FtpClient c = client(ON_PORT);
		try {
			rang(c, "2 5");
			assertEquals(350, rang(c, "1 0")._getResponseCode());
			assertEquals("0123456789", retr(c, "x.txt"));
			rang(c, "2 5");
			assertEquals(501, rang(c, "5 2")._getResponseCode());
			assertEquals("0123456789", retr(c, "x.txt"));
		} finally {
			c.close();
		}
	}

	@Test
	public void restAndRangReplaceEachOther() throws Exception {
		file(onRoot, "y.txt", "0123456789");
		FtpClient c = client(ON_PORT);
		try {
			c.executeCommand("REST 8");
			rang(c, "1 2");
			assertEquals("12", retr(c, "y.txt"));
			rang(c, "1 2");
			c.executeCommand("REST 8");
			assertEquals("89", retr(c, "y.txt"));
		} finally {
			c.close();
		}
	}

	@Test
	public void storeWritesOnlyTheRange() throws Exception {
		File f = file(onRoot, "s.txt", "0123456789");
		FtpClient c = client(ON_PORT);
		try {
			assertEquals(350, rang(c, "3 5")._getResponseCode());
			stor(c, "s.txt", "abc");
			// in place, not truncated
			assertEquals("012abc6789", read(f));
			// past the old end: it grows
			rang(c, "8 10");
			stor(c, "s.txt", "XYZ");
			assertEquals("012abc67XYZ", read(f));
			// less than the range: only what came
			rang(c, "0 5");
			stor(c, "s.txt", "Q");
			assertEquals("Q12abc67XYZ", read(f));
		} finally {
			c.close();
		}
	}

	@Test
	public void storeOfMoreThanTheRangeFailsAndChangesNothingOutsideIt() throws Exception {
		File f = file(onRoot, "m.txt", "0123456789");
		FtpClient c = client(ON_PORT);
		try {
			rang(c, "2 3");
			assertThrows(IOException.class, () -> stor(c, "m.txt", "abcdef"));
		} finally {
			c.close();
		}
		assertEquals("01ab456789", read(f));
	}

	@Test
	public void storeStartingPastTheEndIsRefused() throws Exception {
		File f = file(onRoot, "p.txt", "abc");
		FtpClient c = client(ON_PORT);
		try {
			rang(c, "10 12");
			assertThrows(IOException.class, () -> stor(c, "p.txt", "zz"));
			// the range was used up: a plain STOR replaces the file
			stor(c, "p.txt", "new");
		} finally {
			c.close();
		}
		assertEquals("new", read(f));
	}

	@Test
	public void storeWorksWhenStoreAtOffsetIsOff() throws Exception {
		File f = file(offRoot, "o.txt", "0123456789");
		FtpClient c = client(OFF_PORT);
		try {
			rang(c, "3 5");
			stor(c, "o.txt", "abc");
			rang(c, "3 5");
			assertEquals("abc", retr(c, "o.txt"));
			// REST then STOR is still refused there
			assertThrows(IOException.class, () -> {
				try (OutputStream out = c.getOutputStream("o.txt", false, 3, 0)) {
					out.write('z');
				}
			});
		} finally {
			c.close();
		}
		assertEquals("012abc6789", read(f));
	}

	@Test
	public void appendRefusesARange() throws Exception {
		File f = file(onRoot, "a.txt", "0123");
		FtpClient c = client(ON_PORT);
		try {
			rang(c, "0 1");
			assertThrows(IOException.class, () -> {
				try (OutputStream out = c.getOutputStream("a.txt", false, true)) {
					out.write('x');
				}
			});
		} finally {
			c.close();
		}
		assertEquals("0123", read(f));
	}
}
