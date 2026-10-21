package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * REST n then STOR with FtpServer.setStoreAtOffset(true) writes in place from n and leaves the rest
 * of the file as it was (what random access over FTP needs); with it off (the default) it is refused
 * as before. The client sends the REST straight before STOR and stores nothing if it is refused.
 */
public class TestStoreAtOffset {

	private static final int ON_PORT = 8056;
	private static final int OFF_PORT = 8057;
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
		System.clearProperty(FtpServer.FTP_PREFIX+"."+IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		System.clearProperty(FtpServer.FTP_PREFIX+"."+FileBasedAcl.PROP_FILE_NAME);
		onRoot = new File(FtpTestSupport.scratchDir("FtpStoreAtOffsetOn")).getAbsoluteFile();
		offRoot = new File(FtpTestSupport.scratchDir("FtpStoreAtOffsetOff")).getAbsoluteFile();
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

	private static void storeAt(FtpClient c, String name, long at, String data) throws IOException {
		try (OutputStream out = c.getOutputStream(name, false, at, 0)) {
			out.write(data.getBytes(StandardCharsets.US_ASCII));
		}
	}

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
	}

	@Test
	public void offByDefault() {
		assertFalse(new FtpServer().isStoreAtOffset());
	}

	@Test
	public void writesInTheMiddleAndKeepsTheRest() throws Exception {
		File f = new File(onRoot, "middle.txt");
		Files.write(f.toPath(), "0123456789".getBytes(StandardCharsets.US_ASCII));
		FtpClient c = client(ON_PORT);
		try {
			storeAt(c, "middle.txt", 3, "abc");
			assertEquals("012abc6789", read(f));
			// past the old end: it grows
			storeAt(c, "middle.txt", 8, "XYZ");
			assertEquals("012abc67XYZ", read(f));
			// at the end: adds to it
			storeAt(c, "middle.txt", 11, "!");
			assertEquals("012abc67XYZ!", read(f));
			assertTrue(c.getDialog().contains("Write:REST 3"), c.getDialog());
		} finally {
			c.close();
		}
	}

	@Test
	public void pastTheEndIsRefusedAndNothingChanges() throws Exception {
		File f = new File(onRoot, "short.txt");
		Files.write(f.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
		FtpClient c = client(ON_PORT);
		try {
			assertThrows(IOException.class, () -> storeAt(c, "short.txt", 10, "zz"));
		} finally {
			c.close();
		}
		assertEquals("abc", read(f));
	}

	@Test
	public void startingAtZeroReplacesTheFile() throws Exception {
		File f = new File(onRoot, "zero.txt");
		Files.write(f.toPath(), "0123456789".getBytes(StandardCharsets.US_ASCII));
		FtpClient c = client(ON_PORT);
		try {
			storeAt(c, "zero.txt", 0, "ab");
			assertFalse(c.getDialog().contains("Write:REST"), "REST 0 isn't sent: " + c.getDialog());
		} finally {
			c.close();
		}
		assertEquals("ab", read(f));
	}

	@Test
	public void refusedWhenOffAndNothingChanges() throws Exception {
		File f = new File(offRoot, "off.txt");
		byte[] before = "0123456789".getBytes(StandardCharsets.US_ASCII);
		Files.write(f.toPath(), before);
		FtpClient c = client(OFF_PORT);
		try {
			assertThrows(IOException.class, () -> storeAt(c, "off.txt", 3, "abc"));
			// the connection is still usable
			storeAt(c, "other.txt", 0, "x");
		} finally {
			c.close();
		}
		assertArrayEquals(before, Files.readAllBytes(f.toPath()));
		assertEquals("x", read(new File(offRoot, "other.txt")));
	}
}
