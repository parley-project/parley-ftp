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
 * ~version~V000.01.37-V000.01.35-V000.01.05-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 14, 2004
 *
 */
package us.bringardner.parley.ftp.server.commands;

import java.io.IOException;

import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;

/**
 * @author Tony Bringardner
 *
 */
public class Mode extends BaseCommand implements us.bringardner.parley.ftp.server.FeatCommand {

	private static final long serialVersionUID = 1L;

	/**
	 * 
	 */
	public Mode() {
		super(MODE);
	
	}

	/* 
	 * RFC959
	 *   TRANSFER MODE (MODE)

            The argument is a single Telnet character code specifying
            the data transfer modes described in the Section on
            Transmission Modes.

            The following codes are assigned for transfer modes:

               S - Stream
               B - Block
               C - Compressed

            The default transfer mode is Stream.
	 * 
	 * Only Stream mode supported
	 * @see us.bringardner.parley.ftp.server.FtpCommand#execute(us.bringardner.parley.ftp.server.FtpRequestProcessor, java.lang.String)
	 */
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String commandLine = context.getCommandLine().trim();
		String [] args = commandLine.split(" +");
		if( args.length != 2 || args[1].length() != 1) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: MODE S | Z");
			return;
		}
		switch(args[1].toUpperCase(java.util.Locale.ROOT).charAt(0)){
			case 'S':
				processor.setModeZ(false);
				processor.reply(REPLY_200_OK,"mode " + args[1]);
				break;
			case 'Z':
				// draft-preston-ftpext-deflate: stream mode, deflated; a REST or RANG set is dropped
				processor.removeTempValue(REST);
				processor.removeTempValue(RANG);
				processor.setModeZ(true);
				processor.reply(REPLY_200_OK,"mode Z (deflate)");
				break;
			default:
				// RFC 959: 504 for a mode that isn't implemented (B, C)
				processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,"mode " + args[1] + " not implemented.");
				break;
		}
	}

	/** MODE Z (draft-preston-ftpext-deflate) is listed in FEAT */
	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return MODE+" Z";
	}

	
	@Override
	public IPermission getPermission() {
		return READ_PERMISSION;
	}

}
