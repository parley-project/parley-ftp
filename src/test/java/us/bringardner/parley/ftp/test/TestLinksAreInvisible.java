package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.ftp.server.FtpServer.SymlinkPolicy;
import us.bringardner.parley.ftp.test.TestTransferReliability.Session;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * An administrator makes files visible to FTP users without copying them by making symbolic
 * links in the served tree. The users are not to know: a link looks like the file or directory
 * it leads to, in every listing and reply, and (this is pinned too) acts on the link itself when
 * it is deleted or renamed.
 */
public class TestLinksAreInvisible {

	private static final int PORT = 8043;
	private static FtpServer server;
	private static File base, root, shared;

	@BeforeAll
	public static void start() throws Exception {
		System.clearProperty(IServer.AUTHENTICATOION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);
		base = new File("target/FtpLinksInvisible").getAbsoluteFile();
		deleteAll(base);
		root = new File(base, "root");
		shared = new File(base, "shared");
		assertTrue(root.mkdirs() && shared.mkdirs());
		try {
			Files.createSymbolicLink(new File(base, "probe").toPath(), shared.toPath());
			new File(base, "probe").delete();
		} catch (UnsupportedOperationException | IOException e) {
			Assumptions.assumeTrue(false, "symbolic links not supported: " + e);
		}
		server = new FtpServer();
		server.setSecure(false);
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.getAbsolutePath()));
		server.setPort(PORT);
		server.setSymlinkPolicy(SymlinkPolicy.ALLOWED_TARGETS);
		server.setAllowedLinkTargets(Arrays.asList(shared.getAbsolutePath()));
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		long t = System.currentTimeMillis();
		while (!server.isRunning() && System.currentTimeMillis() - t < 5000) {
			Thread.sleep(20);
		}
		assertTrue(server.isRunning());
	}

	@AfterAll
	public static void stop() throws Exception {
		if (server != null) {
			server.stop();
		}
		deleteAll(base);
	}

	private static void deleteAll(File f) {
		if (f == null || (!f.exists() && !Files.isSymbolicLink(f.toPath()))) {
			return;
		}
		if (!Files.isSymbolicLink(f.toPath())) {
			f.setWritable(true);
			f.setExecutable(true);
			File[] kids = f.listFiles();
			if (kids != null) {
				for (File k : kids) {
					deleteAll(k);
				}
			}
		}
		f.delete();
	}

	private static void write(File f, String s) throws IOException {
		Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	/**
	 * real.txt and realdir/ are in the root; doc.txt and sub/ are in a directory the administrator
	 * allowed. ln-file and ln-dir lead to the first two, shared-file and shared-dir to the others.
	 */
	@BeforeEach
	public void tree() throws Exception {
		for (File k : root.listFiles()) {
			deleteAll(k);
		}
		for (File k : shared.listFiles()) {
			deleteAll(k);
		}
		write(new File(root, "real.txt"), "real content");
		assertTrue(new File(root, "realdir").mkdir());
		write(new File(root, "realdir/inner.txt"), "inner");
		write(new File(shared, "doc.txt"), "shared document");
		assertTrue(new File(shared, "sub").mkdir());
		write(new File(shared, "sub/deep.txt"), "deep");
		Files.createSymbolicLink(new File(root, "ln-file").toPath(), new File(root, "real.txt").toPath());
		Files.createSymbolicLink(new File(root, "ln-dir").toPath(), new File(root, "realdir").toPath());
		Files.createSymbolicLink(new File(root, "shared-file").toPath(), new File(shared, "doc.txt").toPath());
		Files.createSymbolicLink(new File(root, "shared-dir").toPath(), new File(shared, "sub").toPath());
	}

	// ------------------------------------------------------------ what a client is shown

	private static String listing(Session s, String command, String path) throws IOException {
		String text;
		try (Socket data = s.pasv()) {
			s.send(command + (path == null ? "" : " " + path));
			s.expectPreliminary();
			text = new String(data.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		}
		s.expectComplete();
		return text;
	}

	/** The LIST line of each name: "-rw-r--r-- 1 owner group 12 Jan  1 00:00 name" without the name. */
	private static Map<String, String> listLines(String text) {
		Map<String, String> ret = new TreeMap<>();
		for (String line : text.split("\r?\n")) {
			if (line.trim().isEmpty()) {
				continue;
			}
			String[] parts = line.trim().split("\\s+", 9);
			assertTrue(parts.length == 9, "not a LIST line: " + line);
			ret.put(parts[8], line.substring(0, line.lastIndexOf(parts[8])).trim());
		}
		return ret;
	}

	private static Map<String, String> mlsd(String text) {
		Map<String, String> ret = new TreeMap<>();
		for (String line : text.split("\r?\n")) {
			int sp = line.indexOf(' ');
			if (sp > 0) {
				ret.put(line.substring(sp + 1), line.substring(0, sp));
			}
		}
		return ret;
	}

	private static String without(String facts, String... drop) {
		List<String> keep = new ArrayList<>();
		outer:
		for (String f : facts.split(";")) {
			for (String d : drop) {
				if (f.toLowerCase().startsWith(d + "=")) {
					continue outer;
				}
			}
			keep.add(f);
		}
		return String.join(";", keep);
	}

	@Test
	public void aListingShowsALinkAsWhatItLeadsTo() throws Exception {
		try (Session s = new Session(PORT)) {
			String text = listing(s, "LIST", "/");
			assertFalse(text.contains("->"), "an arrow gives a link away: " + text);
			Map<String, String> lines = listLines(text);
			assertEquals(Arrays.asList("ln-dir", "ln-file", "real.txt", "realdir", "shared-dir", "shared-file"), new ArrayList<>(lines.keySet()));

			assertEquals('-', lines.get("ln-file").charAt(0), "a file, not 'l': " + lines.get("ln-file"));
			assertEquals('d', lines.get("ln-dir").charAt(0), lines.get("ln-dir"));
			assertEquals('-', lines.get("shared-file").charAt(0));
			assertEquals('d', lines.get("shared-dir").charAt(0));
			// the same size as the file it leads to ("12" is "real content")
			assertTrue(lines.get("ln-file").contains(" 12 "), lines.get("ln-file"));
			assertTrue(lines.get("shared-file").contains(" 15 "), lines.get("shared-file"));
			// and, apart from the name, the same line as that file's
			assertEquals(lines.get("real.txt").replaceAll("\\s+", " "), lines.get("ln-file").replaceAll("\\s+", " "));
			assertEquals(lines.get("realdir").replaceAll("\\s+", " "), lines.get("ln-dir").replaceAll("\\s+", " "));
		}
	}

	@Test
	public void machineReadableListingsDoNotMentionLinks() throws Exception {
		try (Session s = new Session(PORT)) {
			String text = listing(s, "MLSD", "/");
			String lower = text.toLowerCase();
			assertFalse(lower.contains("slink") || lower.contains("os.unix") || lower.contains("symlink") || lower.contains("->"), text);
			Map<String, String> m = mlsd(text);
			assertEquals("file", fact(m.get("ln-file"), "type"));
			assertEquals("dir", fact(m.get("ln-dir"), "type"));
			assertEquals("file", fact(m.get("shared-file"), "type"));
			assertEquals("dir", fact(m.get("shared-dir"), "type"));
			assertEquals("12", fact(m.get("ln-file"), "size"));
			// the facts are the facts of what it leads to
			assertEquals(without(m.get("real.txt"), "unique"), without(m.get("ln-file"), "unique"));

			s.send("MLST ln-file");
			String reply = s.expect(250).text;
			assertFalse(reply.toLowerCase().contains("slink") || reply.contains("->"), reply);
			assertTrue(reply.toLowerCase().contains("type=file"), reply);
			// RFC 3659 section 7.2: facts, a space, then the pathname: the client's own name for it
			assertTrue(reply.split("\n")[1].endsWith(" /ln-file"), reply);

			// NLST is names only, and has the links among them
			String names = listing(s, "NLST", "/");
			assertTrue(names.contains("ln-file") && names.contains("shared-dir"), names);
			assertFalse(names.contains("->"), names);
		}
	}

	private static String fact(String facts, String name) {
		for (String f : facts.split(";")) {
			if (f.toLowerCase().startsWith(name.toLowerCase() + "=")) {
				return f.substring(name.length() + 1);
			}
		}
		return null;
	}

	@Test
	public void aLinkIsReadAndEnteredLikeTheThingItLeadsTo() throws Exception {
		try (Session s = new Session(PORT)) {
			assertEquals("real content", new String(s.get("ln-file"), StandardCharsets.UTF_8));
			assertEquals("shared document", new String(s.get("shared-file"), StandardCharsets.UTF_8));
			s.send("SIZE ln-file");
			assertEquals("213 12", s.read(5000).code + " " + 12);
			s.send("MDTM ln-file");
			assertEquals(213, s.read(5000).code);

			s.send("CWD ln-dir");
			assertEquals(250, s.read(5000).code);
			s.send("PWD");
			String pwd = s.expect(257).text;
			assertTrue(pwd.contains("\"/ln-dir\""), "the client's path, not realdir's: " + pwd);
			Map<String, String> inner = listLines(listing(s, "LIST", null));
			assertEquals(Collections.singletonList("inner.txt"), new ArrayList<>(inner.keySet()));
			assertEquals("inner", new String(s.get("inner.txt"), StandardCharsets.UTF_8));

			s.send("CWD /shared-dir");
			assertEquals(250, s.read(5000).code);
			assertEquals("deep", new String(s.get("deep.txt"), StandardCharsets.UTF_8));
			// no reply carries a real path
			s.send("PWD");
			String where = s.expect(257).text;
			assertFalse(where.contains(base.getAbsolutePath()) || where.contains(shared.getName() + "/sub"), where);
		}
	}

	// ------------------------------------------------------------ what a client's changes do

	private static boolean isLink(File f) {
		return Files.isSymbolicLink(f.toPath());
	}

	private static String code(Session s, String line) throws IOException {
		s.send(line);
		return "" + s.read(5000).code;
	}

	@Test
	public void deletingALinkRemovesTheLinkAndNotWhatItLeadsTo() throws Exception {
		try (Session s = new Session(PORT)) {
			assertEquals("250", code(s, "DELE ln-file"));
			assertEquals("250", code(s, "DELE shared-file"));
		}
		assertFalse(new File(root, "ln-file").exists() || isLink(new File(root, "ln-file")));
		assertFalse(isLink(new File(root, "shared-file")) || new File(root, "shared-file").exists());
		assertEquals("real content", read(new File(root, "real.txt")), "the file is still there");
		assertEquals("shared document", read(new File(shared, "doc.txt")), "also outside the root");
	}

	@Test
	public void renamingALinkMovesTheLinkAndNotWhatItLeadsTo() throws Exception {
		try (Session s = new Session(PORT)) {
			assertEquals("350", code(s, "RNFR ln-file"));
			assertEquals("250", code(s, "RNTO ln-renamed"));
		}
		assertFalse(isLink(new File(root, "ln-file")));
		assertTrue(isLink(new File(root, "ln-renamed")), "the link has the new name");
		assertEquals("real content", read(new File(root, "ln-renamed")), "and still leads to the file");
		assertEquals("real content", read(new File(root, "real.txt")));
	}

	@Test
	public void removingALinkedDirectoryRemovesTheLinkAndKeepsTheDirectory() throws Exception {
		try (Session s = new Session(PORT)) {
			assertEquals("250", code(s, "RMD ln-dir"));
			assertEquals("250", code(s, "RMD shared-dir"));
		}
		assertFalse(isLink(new File(root, "ln-dir")));
		assertFalse(isLink(new File(root, "shared-dir")));
		assertEquals("inner", read(new File(root, "realdir/inner.txt")), "the directory and what is in it are kept");
		assertEquals("deep", read(new File(shared, "sub/deep.txt")));
	}

	@Test
	public void aLinkedDirectoryIsWorkedInLikeADirectory() throws Exception {
		// what a client does inside a directory it can see happens in the directory it leads to
		try (Session s = new Session(PORT)) {
			s.put("ln-dir/new.txt", "NEW".getBytes(StandardCharsets.UTF_8));
			assertEquals("257", code(s, "MKD ln-dir/sub"));
			s.put("shared-dir/up.txt", "UP".getBytes(StandardCharsets.UTF_8));
			assertEquals("250", code(s, "DELE ln-dir/inner.txt"));
		}
		assertEquals("NEW", read(new File(root, "realdir/new.txt")));
		assertTrue(new File(root, "realdir/sub").isDirectory());
		assertEquals("UP", read(new File(shared, "sub/up.txt")));
		assertFalse(new File(root, "realdir/inner.txt").exists());
		assertTrue(isLink(new File(root, "ln-dir")), "the link itself is untouched");
	}

	// ------------------------------------------------------------ the original is not changed through a link

	private static String store(Session s, String command, String name, String content) throws IOException {
		try (Socket data = s.pasv()) {
			s.send(command + " " + name);
			TestTransferReliability.Reply r = s.read(5000);
			if (r.code >= 400) {
				return "refused " + r.code + " " + r.text.trim();
			}
			data.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
			data.getOutputStream().flush();
		}
		TestTransferReliability.Reply r = s.read(5000);
		return "done " + r.code + " " + r.text.trim();
	}

	/** The reply code and text of a command that starts a transfer, which is refused before any data. */
	private static String refused(Session s, String command, String name) throws IOException {
		try (Socket data = s.pasv()) {
			s.send(command + " " + name);
			TestTransferReliability.Reply r = s.read(5000);
			if (r.code < 400) {
				data.close();
				s.read(5000);
				return "accepted " + r.code;
			}
			return r.code + " " + r.text.trim();
		}
	}

	private static void assertSaysNothingAboutLinks(String reply) {
		String lower = reply.toLowerCase();
		assertFalse(lower.contains("link") || lower.contains("symbolic") || lower.contains("->"), reply);
		assertFalse(reply.contains(base.getPath()), "no real path: " + reply);
	}

	private static String mode(File f) throws IOException {
		return java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()));
	}

	private static void assertRegularFile(File f, String content) throws IOException {
		assertFalse(isLink(f), f + " is still a link");
		assertEquals(content, read(f));
	}

	/** A write replaces the link with a regular file, and what the link led to is never changed. */
	@Test
	public void aWriteToALinkReplacesItWithARegularFile() throws Exception {
		try (Session s = new Session(PORT)) {
			s.put("ln-file", "NEW".getBytes(StandardCharsets.UTF_8));
			s.put("shared-file", "NEW2".getBytes(StandardCharsets.UTF_8));
		}
		assertRegularFile(new File(root, "ln-file"), "NEW");
		assertRegularFile(new File(root, "shared-file"), "NEW2");
		assertEquals("real content", read(new File(root, "real.txt")), "the original is as it was");
		assertEquals("shared document", read(new File(shared, "doc.txt")), "also outside the root");
	}

	@Test
	public void anAppendGoesOnACopyAndTheOriginalIsNotChanged() throws Exception {
		try (Session s = new Session(PORT)) {
			assertEquals("done 226", store(s, "APPE", "ln-file", "+more").substring(0, 8));
			assertEquals("done 226", store(s, "APPE", "shared-file", "+more").substring(0, 8));
		}
		assertRegularFile(new File(root, "ln-file"), "real content+more");
		assertRegularFile(new File(root, "shared-file"), "shared document+more");
		assertEquals("real content", read(new File(root, "real.txt")));
		assertEquals("shared document", read(new File(shared, "doc.txt")));
	}

	/** REST and STOR carry on an unfinished upload; a file that is a link isn't one, so it is not added to. */
	@Test
	public void aRestartOntoALinkIsRefusedAndNothingChanges() throws Exception {
		try (Session s = new Session(PORT)) {
			assertEquals("350", code(s, "REST 12"));
			String reply = store(s, "STOR", "ln-file", "ABC");
			assertTrue(reply.startsWith("refused 450"), reply);
			assertSaysNothingAboutLinks(reply);
		}
		assertTrue(isLink(new File(root, "ln-file")), "still a link");
		assertEquals("real content", read(new File(root, "real.txt")));
	}

	@Test
	public void theModeAndTimeOfALinkedFileAreSetOnACopy() throws Exception {
		File real = new File(root, "real.txt");
		String originalMode = mode(real);
		long originalTime = new File(shared, "doc.txt").lastModified();
		try (Session s = new Session(PORT)) {
			assertEquals("200", code(s, "SITE CHMOD 640 ln-file"));
			assertEquals("213", code(s, "SITE modDate 1600000000000 shared-file"));
		}
		assertRegularFile(new File(root, "ln-file"), "real content");
		assertEquals("rw-r-----", mode(new File(root, "ln-file")));
		assertRegularFile(new File(root, "shared-file"), "shared document");
		assertEquals(1_600_000_000_000L, new File(root, "shared-file").lastModified());
		assertEquals(originalMode, mode(real), "the original's mode is as it was");
		assertEquals(originalTime, new File(shared, "doc.txt").lastModified(), "and its time");
	}

	@Test
	public void aLinkedDirectoryIsNotCopiedSoItsModeIsNotSet() throws Exception {
		File realdir = new File(root, "realdir");
		String modeBefore = mode(realdir);
		long timeBefore = realdir.lastModified();
		try (Session s = new Session(PORT)) {
			assertEquals("450", code(s, "SITE CHMOD 700 ln-dir"));
			assertEquals("450", code(s, "SITE modDate 1600000000000 ln-dir"));
		}
		assertTrue(isLink(new File(root, "ln-dir")), "still a link");
		assertEquals(modeBefore, mode(realdir), "the directory it leads to is unchanged");
		assertEquals(timeBefore, realdir.lastModified());
	}

	// ------------------------------------------------------------ permissions are the administrator's control

	@Test
	public void aReadOnlyFileCanNotBeStoredOrAppendedToThroughALink() throws Exception {
		File real = new File(root, "real.txt");
		real.setWritable(false);
		try {
			try (Session s = new Session(PORT)) {
				String store = refused(s, "STOR", "ln-file");
				assertTrue(store.startsWith("550 "), store);
				String append = refused(s, "APPE", "ln-file");
				assertTrue(append.startsWith("550 "), append);
				assertSaysNothingAboutLinks(store);
				assertSaysNothingAboutLinks(append);
			}
			assertTrue(isLink(new File(root, "ln-file")), "the link is still there");
			assertEquals("real content", read(real));
		} finally {
			real.setWritable(true);
		}
	}

	@Test
	public void aReadOnlyFileIsNotOverwrittenByAnUploadEither() throws Exception {
		File ro = new File(root, "ro.txt");
		write(ro, "protected");
		ro.setWritable(false);
		try (Session s = new Session(PORT)) {
			String store = refused(s, "STOR", "ro.txt");
			assertTrue(store.startsWith("550 "), "an upload used to replace a read-only file: " + store);
		} finally {
			ro.setWritable(true);
		}
		assertEquals("protected", read(ro));
	}

	@Test
	public void aReadOnlyDirectoryKeepsItsLinks() throws Exception {
		File holder = new File(root, "holder");
		assertTrue(holder.mkdir());
		Files.createSymbolicLink(new File(holder, "inside-link").toPath(), new File(root, "real.txt").toPath());
		holder.setWritable(false);
		try (Session s = new Session(PORT)) {
			assertEquals("450", code(s, "DELE holder/inside-link"));
			assertEquals("350", code(s, "RNFR holder/inside-link"));
			assertEquals("550", code(s, "RNTO holder/other"));
			assertTrue(refused(s, "STOR", "holder/inside-link").startsWith("5"), "an upload can't replace it");
		} finally {
			holder.setWritable(true);
		}
		assertTrue(isLink(new File(holder, "inside-link")), "the link is untouched");
		assertEquals("real content", read(new File(root, "real.txt")));
	}

	@Test
	public void aFileThatIsNotALinkIsStillWritable() throws Exception {
		try (Session s = new Session(PORT)) {
			// the original, by its own name, and a file inside a linked directory
			s.put("real.txt", "replaced".getBytes(StandardCharsets.UTF_8));
			s.put("ln-dir/inner.txt", "inner replaced".getBytes(StandardCharsets.UTF_8));
			assertEquals("200", code(s, "SITE CHMOD 640 real.txt"));
			assertEquals("213", code(s, "SITE modDate 1600000000000 ln-dir/inner.txt"));
		}
		assertEquals("replaced", read(new File(root, "real.txt")));
		assertEquals("inner replaced", read(new File(root, "realdir/inner.txt")));
		assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(
				Files.getPosixFilePermissions(new File(root, "real.txt").toPath())));
		assertEquals(1_600_000_000_000L, new File(root, "realdir/inner.txt").lastModified());
	}
}
