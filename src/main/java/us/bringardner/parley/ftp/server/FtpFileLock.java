package us.bringardner.parley.ftp.server;

import java.io.IOException;

import us.bringardner.parley.net.lock.LockDomain;
import us.bringardner.parley.net.lock.NamedLock;
import us.bringardner.parley.net.lock.StoreLocks;

/**
 * A file's lock while an upload, a delete or a rename changes it (see
 * {@link FtpRequestProcessor#lockFile}), with the lock domain it is in, kept open while it is
 * held. The session's thread takes it; an upload's transfer thread lets it go when the data has
 * arrived. Closing twice does nothing more.
 *
 * @since 1.1.0
 */
public final class FtpFileLock implements AutoCloseable {

	/** For a file outside every store (nothing to keep out): holds nothing. */
	static final FtpFileLock NONE = new FtpFileLock(null, null);

	private final LockDomain domain;
	private final NamedLock lock;
	private boolean closed;

	FtpFileLock(LockDomain domain, NamedLock lock) {
		this.domain = domain;
		this.lock = lock;
	}

	/**
	 * @throws IOException (a LockLostException) if the lock was lost (another server took it over
	 *         after this one was paused past its lease): nothing more may be changed under it
	 */
	public void checkValid() throws IOException {
		if( lock != null ) {
			lock.checkValid();
		}
	}

	/** Let go, from whatever thread. */
	@Override
	public synchronized void close() {
		if( closed || lock == null ) {
			return;
		}
		closed = true;
		try {
			lock.closeFromAnyThread();
		} finally {
			StoreLocks.get().release(domain);
		}
	}

	@Override
	public String toString() {
		return lock == null ? "no lock" : lock.toString();
	}
}
