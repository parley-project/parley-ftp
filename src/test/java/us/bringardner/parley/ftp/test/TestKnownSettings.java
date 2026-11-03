package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.KnownSettings;

/**
 * To-Do 2.16: parley-ftp's list of settings (META-INF/parley/settings.list) has every name its code
 * spells out, and every name FtpServer on its own reads while it starts and serves a session (from
 * the system properties), so a server's start-up check never warns about a real setting.
 */
public class TestKnownSettings {

	@TempDir
	File dir;

	@Test
	public void everyConstantIsListed() throws Exception {
		List<String> found = new java.util.ArrayList<>(KnownSettings.settingConstants(FtpServer.class));
		// declared but nothing reads it (To-Do 7.7)
		found.remove(FtpServer.DEBUG_PROP);
		assertEquals(List.of(), KnownSettings.load().undeclared(found));
	}

	/** FtpServer on its own (its main) reads the system properties: they are recorded for the test */
	@Test
	public void everyNameAServerOnItsOwnReadsIsListed() throws Exception {
		KnownSettings k = KnownSettings.load();
		Properties before = System.getProperties();
		KnownSettings.RecordingProperties system = new KnownSettings.RecordingProperties(before);
		File root = new File(dir, "ftp");
		root.mkdirs();
		int port = freePort();
		System.setProperties(system);
		try {
			FtpServer s = new FtpServer(port, "FtpServer", false, FtpServer.FTP_PREFIX,
					FileSourceFactory.getDefaultFactory().createFileSource(root.getPath()));
			s.startAndWait(5000);
			try (RawFtp ftp = new RawFtp(port)) {
				ftp.send("FEAT");
				ftp.send("USER someone");
				ftp.send("PASS wrong");
			} finally {
				s.stop(5000, false);
			}
		} finally {
			System.setProperties(before);
		}
		assertEquals(List.of(), k.undeclared(system.asked()));
	}

	private static int freePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
