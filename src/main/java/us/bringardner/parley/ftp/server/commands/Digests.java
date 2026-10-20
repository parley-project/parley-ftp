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
package us.bringardner.parley.ftp.server.commands;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

import us.bringardner.parley.files.FileSource;

/**
 * The hashes HASH and the X commands (XCRC, XMD5, XSHA1, XSHA256, XSHA512) compute over a file, or part
 * of one, read from the file system as the octets RETR in TYPE I would send.
 *
 * @since 1.1.0
 */
public final class Digests {

	/** The algorithms, by their names in HASH (draft-ietf-ftpext2-hash) */
	public static final String SHA_256 = "SHA-256";
	public static final String SHA_512 = "SHA-512";
	public static final String SHA_1 = "SHA-1";
	public static final String MD5 = "MD5";
	public static final String CRC32 = "CRC32";
	/** All of them, in the order FEAT lists them */
	public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(SHA_256, SHA_512, SHA_1, MD5, CRC32));
	/** What HASH uses until OPTS HASH chooses another */
	public static final String DEFAULT = SHA_256;
	/** How often a long hash says it is still working (HASH sends "213-" lines) */
	public static final long KEEP_ALIVE_MS = 5000;

	private Digests() {
	}

	/** @return the algorithm's name as HASH spells it, or null if it isn't one of these */
	public static String find(String name) {
		if( name == null ) {
			return null;
		}
		String n = name.trim().toUpperCase(Locale.ROOT);
		for(String a : NAMES) {
			if( a.equals(n) || a.replace("-", "").equals(n.replace("-", "")) ) {
				return a;
			}
		}
		return null;
	}

	/** Something told every {@link #KEEP_ALIVE_MS} while a hash is being computed */
	public interface KeepAlive {
		void stillWorking() throws IOException;
	}

	/**
	 * @param start the first octet
	 * @param length octets from start (fewer if the file ends first)
	 * @param keepAlive told now and then during a long hash, or null
	 * @return the hash in lower case hex
	 */
	public static String compute(FileSource file, String algorithm, long start, long length, KeepAlive keepAlive) throws IOException {
		Update digest = digest(algorithm);
		byte[] buf = new byte[64*1024];
		long next = System.currentTimeMillis() + KEEP_ALIVE_MS;
		try (InputStream in = start > 0 ? file.getInputStream(start) : file.getInputStream()) {
			long left = length;
			while( left > 0 ) {
				int n = in.read(buf, 0, (int) Math.min(buf.length, left));
				if( n < 0 ) {
					break;
				}
				digest.update(buf, n);
				left -= n;
				if( keepAlive != null && System.currentTimeMillis() >= next ) {
					keepAlive.stillWorking();
					next = System.currentTimeMillis() + KEEP_ALIVE_MS;
				}
			}
		}
		return digest.hex();
	}

	private interface Update {
		void update(byte[] b, int len);
		String hex();
	}

	private static Update digest(String algorithm) throws IOException {
		if( CRC32.equals(algorithm) ) {
			final CRC32 crc = new CRC32();
			return new Update() {
				public void update(byte[] b, int len) {
					crc.update(b, 0, len);
				}
				public String hex() {
					return String.format("%08x", crc.getValue());
				}
			};
		}
		final MessageDigest md;
		try {
			md = MessageDigest.getInstance(algorithm);
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("No "+algorithm+" here", e);
		}
		return new Update() {
			public void update(byte[] b, int len) {
				md.update(b, 0, len);
			}
			public String hex() {
				StringBuilder ret = new StringBuilder();
				for(byte v : md.digest()) {
					ret.append(String.format("%02x", v & 0xff));
				}
				return ret.toString();
			}
		};
	}
}
