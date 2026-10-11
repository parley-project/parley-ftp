package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.ftp.server.FtpServer.UploadMode;
import us.bringardner.parley.ftp.test.TestTransferReliability.Session;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * What an interrupted STOR leaves, and what REST can then do. By default (ATOMIC) an upload goes to
 * a temporary file that replaces the target when it is complete, so a broken one leaves nothing and
 * there is nothing to resume; and REST can't make STOR add to a file that is complete, which is what
 * a client resuming with the length SIZE gave (the old file's) would otherwise have done. With IN_PLACE
 * a broken upload leaves what arrived and REST carries on with it.
 */
public class TestUploadResume {

	private static final int ATOMIC_PORT = 8052;
	private static final int IN_PLACE_PORT = 8053;
	private static FtpServer atomic;
	private static FtpServer inPlace;
	private static File atomicRoot;
	private static File inPlaceRoot;

	private static FtpServer start(File root, int port, UploadMode mode) throws Exception {
		FtpServer server = new FtpServer();
		server.setSecure(false);
		server.setFtpRoot(FileSourceFactory.getDefaultFactory().createFileSource(root.getAbsolutePath()));
		server.setPort(port);
		if( mode != null ) {
			server.setUploadMode(mode);
		}
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
		System.clearProperty(IServer.AUTHENTICATOION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);
		atomicRoot = new File("target/FtpUploadResumeAtomic").getAbsoluteFile();
		inPlaceRoot = new File("target/FtpUploadResumeInPlace").getAbsoluteFile();
		atomicRoot.mkdirs();
		inPlaceRoot.mkdirs();
		atomic = start(atomicRoot, ATOMIC_PORT, null);
		inPlace = start(inPlaceRoot, IN_PLACE_PORT, UploadMode.IN_PLACE);
	}

	@AfterAll
	public static void stopServers() throws Exception {
		atomic.stop();
		inPlace.stop();
		clear(atomicRoot);
		clear(inPlaceRoot);
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
		clear(atomicRoot);
		clear(inPlaceRoot);
	}

	private static String names(File dir) {
		String[] n = dir.list();
		Arrays.sort(n);
		return Arrays.toString(n);
	}

	private static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	/** Starts STOR, sends some bytes, then drops the data connection (a reset) without finishing. */
	private static void interrupted(Session s, String name, String partial) throws Exception {
		Socket data = s.pasv();
		s.send("STOR " + name);
		s.expectPreliminary();
		data.getOutputStream().write(partial.getBytes(StandardCharsets.UTF_8));
		data.getOutputStream().flush();
		Thread.sleep(300);
		data.setSoLinger(true, 0);
		data.close();
		TestTransferReliability.Reply r = s.read(8000);
		assertEquals(426, r.code, "the server saw it break: " + r);
		Thread.sleep(300);
	}

	/** @return "done 226", or "refused 450 ..." (the transfer wasn't started) */
	private static String store(Session s, String command, String name, String content) throws Exception {
		try (Socket data = s.pasv()) {
			s.send(command + " " + name);
			TestTransferReliability.Reply r = s.read(5000);
			if( r.code >= 400 ) {
				return "refused " + r.code + " " + r.text.trim();
			}
			data.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
			data.getOutputStream().flush();
		}
		TestTransferReliability.Reply r = s.read(5000);
		return "done " + r.code;
	}

	private static String code(Session s, String line) throws Exception {
		s.send(line);
		return "" + s.read(5000).code;
	}

	// ------------------------------------------------------------ atomic (the default)

	@Test
	public void theDefaultIsAtomic() {
		assertEquals(UploadMode.ATOMIC, new FtpServer().getUploadMode());
		assertEquals(UploadMode.ATOMIC, UploadMode.parse("atomic"));
		assertEquals(UploadMode.IN_PLACE, UploadMode.parse("inPlace"));
		assertEquals(UploadMode.IN_PLACE, UploadMode.parse(" IN_PLACE "));
		assertEquals(UploadMode.IN_PLACE, UploadMode.parse("in-place"));
		assertThrows(IllegalArgumentException.class, () -> UploadMode.parse("sometimes"));
	}

	@Test
	public void aBrokenNewUploadLeavesNothingAndHasNothingToResume() throws Exception {
		try (Session s = new Session(ATOMIC_PORT)) {
			interrupted(s, "new.bin", "0123456789");
			assertEquals("[]", names(atomicRoot), "no file and no temporary file");
			assertEquals("550", code(s, "SIZE new.bin"));
			assertEquals("350", code(s, "REST 10"));
			String reply = store(s, "STOR", "new.bin", "the rest");
			assertTrue(reply.startsWith("refused 450"), reply);
			assertEquals("[]", names(atomicRoot));
			// starting again works
			assertEquals("done 226", store(s, "STOR", "new.bin", "0123456789the rest"));
			assertEquals("0123456789the rest", read(new File(atomicRoot, "new.bin")));
		}
	}

	@Test
	public void aBrokenOverwriteLeavesTheOriginalAndRestWithItsSizeIsRefused() throws Exception {
		File old = new File(atomicRoot, "old.bin");
		Files.write(old.toPath(), "OLDOLDOLDOLDOLDOLD".getBytes(StandardCharsets.UTF_8));
		try (Session s = new Session(ATOMIC_PORT)) {
			interrupted(s, "old.bin", "newnewnewn");
			assertEquals("OLDOLDOLDOLDOLDOLD", read(old), "the original is untouched");
			assertEquals("[old.bin]", names(atomicRoot));
			// what a client resuming by SIZE sends: the OLD file's length. It used to be added to.
			s.send("SIZE old.bin");
			TestTransferReliability.Reply size = s.read(5000);
			assertEquals(213, size.code);
			assertTrue(size.text.trim().endsWith("18"), "SIZE gives the old file's length: " + size);
			assertEquals("350", code(s, "REST 18"));
			String reply = store(s, "STOR", "old.bin", "<<tail of the new file>>");
			assertTrue(reply.startsWith("refused 450"), reply);
			assertEquals("OLDOLDOLDOLDOLDOLD", read(old), "and it is not a mixture of the two");
			// the file can be stored again, in full
			assertEquals("done 226", store(s, "STOR", "old.bin", "all new"));
			assertEquals("all new", read(old));
		}
	}

	@Test
	public void restCanNotMakeAStoreAddToACompleteFile() throws Exception {
		File f = new File(atomicRoot, "complete.bin");
		Files.write(f.toPath(), "complete".getBytes(StandardCharsets.UTF_8));
		try (Session s = new Session(ATOMIC_PORT)) {
			assertEquals("350", code(s, "REST 8"));
			assertTrue(store(s, "STOR", "complete.bin", "+more").startsWith("refused 450"));
			assertEquals("350", code(s, "REST 3"));
			assertTrue(store(s, "STOR", "complete.bin", "+more").startsWith("refused 450"));
		}
		assertEquals("complete", read(f));
	}

	@Test
	public void appendStillAppends() throws Exception {
		File f = new File(atomicRoot, "log.txt");
		Files.write(f.toPath(), "line 1\n".getBytes(StandardCharsets.UTF_8));
		try (Session s = new Session(ATOMIC_PORT)) {
			assertEquals("done 226", store(s, "APPE", "log.txt", "line 2\n"));
		}
		assertEquals("line 1\nline 2\n", read(f));
	}

	// ------------------------------------------------------------ in place

	@Test
	public void inPlaceABrokenUploadLeavesWhatArrivedAndRestCarriesOn() throws Exception {
		File f = new File(inPlaceRoot, "big.bin");
		try (Session s = new Session(IN_PLACE_PORT)) {
			interrupted(s, "big.bin", "0123456789");
			assertEquals("0123456789", read(f), "what arrived is there");
			assertEquals("[big.bin]", names(inPlaceRoot), "and no temporary file");
			s.send("SIZE big.bin");
			TestTransferReliability.Reply size = s.read(5000);
			assertEquals(213, size.code);
			assertTrue(size.text.trim().endsWith("10"), "SIZE gives what arrived: " + size);
			assertEquals("350", code(s, "REST 10"));
			assertEquals("done 226", store(s, "STOR", "big.bin", "ABCDEFGHIJ"));
			assertEquals("0123456789ABCDEFGHIJ", read(f), "the whole file");

			// it is complete now: REST can't add to it again
			assertEquals("350", code(s, "REST 20"));
			assertTrue(store(s, "STOR", "big.bin", "more").startsWith("refused 450"));
			assertEquals("0123456789ABCDEFGHIJ", read(f));
		}
	}

	@Test
	public void inPlaceASecondBreakCanBeResumedToo() throws Exception {
		File f = new File(inPlaceRoot, "twice.bin");
		try (Session s = new Session(IN_PLACE_PORT)) {
			interrupted(s, "twice.bin", "AAAA");
			assertEquals("350", code(s, "REST 4"));
			Socket data = s.pasv();
			s.send("STOR twice.bin");
			s.expectPreliminary();
			data.getOutputStream().write("BBBBBB".getBytes(StandardCharsets.UTF_8));
			data.getOutputStream().flush();
			Thread.sleep(300);
			data.setSoLinger(true, 0);
			data.close();
			assertEquals(426, s.read(8000).code);
			Thread.sleep(300);
			assertEquals("AAAABBBBBB", read(f));

			assertEquals("350", code(s, "REST 10"));
			assertEquals("done 226", store(s, "STOR", "twice.bin", "CC"));
			assertEquals("AAAABBBBBBCC", read(f));
		}
	}

	@Test
	public void inPlaceAFileChangedMeanwhileIsNotResumed() throws Exception {
		File f = new File(inPlaceRoot, "changed.bin");
		try (Session s = new Session(IN_PLACE_PORT)) {
			interrupted(s, "changed.bin", "12345");
			// someone else adds to it, so it is no longer where the upload stopped
			Files.write(f.toPath(), "12345678".getBytes(StandardCharsets.UTF_8));
			assertEquals("350", code(s, "REST 8"));
			assertTrue(store(s, "STOR", "changed.bin", "x").startsWith("refused 450"));
			assertEquals("12345678", read(f));
		}
	}

	@Test
	public void inPlaceACompleteUploadReplacesTheOldContent() throws Exception {
		File f = new File(inPlaceRoot, "replace.bin");
		Files.write(f.toPath(), "a much longer old content".getBytes(StandardCharsets.UTF_8));
		try (Session s = new Session(IN_PLACE_PORT)) {
			assertEquals("done 226", store(s, "STOR", "replace.bin", "new"));
		}
		assertEquals("new", read(f), "the old content is replaced, not left behind the new");
	}

	@Test
	public void inPlaceAStoreOntoALinkReplacesTheLinkAndNotTheOriginal() throws Exception {
		File real = new File(inPlaceRoot, "real.txt");
		Files.write(real.toPath(), "original".getBytes(StandardCharsets.UTF_8));
		File link = new File(inPlaceRoot, "ln");
		Files.createSymbolicLink(link.toPath(), real.toPath());
		try (Session s = new Session(IN_PLACE_PORT)) {
			assertEquals("done 226", store(s, "STOR", "ln", "via the link"));
		}
		assertFalse(Files.isSymbolicLink(link.toPath()), "the link was replaced");
		assertEquals("via the link", read(link));
		assertEquals("original", read(real), "the original is as it was");
	}
}
