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
import java.util.concurrent.atomic.AtomicBoolean;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.ftp.server.FtpServerStream;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * STORE UNIQUE (STOU), RFC 959 and RFC 1123 section 4.1.2.9: store the upload under a name that isn't
 * used yet in the current directory, and say what it is in the 150 reply: {@code 150 FILE: name}.
 * <p>
 * With no argument the name is {@code ftp-<time>}; with one (some clients send a name to start from) it is
 * that name if it isn't used, else {@code name.1}, {@code name.2} and so on. The name is made (empty)
 * before the transfer starts, so two uploads can't get the same one, and removed again if the upload
 * fails. The upload is put in place as STOR does.
 *
 * @since 1.1.0
 */
public class Stou extends BaseCommand {

	private static final long serialVersionUID = 1L;
	private static final int MAX_TRIES = 1000;

	public Stou() {
		super(STOU);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine();
		int sp = line.indexOf(' ');
		String base = sp < 0 ? "" : line.substring(sp+1).trim();
		if( base.contains("/") || base.contains("\\") || base.equals(".") || base.equals("..") ) {
			processor.reply(REPLY_553_FILE_NAME_NOT_ALLOWED,"STOU takes a file name, not a path");
			return;
		}
		if( base.isEmpty() ) {
			base = "ftp-"+Long.toString(System.currentTimeMillis(), 36);
		}
		FileSource dir = processor.getCurrentDir();
		FileSource target = null;
		for(int i=0; i < MAX_TRIES && target == null; i++ ) {
			String name = i == 0 ? base : base+"."+i;
			FileSource f = processor.createNewFile(name);
			if( f == null ) {
				processor.reply(REPLY_553_FILE_NAME_NOT_ALLOWED,"File name not allowed: "+name);
				return;
			}
			try {
				if( !f.exists() && f.createNewFile() ) {
					target = f;
				}
			} catch (IOException e) {
				processor.logError("STOU can't make "+name+" in "+dir, e);
				processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Permission denied.");
				return;
			}
		}
		if( target == null ) {
			processor.reply(REPLY_450_FILE_ACTION_FAILED,"No unique name for "+base);
			return;
		}

		final FileSource made = target;
		final AtomicBoolean over = new AtomicBoolean();
		FtpServerStream.CompletionHandler cleanUp = new CleanUp(processor, made, over);
		processor.receiveFile(made, false, processor.getUploadMode() == us.bringardner.parley.ftp.server.FtpServer.UploadMode.IN_PLACE,
				"FILE: "+made.getName(), cleanUp);
		if( !over.get() && !processor.transferInProcess.isTransferInProgress() ) {
			// no transfer was started (no data connection): the name isn't needed
			removeEmpty(processor, made);
		}
	}

	/** After the transfer: the name made for it is removed if the upload failed */
	private static final class CleanUp implements FtpServerStream.CompletionHandler {
		private final FtpRequestProcessor processor;
		private final FileSource made;
		private final AtomicBoolean over;

		CleanUp(FtpRequestProcessor processor, FileSource made, AtomicBoolean over) {
			this.processor = processor;
			this.made = made;
			this.over = over;
		}

		@Override
		public void transferComplete(boolean success) throws IOException {
			over.set(true);
			if( !success ) {
				removeEmpty(processor, made);
			}
		}
	}

	private static void removeEmpty(FtpRequestProcessor processor, FileSource f) {
		try {
			if( f.exists() && f.length() == 0 && !f.delete() ) {
				processor.logError("Can't remove "+f+" after a STOU that failed");
			}
		} catch (IOException e) {
			processor.logError("Can't remove "+f+" after a STOU that failed", e);
		}
	}

	@Override
	public IPermission getPermission() {
		return WRITE_PERMISSION;
	}
}
