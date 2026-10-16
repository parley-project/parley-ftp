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
 * ~version~V000.01.37-V000.01.35-V000.01.07-V000.01.05-V000.01.02-V000.00.01-V000.00.00-
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
public class Rmd extends BaseCommand {

	private static final long serialVersionUID = 1L;

	/**
	 * 
	 */
	public Rmd() {
		super(RMD);
	
	}

	/* 
	 * RFC 959
	 * REMOVE DIRECTORY (RMD)

            This command causes the directory specified in the pathname
            to be removed as a directory (if the pathname is absolute)
            or as a subdirectory of the current working directory (if
            the pathname is relative). 

	 * 
	 *   Remove the directory with the name "pathname".
	 * @see us.bringardner.parley.ftp.server.FtpCommand#execute(us.bringardner.parley.ftp.server.FtpRequestProcessor, java.lang.String)
	 */
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String commandLine = pathArgument(processor, context);
		if( commandLine == null ) {
			return;
		}
			
		FileSource target = processor.createNewFile(commandLine);
		if( target == null ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN," Invalid name");
			return;
		}
		commandLine = processor.getVirtualPath(target);
		
		if( !target.isDirectory()){
			processor.reply(REPLY_450_FILE_ACTION_FAILED," Invalid or non existant name");
			return;
		} 

		if(target.equals(processor.getFtpRoot())) {
			processor.reply(REPLY_450_FILE_ACTION_FAILED," Invalid or non existant name");
			return;
		}
		
		
		if( target.delete() ) {
			if( target.equals(processor.getCurrentDir())){
				processor.setCurrentDir(target.getParentFile());
			}
			processor.reply(REPLY_250_FILE_ACTION_OK,commandLine+" has been deleted");
		} else {
			processor.reply(REPLY_450_FILE_ACTION_FAILED," Unable to dlete "+commandLine);
		}
	}

	@Override
	public IPermission getPermission() {
		return WRITE_PERMISSION;
	}

}
