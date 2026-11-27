package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.logging.testing.ErrorsExpected;
import us.bringardner.parley.logging.testing.LogCapture;

/** The 421 the server sends before it closes a session on its own (RFC 959). */
public class TestCloseReplies {

	private static final int PORT = 8069;

	private static File root(String name) {
		File root = new File(FtpTestSupport.scratchDir(name)).getAbsoluteFile();
		assertTrue(root.mkdirs() || root.isDirectory());
		return root;
	}

	@ErrorsExpected
	@Test
	public void notLoggedInInTimeIs421() throws Exception {
		FtpServer s = new FtpServer();
		s.setSecure(false);
		s.setFtpRoot(us.bringardner.parley.files.FileSourceFactory.getDefaultFactory().createFileSource(root("CloseLogin").getPath()));
		s.setPort(PORT);
		s.setLoginTimeLimit(300);
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

	@ErrorsExpected
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

	/** To-Do 19.7: QUIT is the reason in the server's disconnect line. */
	@Test
	@ResourceLock(Resources.SYSTEM_OUT)
	public void quitIsTheReasonInTheDisconnectLine() throws Exception {
		try (LogCapture capture = new LogCapture()) {
			FtpServer s = RawFtp.startServer(PORT + 2, root("CloseQuit"));
			try {
				try (RawFtp c = new RawFtp(PORT + 2)) {
					// (not one(): it sends a NOOP after the command, and the connection is closed by then)
					assertTrue(c.send("QUIT").get(0).startsWith("221"));
					assertNull(c.in.readLine(), "then closed");
				}
				String line = null;
				long end = System.currentTimeMillis() + 10000;
				while (line == null && System.currentTimeMillis() < end) {
					line = capture.first("Disconnected (");
					if (line == null) {
						Thread.sleep(20);
					}
				}
				assertTrue(line != null, "no disconnect line");
				assertTrue(line.startsWith("INFO [" + s.getName() + "-") && line.contains("] Disconnected (client quit) after "), line);
			} finally {
				s.stop();
				long t = System.currentTimeMillis();
				while (s.isRunning() && System.currentTimeMillis() - t < 10000) {
					Thread.sleep(20);
				}
			}
		}
	}
}
