package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.ftp.server.FtpServer;

/** The 421 the server sends before it closes a session on its own (RFC 959). */
public class TestCloseReplies {

	private static final int PORT = 8069;

	private static File root(String name) {
		File root = new File(FtpTestSupport.scratchDir(name)).getAbsoluteFile();
		assertTrue(root.mkdirs() || root.isDirectory());
		return root;
	}

	@Test
	public void notLoggedInInTimeIs421() throws Exception {
		FtpServer s = new FtpServer();
		s.setSecure(false);
		s.setFtpRoot(us.bringardner.parley.files.FileSourceFactory.getDefaultFactory().createFileSource(root("CloseLogin").getPath()));
		s.setPort(PORT);
		s.setLoginTimeLimit(300);
		s.getLogger().setLevel(us.bringardner.parley.core.ILogger.Level.ERROR);
		s.start();
		try {
			long t = System.currentTimeMillis();
			while (!s.isRunning() && System.currentTimeMillis() - t < 5000) {
				Thread.sleep(20);
			}
			try (RawFtp c = new RawFtp(PORT)) {
				assertEquals("421 Not logged in in time, closing", c.in.readLine());
				assertNull(c.in.readLine(), "then closed");
			}
		} finally {
			s.stop();
			long t = System.currentTimeMillis();
			while (s.isRunning() && System.currentTimeMillis() - t < 10000) {
				Thread.sleep(20);
			}
		}
	}

	@Test
	public void stoppingTheServerIs421() throws Exception {
		FtpServer s = RawFtp.startServer(PORT + 1, root("CloseStop"));
		try (RawFtp c = new RawFtp(PORT + 1)) {
			assertEquals(200, c.one("NOOP"));
			s.stop();
			assertEquals("421 The server is shutting down", c.in.readLine());
			assertNull(c.in.readLine(), "then closed");
		} finally {
			long t = System.currentTimeMillis();
			while (s.isRunning() && System.currentTimeMillis() - t < 10000) {
				Thread.sleep(20);
			}
		}
	}
}
