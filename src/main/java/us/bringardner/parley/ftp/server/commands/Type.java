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
 * ~version~V000.01.35-V000.01.25-V000.01.05-V000.00.01-V000.00.00-
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
public class Type extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	/**
	 * 
	 */
	public Type() {
		super(TYPE);
	
	}

	/* 
	 * RFC 959
	 * 
	 * REPRESENTATION TYPE (TYPE)

            The argument specifies the representation type as described
            in the Section on Data Representation and Storage.  Several
            types take a second parameter.  The first parameter is
            denoted by a single Telnet character, as is the second
            Format parameter for ASCII and EBCDIC; the second parameter
            for local byte is a decimal integer to indicate Bytesize.
            The parameters are separated by a <SP> (Space, ASCII code
            32).

            The following codes are assigned for type:

                         \    /
               A - ASCII |    | N - Non-print
                         |-><-| T - Telnet format effectors
               E - EBCDIC|    | C - Carriage Control (ASA)
                         /    \
               I - Image
               
               L <byte size> - Local byte Byte size

            The default representation type is ASCII Non-print.  If the
            Format parameter is changed, and later just the first
            argument is changed, Format then returns to the Non-print
            default.

	 * 
	 * @see us.bringardner.parley.ftp.server.FtpCommand#execute(us.bringardner.parley.ftp.server.FtpRequestProcessor, java.lang.String)
	 */
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String commandLine = context.getCommandLine().trim();
		String [] args = commandLine.split(" +");
		if( args.length < 2 || args.length > 3 || args[1].length() != 1 ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: TYPE A [N] | I | L 8 | U");
			return;
		}
		String second = args.length == 3 ? args[2].toUpperCase(java.util.Locale.ROOT) : null;
		int type = -1;
		switch(args[1].toUpperCase(java.util.Locale.ROOT).charAt(0)){
			case 'I':
				type = second == null ? FtpRequestProcessor.TYPE_IMAGE : -1;
				break;
			case 'A':
				// RFC 959: the format is N (non-print, the default); T (Telnet) and C (ASA) aren't implemented
				type = second == null || second.equals("N") ? FtpRequestProcessor.TYPE_ASCII : -1;
				break;
			case 'L':
				if( second == null ) {
					processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: TYPE L <byte size>");
					return;
				}
				// RFC 959: local byte size 8 is the same as image on these 8-bit systems
				type = "8".equals(second) ? FtpRequestProcessor.TYPE_IMAGE : -1;
				break;
			case 'U':
				// draft-klensin-ftp-typeu: UTF-8 text with CRLF line ends. Text is sent as stored, so it
				// is the same as A here
				type = second == null ? FtpRequestProcessor.TYPE_ASCII : -1;
				break;
			case 'E':
			default:
				break;
		}
		if( type >= 0 ){
			processor.setRepresentationType(type);
			processor.reply(REPLY_200_OK,"type " + commandLine.substring(5).trim());
		} else {
			// RFC 959: 504 for a type that isn't implemented (E, L other than 8, A T, A C)
			processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,"type " + commandLine.substring(5).trim() + " not implemented.");
		}
	}


}
