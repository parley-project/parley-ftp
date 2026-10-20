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
 * AVBL [path] (draft-peterson-streamlined-ftp-command-extensions): the octets that can be uploaded to a
 * directory (the current one by default), as the file system reports its usable space: {@code 213 123456}.
 * 550 if it isn't a directory, or the file system can't tell. Listed in FEAT.
 *
 * @since 1.1.0
 */
public class Avbl extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;

	public Avbl() {
		super(AVBL);
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
		long space;
		try {
			space = dir.getUsableSpace();
		} catch (IOException | RuntimeException e) {
			space = 0;
		}
		if( space <= 0 ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"The space available isn't known here");
			return;
		}
		processor.reply(REPLY_213_FILE_STATUS,""+space);
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return AVBL;
	}
}
