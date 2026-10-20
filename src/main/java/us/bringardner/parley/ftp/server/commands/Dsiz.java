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
 * DSIZ [path] (draft-peterson-streamlined-ftp-command-extensions): the octets in the files of a directory
 * (the current one by default) and all below it: {@code 213 123456}. Links aren't followed (a client
 * doesn't see them either). 550 if it isn't a directory. Listed in FEAT.
 *
 * @since 1.1.0
 */
public class Dsiz extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;

	public Dsiz() {
		super(DSIZ);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine();
		int sp = line.indexOf(' ');
		String path = sp < 0 ? "" : line.substring(sp+1).trim();
		FileSource dir = path.isEmpty() ? processor.getCurrentDir() : processor.createNewFile(path);
		if( dir == null || !dir.exists() || !dir.isDirectory() ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,(path.isEmpty() ? "The current directory" : path)+" is not a directory");
			return;
		}
		processor.reply(REPLY_213_FILE_STATUS,""+size(processor, dir));
	}

	private static long size(FtpRequestProcessor processor, FileSource dir) throws IOException {
		long ret = 0;
		FileSource[] kids = dir.listFiles();
		if( kids == null ) {
			return 0;
		}
		for(FileSource k : kids) {
			if( processor.isLink(k) ) {
				continue;
			}
			if( k.isDirectory() ) {
				ret += size(processor, k);
			} else {
				ret += k.length();
			}
		}
		return ret;
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return DSIZ;
	}
}
