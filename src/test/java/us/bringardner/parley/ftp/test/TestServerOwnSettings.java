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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.ftp.server.FtpServer.SymlinkPolicy;
import us.bringardner.parley.ftp.server.FtpServer.UploadMode;

/**
 * Each FTP server reads its settings under its own prefix, from its own configuration: two servers
 * in one JVM (the mail suite's ftp and ftps) can have different values, passive port ranges
 * included, and a server made with a configuration doesn't read the system properties (To-Do 11.13).
 */
public class TestServerOwnSettings {

	private static FtpServer make(Properties c, String prefix, boolean secure) throws Exception {
		File dir = Files.createTempDirectory("ftp-own-settings").toFile();
		dir.deleteOnExit();
		FileSource root = FileSourceFactory.getDefaultFactory().createFileSource(dir.getAbsolutePath());
		return BaseObject.makeWith(c, () -> new FtpServer(0, "FtpTest", secure, prefix, root));
	}

	@Test
	public void twoServersInOneJvmKeepTheirOwnSettings() throws Exception {
		Properties a = new Properties();
		a.setProperty("parley.mail.ftp.bufferSize", "4096");
		a.setProperty("parley.mail.ftp.dataTimeout", "30s");
		a.setProperty("parley.mail.ftp.connectTimeout", "5s");
		a.setProperty("parley.mail.ftp.allowForeignDataAddress", "true");
		a.setProperty("parley.mail.ftp.symlinkPolicy", "allowedTargets");
		a.setProperty("parley.mail.ftp.allowedLinkTargets", "/a, /b");
		a.setProperty("parley.mail.ftp.uploadMode", "inPlace");
		a.setProperty("parley.mail.ftp.storeAtOffset", "true");
		a.setProperty("parley.mail.ftp.linger", "7");
		a.setProperty("parley.mail.ftp.passiveMinPort", "20000");
		a.setProperty("parley.mail.ftp.passiveMaxPort", "20010");
		Properties b = new Properties();
		b.setProperty("parley.mail.ftps.passiveMinPort", "30000");
		b.setProperty("parley.mail.ftps.passiveMaxPort", "30005");
		b.setProperty("parley.mail.ftps.symlinkPolicy", "follow");

		FtpServer ftp = make(a, "parley.mail.ftp", false);
		FtpServer ftps = make(b, "parley.mail.ftps", true);

		assertEquals(4096, ftp.getBufferSize());
		assertEquals(30_000, ftp.getDataTimeout());
		assertEquals(5_000, ftp.getConnectTimeout());
		assertTrue(ftp.isAllowForeignDataAddress());
		assertEquals(SymlinkPolicy.ALLOWED_TARGETS, ftp.getSymlinkPolicy());
		assertEquals(Arrays.asList("/a", "/b"), ftp.getAllowedLinkTargets());
		assertEquals(UploadMode.IN_PLACE, ftp.getUploadMode());
		assertTrue(ftp.isStoreAtOffset());
		assertEquals(7, ftp.getLinger());
		assertEquals(20000, ftp.getPassiveMinPort());
		assertEquals(20010, ftp.getPassiveMaxPort());

		// the other server's values, or the defaults: nothing leaks from the first
		assertEquals(FtpServer.DEFAULT_BUFFER_SIZE, ftps.getBufferSize());
		assertEquals(FtpServer.DEFAULT_DATA_TIMEOUT, ftps.getDataTimeout());
		assertFalse(ftps.isAllowForeignDataAddress());
		assertEquals(SymlinkPolicy.FOLLOW, ftps.getSymlinkPolicy());
		assertEquals(Collections.emptyList(), ftps.getAllowedLinkTargets());
		assertEquals(UploadMode.ATOMIC, ftps.getUploadMode());
		assertFalse(ftps.isStoreAtOffset());
		assertEquals(-1, ftps.getLinger());
		assertEquals(30000, ftps.getPassiveMinPort());
		assertEquals(30005, ftps.getPassiveMaxPort());

		// changing one server's range leaves the other's alone
		ftp.setPassivePortRange(21000, 21001);
		assertEquals(30000, ftps.getPassiveMinPort());
		assertEquals(30005, ftps.getPassiveMaxPort());
	}

	@Test
	public void aServerWithAConfigurationIgnoresTheSystemProperties() throws Exception {
		String[] names = { "parley.mail.ftp.linger", "parley.mail.ftp.uploadMode", "parley.mail.ftp.passiveMinPort",
				"parley.ftp.linger", "parley.ftp.storeAtOffset" };
		try {
			System.setProperty("parley.mail.ftp.linger", "9");
			System.setProperty("parley.mail.ftp.uploadMode", "inPlace");
			System.setProperty("parley.mail.ftp.passiveMinPort", "40000");
			System.setProperty("parley.ftp.linger", "9");
			System.setProperty("parley.ftp.storeAtOffset", "true");
			FtpServer s = make(new Properties(), "parley.mail.ftp", false);
			assertEquals(-1, s.getLinger());
			assertEquals(UploadMode.ATOMIC, s.getUploadMode());
			assertFalse(s.isStoreAtOffset());
			assertEquals(10333, s.getPassiveMinPort());
		} finally {
			for (String n : names) {
				System.clearProperty(n);
			}
		}
	}

	@Test
	public void aServerAloneInItsJvmReadsItsPrefixFromTheSystemProperties() throws Exception {
		String[] names = { "parley.ftp.linger", "parley.ftp.passiveMinPort", "parley.ftp.passiveMaxPort",
				"parley.ftps.linger" };
		try {
			System.setProperty("parley.ftp.linger", "3");
			System.setProperty("parley.ftp.passiveMinPort", "25000");
			System.setProperty("parley.ftp.passiveMaxPort", "25009");
			System.setProperty("parley.ftps.linger", "4");
			FtpServer ftp = new FtpServer();
			assertEquals(3, ftp.getLinger());
			assertEquals(25000, ftp.getPassiveMinPort());
			assertEquals(25009, ftp.getPassiveMaxPort());
			// a server with implicit TLS made in code reads its own prefix, parley.ftps
			assertEquals(4, new FtpServer(true).getLinger());
		} finally {
			for (String n : names) {
				System.clearProperty(n);
			}
		}
	}
}
