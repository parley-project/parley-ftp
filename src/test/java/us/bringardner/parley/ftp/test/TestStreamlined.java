package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.bringardner.parley.ftp.test.RawFtp.code;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * AVBL, DSIZ, RMDA, THMB and CSID (draft-peterson-streamlined-ftp-command-extensions), server and client.
 */
public class TestStreamlined {

	private static final int PORT = 8065;
	private static FtpServer server;
	private static File root;

	@BeforeAll
	public static void start() throws Exception {
		System.clearProperty(IServer.AUTHENTICATION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);
		root = new File(FtpTestSupport.scratchDir("FtpStreamlined")).getAbsoluteFile();
		root.mkdirs();
		Files.write(new File(root, "text.txt").toPath(), "not an image".getBytes(StandardCharsets.US_ASCII));
		BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
		img.setRGB(10, 10, 0xff0000);
		ImageIO.write(img, "png", new File(root, "img.png"));
		server = RawFtp.startServer(PORT, root);
	}

	@AfterAll
	public static void stop() throws Exception {
		server.stop();
	}

	private static File tree(String name) throws Exception {
		File d = new File(root, name);
		new File(d, "a/b").mkdirs();
		Files.write(new File(d, "one.txt").toPath(), new byte[3]);
		Files.write(new File(d, "a/two.txt").toPath(), new byte[4]);
		Files.write(new File(d, "a/b/three.txt").toPath(), new byte[5]);
		return d;
	}

	@Test
	public void feat() throws Exception {
		try (RawFtp c = new RawFtp(PORT).login()) {
			String feat = String.join("\n", c.send("FEAT"));
			for (String f : new String[] { "\n AVBL", "\n DSIZ", "\n RMDA", "\n CSID", "\n THMB " }) {
				assertTrue(feat.contains(f), f + " in " + feat);
			}
			assertTrue(feat.contains("PNG"), feat);
		}
	}

	@Test
	public void avblAndDsiz() throws Exception {
		tree("sized");
		try (RawFtp c = new RawFtp(PORT).login()) {
			List<String> r = c.oneReply("AVBL");
			assertEquals(213, code(r), r.toString());
			assertTrue(Long.parseLong(r.get(0).substring(4)) > 0);
			assertEquals(213, c.one("AVBL sized"));
			assertEquals(550, c.one("AVBL text.txt"));
			assertEquals("213 12", c.oneReply("DSIZ sized").get(0));
			assertEquals("213 9", c.oneReply("DSIZ sized/a").get(0));
			assertEquals(550, c.one("DSIZ text.txt"));
			assertEquals(550, c.one("DSIZ nosuch"));
		}
	}

	@Test
	public void rmda() throws Exception {
		File d = tree("gone");
		try (RawFtp c = new RawFtp(PORT).login()) {
			assertEquals(250, c.one("RMDA gone"));
			assertFalse(d.exists());
			assertEquals(550, c.one("RMDA text.txt"));
			assertEquals(550, c.one("RMDA nosuch"));
			assertEquals(550, c.one("RMDA /"));
			assertTrue(new File(root, "text.txt").exists());
		}
	}

	@Test
	public void thmb() throws Exception {
		try (RawFtp c = new RawFtp(PORT).login()) {
			Socket data = c.pasv();
			List<String> r = c.send("THMB PNG 50 50 img.png");
			assertEquals(150, code(r), r.toString());
			assertTrue(r.get(0).matches(".*\\(\\d+ Bytes\\)"), r.toString());
			byte[] png = RawFtp.drain(data);
			assertEquals(226, code(c.read()));
			BufferedImage t = ImageIO.read(new ByteArrayInputStream(png));
			assertEquals(50, t.getWidth());
			assertEquals(25, t.getHeight());

			data = c.pasv();
			assertEquals(150, code(c.send("THMB jpg 500 500 img.png")));
			BufferedImage j = ImageIO.read(new ByteArrayInputStream(RawFtp.drain(data)));
			assertEquals(200, j.getWidth(), "never made larger");
			assertEquals(226, code(c.read()));

			assertEquals(501, c.one("THMB XYZ 10 10 img.png"));
			assertEquals(501, c.one("THMB PNG 0 10 img.png"));
			assertEquals(501, c.one("THMB PNG 10 img.png"));
			assertEquals(550, c.one("THMB PNG 10 10 text.txt"));
			assertEquals(550, c.one("THMB PNG 10 10 nosuch.png"));
		}
	}

	@Test
	public void csid() throws Exception {
		try (RawFtp c = new RawFtp(PORT)) {
			assertTrue(code(c.send("CSID Name=Client;")) >= 500, "needs a login");
			c.login();
			List<String> r = c.oneReply("CSID Name=Client; Version=1.0.0.1; Vendor=Acme;");
			assertEquals(200, code(r));
			assertTrue(r.get(0).contains("Name=parley-ftp;") && r.get(0).contains("CaseSensitive="), r.toString());
			assertTrue(String.join("\n", c.send("STAT")).contains("Client: Client 1.0.0.1 (Acme)"));
		}
	}

	@Test
	public void client() throws Exception {
		tree("ctree");
		FtpClient c = new FtpClient("127.0.0.1", PORT);
		c.setRequestSecure(false);
		c.getLogger().setLevel(Level.ERROR);
		assertTrue(c.connect("anonymous", "x", null));
		try {
			assertTrue(c.getFreeSpace(null) > 0);
			assertEquals(-1, c.getFreeSpace("text.txt"));
			assertEquals(12, c.getDirectorySize("ctree"));
			byte[] png = c.getThumbnail("img.png", "PNG", 20, 20);
			assertNotNull(png);
			assertEquals(20, ImageIO.read(new ByteArrayInputStream(png)).getWidth());
			Map<String, String> id = c.identify("test", "1", "me");
			assertEquals("parley-ftp", id.get("Name"));
			assertTrue(c.deleteRecursively("ctree"));
			assertFalse(new File(root, "ctree").exists());
			assertFalse(c.deleteRecursively("ctree"));
			// the connection is still in step after all that
			assertTrue(c.executeCommand("NOOP").isPositiveComplet());
		} finally {
			c.close();
		}
	}
}
