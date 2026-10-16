/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
 *
 *
 * ~version~V000.01.54-V000.01.37-V000.01.35-V000.01.09-V000.01.07-V000.01.05-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 14, 2004
 *
 */
package us.bringardner.parley.ftp.server.commands;

import java.io.IOException;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;

/**
 * @author Tony Bringardner
 *
 */
public class Appe  extends BaseCommand {

	private static final long serialVersionUID = 1L;

	/**
	 * 
	 */
	public Appe() {
		super(APPE);	
	}

	/*
	 * RFC 959
	 * 
	 * APPEND (with create) (APPE)

            This command causes the server-DTP to accept the data
            transferred via the data connection and to store the data in
            a file at the server site.  If the file specified in the
            pathname exists at the server site, then the data shall be
            appended to that file; otherwise the file specified in the
            pathname shall be created at the server site.


	Implementation is the same as STOR except the output stream is 
	set for append.
	
	 * @see us.bringardner.parley.ftp.server.FtpCommand#execute(us.bringardner.parley.ftp.server.FtpRequestProcessor, java.lang.String)
	 */
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String commandLine = pathArgument(processor, context);
		if( commandLine == null ) {
			return;
		}
			
		FileSource target = processor.createNewFile(commandLine);
		
		if( target == null || target.isDirectory()){
			processor.reply(REPLY_450_FILE_ACTION_FAILED," Invalid or non existant name");
			return;
		} 

		if( target.exists() && !target.canWrite() ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Permission denied.");
			return;
		}
		if( processor.isLink(target) ) {
			// an append to a link goes on a regular copy that takes its place: what it led to is not changed
			try {
				target = processor.replaceLinkWithCopy(target);
			} catch (IOException e) {
				processor.logError("Can't replace link "+target, e);
				processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Permission denied.");
				return;
			}
		}

		// Get the data connection before opening the file
		processor.receiveFile(target, true);

	}

	@Override
	public IPermission getPermission() {
		return WRITE_PERMISSION;
	}

}
