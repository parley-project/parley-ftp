package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpProviders;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.mail.server.MailServerProvider;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.net.lock.Sharing;
import us.bringardner.parley.net.server.KnownSettings;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerLauncher;
import us.bringardner.parley.net.server.ServerMain;

/**
 * To-Do 2.16: parley-ftp's list of settings (META-INF/parley/settings.list) has every name its code
 * spells out, and every name its servers read while they start and serve a session (the mail
 * suite's ftp and ftps, from their configurations, and FtpServer on its own, from the system
 * properties), so a server's start-up check never warns about a real setting.
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

	@Test
	public void everyNameTheSuitesServersReadIsListed() throws Exception {
		KnownSettings k = KnownSettings.load();
		assertTrue(k.prefixes().containsAll(List.of(FtpProviders.FTP_PREFIX, FtpProviders.FTPS_PREFIX)), k.prefixes().toString());
		File mail = new File(dir, "mail");
		MailLocks.writeStoreSettings(FileSourceFactory.getDefaultFactory().createFileSource(mail.getPath()), Sharing.OFF);
		File queue = new File(mail, ".smtp-queue/queue.properties");
		queue.getParentFile().mkdirs();
		Files.write(queue.toPath(), "hostname=mx.example.com\ndomains=example.com\n".getBytes(StandardCharsets.UTF_8));
		File users = new File(dir, "users.txt");
		Files.write(users.toPath(), "tony@example.com , secret , READ|WRITE\n".getBytes(StandardCharsets.UTF_8));

		for (String id : List.of("ftp", "ftps")) {
			KnownSettings.RecordingProperties c = new KnownSettings.RecordingProperties();
			c.setProperty("parley.mail.root", mail.getPath());
			c.setProperty("parley.mail.users", users.getPath());
			MailServerProvider p = (MailServerProvider) ServerLauncher.providers().get(id);
			int port = freePort();
			c.setProperty(p.prefix() + ".port", "" + port);
			c.setProperty(p.prefix() + ".requireTls", "false");
			p.enabled(c);
			p.unavailable(c);
			p.warnings(c);
			p.port(c);
			if (!p.implicitTls()) {
				ServerMain.Service s = p.create(new ServerContext(), c);
				s.startAndWait(5000);
				try (RawFtp ftp = new RawFtp(port)) {
					assertEquals(331, RawFtp.code(ftp.send("USER tony@example.com")));
					assertEquals(230, RawFtp.code(ftp.send("PASS secret")));
					ftp.send("PWD");
					ftp.send("FEAT");
					byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
					ftp.sendData("STOR a.txt", data);
					ftp.receive("RETR a.txt");
				} finally {
					s.stop(5000);
				}
			}
			assertEquals(List.of(), k.undeclared(c.asked()), id + " read these");
		}
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
