package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileUserRegistry;
import us.bringardner.parley.net.server.IServer;

/**
 * A server claims the store of the root its settings give, not the root it had when it was made: servers made
 * with one placeholder root and given their own folders when their settings are read don't keep each other out.
 */
public class TestFtpStoreClaim {

	private final List<FtpServer> servers = new ArrayList<>();
	private String sharingBefore;

	/** Takes its root from "its settings" when they are read, as an application's server does */
	private static class SettingsRoot extends FtpServer {
		private final FileSource wanted;

		SettingsRoot(int port, FileSource placeholder, FileSource wanted) throws IOException {
			setSecure(false);
			setPort(port);
			setFtpRoot(placeholder);
			this.wanted = wanted;
		}

		@Override
		protected void settingsRead(boolean fromFile) throws IOException {
			super.settingsRead(fromFile);
			setFtpRoot(wanted);
		}
	}

	@BeforeEach
	public void setUp() {
		System.setProperty(FtpServer.FTP_PREFIX + "." + IServer.AUTHENTICATION_PROVIDER_PROPERTY, WritableAnonymousUserRegistry.class.getName());
		System.clearProperty(FtpServer.FTP_PREFIX + "." + FileUserRegistry.PROP_FILE_NAME);
		sharingBefore = System.getProperty(FtpServer.SHARED_STORE_PROPERTY);
		System.setProperty(FtpServer.SHARED_STORE_PROPERTY, "off");
	}

	@AfterEach
	public void tearDown() {
		for (FtpServer s : servers) {
			s.stop();
		}
		if (sharingBefore == null) {
			System.clearProperty(FtpServer.SHARED_STORE_PROPERTY);
		} else {
			System.setProperty(FtpServer.SHARED_STORE_PROPERTY, sharingBefore);
		}
	}

	private static File scratch(String prefix) throws IOException {
		File base = new File(FtpTestSupport.scratchDir("FtpStoreClaim"));
		base.mkdirs();
		return Files.createTempDirectory(base.toPath(), prefix).toFile();
	}

	private FileSource folder(File parent, String name) throws Exception {
		File f = new File(parent, name);
		f.mkdirs();
		return FileSourceFactory.getDefaultFactory().createFileSource(f.getCanonicalPath());
	}

	private FtpServer make(int port, FileSource placeholder, FileSource wanted) throws IOException {
		FtpServer s = new SettingsRoot(port, placeholder, wanted);
		s.getLogger().setLevel(LogLevel.ERROR);
		servers.add(s);
		return s;
	}

	private static boolean waitRunning(FtpServer s) throws InterruptedException {
		long t = System.currentTimeMillis();
		while (!s.isRunning() && System.currentTimeMillis() - t < 5000) {
			Thread.sleep(20);
		}
		return s.isRunning();
	}

	@Test
	public void eachServerClaimsTheRootItsSettingsGive() throws Exception {
		File scratch = scratch("p");
		FileSource placeholder = folder(scratch, "placeholder");
		FtpServer a = make(8066, placeholder, folder(scratch, "a"));
		FtpServer b = make(8067, placeholder, folder(scratch, "b"));
		a.start();
		b.start(); // (before: both claimed the placeholder, and the second was refused)
		assertTrue(waitRunning(a) && waitRunning(b));
	}

	@Test
	public void twoServersOnTheSameRootStillKeepEachOtherOut() throws Exception {
		File scratch = scratch("q");
		FileSource placeholder = folder(scratch, "placeholder");
		FileSource same = folder(scratch, "same");
		FtpServer a = make(8068, placeholder, same);
		FtpServer b = make(8069, placeholder, same);
		a.start();
		assertThrows(java.io.UncheckedIOException.class, b::start);
	}
}
