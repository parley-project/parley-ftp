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
 * ~version~V000.01.35-V000.01.05-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 14, 2004
 *
 */
package us.bringardner.parley.ftp.server.commands;

import java.io.IOException;

import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;

/**
 * @author Tony Bringardner
 *
 */
public class Stru  extends BaseCommand {

	private static final long serialVersionUID = 1L;

	/**
	 * 
	 */
	public Stru() {
		super(STRU);
	
	}

	/*
	 * RFC 959
	 * FILE STRUCTURE (STRU)

            The argument is a single Telnet character code specifying
            file structure described in the Section on Data
            Representation and Storage.

            The following codes are assigned for structure:

               F - File (no record structure)
               R - Record structure
               P - Page structure

            The default structure is File.
 
	 * @see us.bringardner.parley.ftp.server.FtpCommand#execute(us.bringardner.parley.ftp.server.FtpRequestProcessor, java.lang.String)
	 */
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String commandLine = context.getCommandLine();
		String [] args = commandLine.split(" ");
		if( args.length != 2 ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Not enough parameters");
			return;
		}
			
		//  Only accept 'F'
		if(args[1].equalsIgnoreCase("F")) {
			processor.reply(REPLY_200_OK,"OK");
		} else {
			// RFC 959: 504 for a parameter that isn't implemented (record and page structure)
			processor.reply(REPLY_504_NOT_IMP_FOR_PARAM, "STRU "+args[1]+" not implemented; only F (file)");
		}
	}

}
