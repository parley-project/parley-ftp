/**
 * <PRE>
 *
 * Copyright Tony Bringarder 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *
 *	@author Tony Bringardner
 */
package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Files;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;

/**
 * The FTP and FTPS servers share the external address and the passive port range: the FTPS server
 * reads its own setting first, then the FTP server's.
 */
public class TestSharedSettings {

	/** The system properties a test set with setSystem, cleared after it */
	private static final java.util.Set<String> SET_HERE = new java.util.HashSet<>();

	private static void setSystem(java.util.Properties settings) {
		for (String n : settings.stringPropertyNames()) {
			System.setProperty(n, settings.getProperty(n));
			SET_HERE.add(n);
		}
	}

	@org.junit.jupiter.api.AfterEach
	public void clearSystem() {
		for (String n : SET_HERE) {
			System.clearProperty(n);
		}
		SET_HERE.clear();
	}


	private static FtpServer make(Properties c, String prefix, boolean secure, String... fallbacks) throws Exception {
		File dir = Files.createTempDirectory("ftp-shared").toFile();
		dir.deleteOnExit();
		FileSource root = FileSourceFactory.getDefaultFactory().createFileSource(dir.getAbsolutePath());
		setSystem(c);
		return new FtpServer(0, "FtpTest", secure, prefix, root, fallbacks);
	}

	@Test
	public void ftpsTakesTheFtpSettingsWhenItHasNone() throws Exception {
		Properties c = new Properties();
		c.setProperty("parley.mail.ftp.externalAddress", "1.2.3.4");
		c.setProperty("parley.mail.ftp.passiveMinPort", "20000");
		c.setProperty("parley.mail.ftp.passiveMaxPort", "20010");
		FtpServer ftp = make(c, "parley.mail.ftp", false);
		FtpServer ftps = make(c, "parley.mail.ftps", true, "parley.mail.ftp");
		assertEquals("1.2.3.4", ftp.getExternalAddress());
		assertEquals("1.2.3.4", ftps.getExternalAddress());
		assertEquals(20000, ftps.getPassiveMinPort());
		assertEquals(20010, ftps.getPassiveMaxPort());
	}

	@Test
	public void ftpsOwnSettingWinsAndFtpIsUnaffected() throws Exception {
		Properties c = new Properties();
		c.setProperty("parley.mail.ftp.externalAddress", "1.2.3.4");
		c.setProperty("parley.mail.ftps.externalAddress", "5.6.7.8");
		c.setProperty("parley.mail.ftp.passiveMinPort", "20000");
		c.setProperty("parley.mail.ftp.passiveMaxPort", "20010");
		c.setProperty("parley.mail.ftps.passiveMinPort", "30000");
		c.setProperty("parley.mail.ftps.passiveMaxPort", "30005");
		FtpServer ftp = make(c, "parley.mail.ftp", false);
		FtpServer ftps = make(c, "parley.mail.ftps", true, "parley.mail.ftp");
		assertEquals("1.2.3.4", ftp.getExternalAddress());
		assertEquals("5.6.7.8", ftps.getExternalAddress());
		assertEquals(20000, ftp.getPassiveMinPort());
		assertEquals(30000, ftps.getPassiveMinPort());
		assertEquals(30005, ftps.getPassiveMaxPort());
	}

	@Test
	public void ftpNeverReadsTheFtpsSettings() throws Exception {
		Properties c = new Properties();
		c.setProperty("parley.mail.ftps.externalAddress", "5.6.7.8");
		c.setProperty("parley.mail.ftps.passiveMinPort", "30000");
		c.setProperty("parley.mail.ftps.passiveMaxPort", "30005");
		FtpServer ftp = make(c, "parley.mail.ftp", false);
		assertNull(ftp.getExternalAddress());
		assertEquals(10333, ftp.getPassiveMinPort());
	}

	@Test
	public void withoutFallbacksNothingChanges() throws Exception {
		Properties c = new Properties();
		c.setProperty("parley.mail.ftp.externalAddress", "1.2.3.4");
		FtpServer ftps = make(c, "parley.mail.ftps", true);
		assertNull(ftps.getExternalAddress());
	}

	@Test
	public void otherSettingsAreNotShared() throws Exception {
		Properties c = new Properties();
		c.setProperty("parley.mail.ftp.linger", "7");
		FtpServer ftps = make(c, "parley.mail.ftps", true, "parley.mail.ftp");
		assertEquals(-1, ftps.getLinger());
	}

	@Test
	public void theDefaultServersShareThroughParleyFtp() throws Exception {
		String[] names = { "parley.ftp.externalAddress", "parley.ftp.passiveMinPort", "parley.ftp.passiveMaxPort" };
		try {
			System.setProperty(names[0], "9.9.9.9");
			System.setProperty(names[1], "26000");
			System.setProperty(names[2], "26009");
			FtpServer ftps = new FtpServer(0, "FtpTest", true);
			assertEquals("9.9.9.9", ftps.getExternalAddress());
			assertEquals(26000, ftps.getPassiveMinPort());
			System.setProperty("parley.ftps.externalAddress", "8.8.8.8");
			assertEquals("8.8.8.8", ftps.getExternalAddress());
		} finally {
			for (String n : names) {
				System.clearProperty(n);
			}
			System.clearProperty("parley.ftps.externalAddress");
		}
	}
}
