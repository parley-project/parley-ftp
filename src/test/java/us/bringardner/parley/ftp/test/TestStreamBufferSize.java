package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.ILogger.Level;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.ftp.client.ClientFtpInputStream;
import us.bringardner.parley.ftp.client.ClientFtpOutputStream;
import us.bringardner.parley.ftp.client.FtpClient;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.IServer;

/**
 * A stream can be opened with a buffer of its own. Without one it uses the client's transfer
 * buffer size, and neither is ever below 8 KB. The data is the same whatever the size is.
 */
public class TestStreamBufferSize {

	private static final int PORT = 8037;
	private static final String ROOT_PATH = "target/FtpRootStreamBuffer";

	private static FtpServer server;
	private static File rootDir;

	@BeforeAll
	public static void startServer() throws Exception {
		System.clearProperty(IServer.AUTHENTICATOION_PROVIDER_PROPERTY);
		System.clearProperty(FileBasedAcl.PROP_FILE_NAME);

		rootDir = new File(ROOT_PATH).getAbsoluteFile();
		deleteAll(rootDir);
		assertTrue(rootDir.mkdirs(), "can't create " + rootDir);

		FileSource root = FileSourceFactory.getDefaultFactory().createFileSource(rootDir.getAbsolutePath());
		server = new FtpServer();
		server.setSecure(false);
		server.setFtpRoot(root);
		server.setPort(PORT);
		server.getLogger().setLevel(Level.ERROR);
		server.start();
		long start = System.currentTimeMillis();
		while (!server.isRunning() && System.currentTimeMillis() - start < 5000) {
			Thread.sleep(20);
		}
		assertTrue(server.isRunning(), "server did not start");
	}

	@AfterAll
	public static void stopServer() throws Exception {
		if (server != null) {
			server.stop();
			long start = System.currentTimeMillis();
			while (server.isRunning() && System.currentTimeMillis() - start < 10000) {
				Thread.sleep(10);
			}
		}
		deleteAll(rootDir);
	}

	private static void deleteAll(File f) {
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteAll(k);
			}
		}
		f.delete();
	}

	private static FtpClient connect() throws Exception {
		FtpClient client = new FtpClient("localhost", PORT);
		client.setRequestSecure(false);
		client.getLogger().setLevel(Level.ERROR);
		assertTrue(client.connect("anonymous", "x", null));
		return client;
	}

	private static byte[] data(int size) {
		byte[] b = new byte[size];
		new Random(9).nextBytes(b);
		return b;
	}

	@Test
	public void aStreamUsesTheClientsSizeUnlessAskedForAnother() throws Exception {
		FtpClient client = connect();
		try {
			client.setTransferBufferSize(40_000);
			byte[] content = data(150_000);

			// upload: the client's size, then one of its own
			try (OutputStream out = client.getOutputStream("default.bin")) {
				assertEquals(40_000, ((ClientFtpOutputStream) out).getBufferSize());
				out.write(content);
			}
			try (OutputStream out = client.getOutputStream("own.bin", false, false, 100_000)) {
				assertEquals(100_000, ((ClientFtpOutputStream) out).getBufferSize());
				out.write(content);
			}

			// download
			try (InputStream in = client.getInputStream("default.bin")) {
				assertEquals(40_000, ((ClientFtpInputStream) in).getBufferSize());
				assertArrayEquals(content, in.readAllBytes());
			}
			try (InputStream in = client.getInputStream("own.bin", false, 0, 12_000)) {
				assertEquals(12_000, ((ClientFtpInputStream) in).getBufferSize());
				assertArrayEquals(content, in.readAllBytes());
			}

			// one stream's size doesn't change the client's
			assertEquals(40_000, client.getTransferBufferSize());
		} finally {
			client.close();
		}
	}

	@Test
	public void neverBelow8K() throws Exception {
		FtpClient client = connect();
		try {
			byte[] content = data(30_000);
			try (OutputStream out = client.getOutputStream("small.bin", false, false, 100)) {
				assertEquals(8192, ((ClientFtpOutputStream) out).getBufferSize());
				out.write(content);
			}
			try (InputStream in = client.getInputStream("small.bin", false, 0, 1)) {
				assertEquals(8192, ((ClientFtpInputStream) in).getBufferSize());
				assertArrayEquals(content, in.readAllBytes());
			}
		} finally {
			client.close();
		}
	}

	@Test
	public void bytesAtATimeAndStartingPositionStillWork() throws Exception {
		FtpClient client = connect();
		try {
			byte[] content = data(70_000);
			try (OutputStream out = client.getOutputStream("bytes.bin", false, false, 16_384)) {
				for (byte b : content) {
					out.write(b & 0xff);
				}
			}
			assertEquals(content.length, Files.size(new File(rootDir, "bytes.bin").toPath()));
			try (InputStream in = client.getInputStream("bytes.bin", false, 25_000, 64 * 1024)) {
				byte[] back = in.readAllBytes();
				assertEquals(content.length - 25_000, back.length);
				assertArrayEquals(java.util.Arrays.copyOfRange(content, 25_000, content.length), back);
			}
		} finally {
			client.close();
		}
	}
}
