package us.bringardner.parley.ftp.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.ftp.client.FtpClient;

/**
 * A server that accepts the connection and never sends its greeting: connect gives up after one
 * command timeout with a SocketTimeoutException, and closes its socket rather than leave it open.
 * Plain and implicit TLS (where the wait is in the handshake).
 */
public class TestSilentServerConnect {

	private static final int TIMEOUT_MS = 2000;

	@Test
	public void plainGivesUpAfterOneTimeoutAndCloses() throws Exception {
		check(false);
	}

	@Test
	public void implicitTlsGivesUpAfterOneTimeoutAndCloses() throws Exception {
		check(true);
	}

	private void check(boolean implicitTls) throws Exception {
		Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
		try (SilentServer server = new SilentServer()) {
			FtpClient client = new FtpClient("127.0.0.1", server.getPort());
			try {
				client.setSecure(implicitTls);
				client.setCmdTimeout(TIMEOUT_MS);
				long start = System.nanoTime();
				assertThrows(SocketTimeoutException.class, () -> client.connect("ftp", "x", null));
				long ms = (System.nanoTime() - start) / 1_000_000;
				assertTrue(ms >= TIMEOUT_MS - 100 && ms < TIMEOUT_MS * 3 / 2, "gave up after " + ms + " ms");
				assertFalse(client.isConnected());
				assertEquals(1, server.accepted(), "connections accepted");
				// before close(): connect itself must have closed the socket
				assertTrue(server.allClosedByClient(TIMEOUT_MS), "the client's socket was left open");
			} finally {
				client.close();
			}
		}
		assertNoNewThreads(before);
	}

	/** Every thread started during the test has ended (given a moment to). */
	static void assertNoNewThreads(Set<Thread> before) throws InterruptedException {
		List<Thread> left = new ArrayList<>();
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (!before.contains(t)) {
				t.join(2000);
				if (t.isAlive()) {
					left.add(t);
				}
			}
		}
		assertTrue(left.isEmpty(), "threads left running: " + left);
	}

	/** Accepts connections on the loopback address and never writes to them. */
	static final class SilentServer implements AutoCloseable {
		private final ServerSocket server;
		private final List<Socket> sockets = new ArrayList<>();
		private final Thread acceptor;

		SilentServer() throws IOException {
			server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			acceptor = new Thread(() -> {
				try {
					while (true) {
						Socket s = server.accept();
						synchronized (sockets) {
							sockets.add(s);
						}
					}
				} catch (IOException e) {
					// closed
				}
			}, "silent-server");
			acceptor.setDaemon(true);
			acceptor.start();
		}

		int getPort() {
			return server.getLocalPort();
		}

		int accepted() {
			synchronized (sockets) {
				return sockets.size();
			}
		}

		/** True if the client has closed every accepted connection (end of stream seen), within the time. */
		boolean allClosedByClient(int withinMs) throws IOException {
			List<Socket> copy;
			synchronized (sockets) {
				copy = new ArrayList<>(sockets);
			}
			long end = System.currentTimeMillis() + withinMs;
			for (Socket s : copy) {
				InputStream in = s.getInputStream();
				byte[] buf = new byte[4096];
				while (true) {
					long left = end - System.currentTimeMillis();
					if (left <= 0) {
						return false;
					}
					s.setSoTimeout((int) left);
					try {
						// what a TLS client sent (its hello) is read and dropped
						if (in.read(buf) < 0) {
							break;
						}
					} catch (SocketTimeoutException e) {
						return false;
					} catch (IOException e) {
						break; // reset by the client: closed too
					}
				}
			}
			return true;
		}

		@Override
		public void close() throws IOException {
			server.close();
			synchronized (sockets) {
				for (Socket s : sockets) {
					s.close();
				}
			}
			try {
				acceptor.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
