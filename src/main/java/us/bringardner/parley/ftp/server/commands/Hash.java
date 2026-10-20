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

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.ftp.server.FeatCommand;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * HASH (draft-ietf-ftpext2-hash, an expired draft): the hash of a file, or with RANG first of part of one,
 * as the octets RETR in TYPE I sends:
 * <pre>
 *   HASH path
 *   213 SHA-256 0-1023 9f86d0...  path
 * </pre>
 * The algorithm is SHA-256 until {@code OPTS HASH name} chooses another; FEAT lists them, the one in use
 * marked with '*': {@code HASH SHA-256*;SHA-512;SHA-1;MD5;CRC32}. A long hash sends a {@code 213-} line
 * every few seconds so the client knows the server is still working.
 * <ul>
 * <li>550: no such file, or it can't be read. 553: a directory.</li>
 * <li>501: a RANG that starts past the end of the file.</li>
 * <li>450: a transfer is in progress on this connection (the reply would be mixed with the transfer's).</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class Hash extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;

	public Hash() {
		super(HASH);
	}

	/** @return the algorithm HASH uses on this session (OPTS HASH chooses it; REIN forgets it) */
	public static String current(FtpRequestProcessor processor) {
		Object ret = processor.getTempValue(HASH);
		return ret == null ? Digests.DEFAULT : ret.toString();
	}

	@Override
	public void execute(final FtpRequestProcessor processor, IRequestContext context) throws IOException {
		Rang.Range range = (Rang.Range) processor.removeTempValue(RANG);
		String path = pathArgument(processor, context);
		if( path == null ) {
			return;
		}
		path = path.trim();
		if( processor.transferInProcess.isTransferInProgress() ) {
			processor.reply(REPLY_450_FILE_ACTION_FAILED,"A transfer is in progress; HASH when it is over");
			return;
		}
		FileSource f = processor.createNewFile(path);
		if( f == null || !f.exists() || !f.canRead() ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"No such file: "+path);
			return;
		}
		if( f.isDirectory() ) {
			processor.reply(REPLY_553_FILE_NAME_NOT_ALLOWED,path+" is a directory");
			return;
		}
		long len = f.length();
		long start = 0;
		long end = len-1;
		if( range != null ) {
			start = range.getStart();
			end = Math.min(range.getEnd(), len-1);
			if( start > 0 && start >= len ) {
				processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"RANG starts at "+start+", past the end of "+path+" ("+len+" bytes)");
				return;
			}
		}
		String algorithm = current(processor);
		String hex;
		try {
			hex = Digests.compute(f, algorithm, start, Math.max(0, end - start + 1), new StillWorking(processor, algorithm));
		} catch (IOException e) {
			processor.logError("HASH "+path, e);
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Can't read "+path);
			return;
		}
		processor.reply(REPLY_213_FILE_STATUS,algorithm+" "+start+"-"+Math.max(end, start)+" "+hex+" "+path);
	}

	/** A "213-" line, so the client knows a long hash is still being computed */
	private static final class StillWorking implements Digests.KeepAlive {
		private final FtpRequestProcessor processor;
		private final String algorithm;

		StillWorking(FtpRequestProcessor processor, String algorithm) {
			this.processor = processor;
			this.algorithm = algorithm;
		}

		@Override
		public void stillWorking() throws IOException {
			processor.reply(REPLY_213_FILE_STATUS+"-Computing "+algorithm);
		}
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		String current = current(processor);
		StringBuilder ret = new StringBuilder(HASH).append(' ');
		for(int i=0; i < Digests.NAMES.size(); i++ ) {
			String a = Digests.NAMES.get(i);
			ret.append(i == 0 ? "" : ";").append(a).append(a.equals(current) ? "*" : "");
		}
		return ret.toString();
	}
}
