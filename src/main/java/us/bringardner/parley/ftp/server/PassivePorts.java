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
package us.bringardner.parley.ftp.server;

/**
 * The ports one server's passive (PASV / EPSV) data connections are opened on: a range, tried in
 * turn so that successive connections don't all try the same busy port first. Each FtpServer has
 * its own, so two FTP servers in one JVM (an FTP and an FTPS server, say) can have different ranges.
 */
final class PassivePorts {

	/** The default range */
	static final int DEFAULT_MIN = 10333;
	static final int DEFAULT_MAX = 65333;

	private int min = DEFAULT_MIN;
	private int max = DEFAULT_MAX;
	/** The next port to try (rotates through [min, max]) */
	private int next = DEFAULT_MIN;

	synchronized int getMin() {
		return min;
	}

	synchronized int getMax() {
		return max;
	}

	/**
	 * @throws IllegalArgumentException for a range that is empty or not within 1-65535
	 */
	synchronized void setRange(int min, int max) {
		if( min < 1 || max > 65535 || min > max ) {
			throw new IllegalArgumentException("Invalid passive port range "+min+"-"+max+" (1-65535, min <= max)");
		}
		this.min = min;
		this.max = max;
		if( next < min || next > max ) {
			next = min;
		}
	}

	/**
	 * The port to try first; the range grows to include it if it is outside (the passivePort setting).
	 * @throws IllegalArgumentException for a port not within 1-65535
	 */
	synchronized void startAt(int port) {
		if( port < 1 || port > 65535 ) {
			throw new IllegalArgumentException("Invalid passive port "+port+" (1-65535)");
		}
		next = port;
		if( port < min ) {
			min = port;
		} else if( port > max ) {
			max = port;
		}
	}

	/** @return how many ports the range has */
	synchronized int size() {
		return max - min + 1;
	}

	/** @return the next port to try, always within [min, max] */
	synchronized int next() {
		if( next < min || next > max ) {
			next = min;
		}
		int ret = next;
		next = ret >= max ? min : ret + 1;
		return ret;
	}
}
