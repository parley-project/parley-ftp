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
 * XCRC, XMD5, XSHA1, XSHA256 and XSHA512: checksums as other servers (ProFTPD's mod_digest, Serv-U,
 * GlobalSCAPE) and clients (FileZilla, WinSCP) have them; not in any RFC or draft.
 * <pre>
 *   XMD5 path [start [end]]
 *   250 9e107d9d372bb6826bd81d3542a419d6
 * </pre>
 * start and end are octet positions, end not included (start 0 and end 10 is the first ten octets); end
 * defaults to the end of the file. The path may be in double quotes; without them, a path that names a
 * file as it stands is taken whole, even if it ends in numbers. Listed in FEAT.
 *
 * @since 1.1.0
 */
public class XHash extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;
	private final String algorithm;

	public XHash(String name, String algorithm) {
		super(name);
		this.algorithm = algorithm;
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine();
		int sp = line.indexOf(' ');
		String arg = sp < 0 ? "" : line.substring(sp+1).trim();
		if( arg.isEmpty() ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: "+getName()+" path [start [end]]");
			return;
		}
		if( processor.transferInProcess.isTransferInProgress() ) {
			processor.reply(REPLY_450_FILE_ACTION_FAILED,"A transfer is in progress");
			return;
		}
		String path;
		String rest;
		if( arg.startsWith("\"") && arg.indexOf('"', 1) > 0 ) {
			int q = arg.indexOf('"', 1);
			path = arg.substring(1, q);
			rest = arg.substring(q+1).trim();
		} else {
			path = arg;
			rest = "";
			FileSource whole = processor.createNewFile(arg);
			if( whole == null || !whole.exists() ) {
				// "path start [end]": take numbers off the end
				String[] t = arg.split(" +");
				int numbers = 0;
				for(int i=t.length-1; i > 0 && numbers < 2 && t[i].matches("\\d+"); i-- ) {
					numbers++;
				}
				if( numbers > 0 ) {
					int at = arg.length();
					for(int i=0; i < numbers; i++ ) {
						at = arg.lastIndexOf(' ', at-1);
					}
					path = arg.substring(0, at).trim();
					rest = arg.substring(at).trim();
				}
			}
		}
		long start = 0;
		long end = -1;
		if( !rest.isEmpty() ) {
			String[] n = rest.split(" +");
			try {
				start = Long.parseLong(n[0]);
				if( n.length > 1 ) {
					end = Long.parseLong(n[1]);
				}
				if( n.length > 2 || start < 0 || (end >= 0 && end < start) ) {
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: "+getName()+" path [start [end]]");
				return;
			}
		}
		FileSource f = processor.createNewFile(path);
		if( f == null || !f.exists() || !f.canRead() ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"No such file: "+path);
			return;
		}
		if( f.isDirectory() ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,path+" is a directory");
			return;
		}
		long len = f.length();
		if( end < 0 || end > len ) {
			end = len;
		}
		if( start > len ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Start "+start+" is past the end of "+path+" ("+len+" bytes)");
			return;
		}
		try {
			String hex = Digests.compute(f, algorithm, start, end - start, null);
			processor.reply(REPLY_250_FILE_ACTION_OK,hex);
		} catch (IOException e) {
			processor.logError(getName()+" "+path, e);
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Can't read "+path);
		}
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return getName();
	}
}
