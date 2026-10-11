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
}
