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
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * RMDA path (draft-peterson-streamlined-ftp-command-extensions): remove a directory and everything in it.
 * Everything that can be removed is; if anything can't (or the path is a file, or the root), the reply
 * is 550 and the directories above what was left stay. A link is removed, not followed. Needs WRITE, as
 * RMD and DELE do. Listed in FEAT.
 *
 * @since 1.1.0
 */
public class Rmda extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;

	public Rmda() {
		super(RMDA);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String path = pathArgument(processor, context);
		if( path == null ) {
			return;
		}
		path = path.trim();
		FileSource dir = processor.createNewFile(path);
		if( dir == null || !dir.exists() || !dir.isDirectory() || processor.isLink(dir) ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,path+" is not a directory");
			return;
		}
		if( processor.getVirtualPath(dir).equals("/") ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"The root can't be removed");
			return;
		}
		int[] left = new int[1];
		remove(processor, dir, left);
		if( left[0] > 0 ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,left[0]+" item(s) in "+path+" could not be removed");
		} else {
			processor.reply(REPLY_250_FILE_ACTION_OK,path+" removed");
		}
	}

	/** Removes what is in dir, then dir; counts what couldn't be removed */
	private static void remove(FtpRequestProcessor processor, FileSource dir, int[] left) {
		try {
			FileSource[] kids = dir.listFiles();
			if( kids != null ) {
				for(FileSource k : kids) {
					if( k.isDirectory() && !processor.isLink(k) ) {
						remove(processor, k, left);
					} else if( !k.delete() ) {
						left[0]++;
					}
				}
			}
			if( !dir.delete() ) {
				left[0]++;
			}
		} catch (IOException | RuntimeException e) {
			processor.logDebug("RMDA can't remove "+dir, e);
			left[0]++;
		}
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return RMDA;
	}

	@Override
	public IPermission getPermission() {
		return WRITE_PERMISSION;
	}
}
