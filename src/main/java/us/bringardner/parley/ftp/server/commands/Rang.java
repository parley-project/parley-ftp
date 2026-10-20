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

import us.bringardner.parley.ftp.server.FeatCommand;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * RANG: an octet range for the next RETR or STOR (draft-bryan-ftp-range-08, an expired Internet-Draft,
 * never an RFC; advertised in FEAT as "RANG STREAM").
 *
 * <pre>
 *   range-command = "RANG" SP start-point SP end-point CRLF
 * </pre>
 *
 * Both points count octets from the start of the file, and the end point is inclusive: "RANG 0 1" is the
 * first two octets. A good range is saved and answered with 350; "RANG 1 0" resets it (350). A start
 * after the end, or anything that isn't two numbers, is answered with 501 and the range is reset.
 * <ul>
 * <li>RETR sends the octets from start to end. An end past the end of the file sends to the end of the
 * file; a start at or past the end of the file sends nothing.</li>
 * <li>STOR writes the upload into the file from start, in place, and changes nothing outside the range:
 * the file is never truncated, and more data than the range holds fails the transfer after the range is
 * written. A start past the end of the file is refused (554). Like REST then STOR at an offset, this is
 * only allowed with {@link us.bringardner.parley.ftp.server.FtpServer#setStoreAtOffset(boolean)} on;
 * otherwise STOR is refused (504) and nothing changes.</li>
 * </ul>
 * RANG and REST replace each other: the last one sent before the transfer is the one used. The range is
 * used by the next RETR or STOR only; APPE refuses it.
 *
 * @since 1.1.0
 */
public class Rang extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;

	/** An inclusive octet range, saved by RANG for the next transfer. */
	public static final class Range {
		private final long start;
		private final long end;

		public Range(long start, long end) {
			if( start < 0 || end < start ) {
				throw new IllegalArgumentException("Invalid range "+start+"-"+end);
			}
			this.start = start;
			this.end = end;
		}

		/** @return the first octet of the range */
		public long getStart() {
			return start;
		}

		/** @return the last octet of the range (inclusive) */
		public long getEnd() {
			return end;
		}

		/** @return the number of octets in the range */
		public long getLength() {
			// end - start can't overflow (0 <= start <= end), but adding 1 can
			long len = end - start;
			return len == Long.MAX_VALUE ? Long.MAX_VALUE : len + 1;
		}

		@Override
		public String toString() {
			return start+"-"+end;
		}
	}

	public Rang() {
		super(RANG);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String commandLine = context.getCommandLine();
		int idx = commandLine.indexOf(' ');
		String[] args = idx > 0 ? commandLine.substring(idx).trim().split(" +") : new String[0];

		long start;
		long end;
		if( args.length != 2 || !isDigits(args[0]) || !isDigits(args[1]) ) {
			processor.removeTempValue(RANG);
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: RANG <start-point> <end-point>");
			return;
		}
		try {
			start = Long.parseLong(args[0]);
			end = Long.parseLong(args[1]);
		} catch(NumberFormatException e) {
			// too big for a long
			processor.removeTempValue(RANG);
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Invalid range "+args[0]+" "+args[1]);
			return;
		}

		if( start == 1 && end == 0 ) {
			processor.removeTempValue(RANG);
			processor.reply(REPLY_350_FILE_ACTION_PENDING,"Range reset. The next transfer is the whole file.");
			return;
		}
		if( start > end ) {
			processor.removeTempValue(RANG);
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Invalid range: start-point "+start+" is after end-point "+end);
			return;
		}

		// the last of RANG and REST is the one the transfer uses
		processor.removeTempValue(REST);
		processor.setTempValue(RANG, new Range(start, end));
		processor.reply(REPLY_350_FILE_ACTION_PENDING,"Restarting at "+start+". End byte range at "+end+".");
	}

	private static boolean isDigits(String s) {
		if( s.isEmpty() ) {
			return false;
		}
		for(int i=0; i < s.length(); i++ ) {
			char c = s.charAt(i);
			if( c < '0' || c > '9' ) {
				return false;
			}
		}
		return true;
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return RANG+" STREAM";
	}

	@Override
	public IPermission getPermission() {
		// RETR needs read; STOR checks its own write permission
		return READ_PERMISSION;
	}
}
