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

import us.bringardner.parley.ftp.server.FeatCommand;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * CLNT name (not in any RFC or draft; FileZilla, Serv-U and others have it): the client says what it is.
 * Kept for STAT (as CSID's is) and the log; 200. Allowed before logging in. Listed in FEAT.
 *
 * @since 1.1.0
 */
public class Clnt extends NoAuthReqBaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;

	public Clnt() {
		super(CLNT);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine();
		int sp = line.indexOf(' ');
		String name = sp < 0 ? "" : line.substring(sp+1).trim();
		if( name.isEmpty() ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: CLNT name");
			return;
		}
		if( name.length() > 200 ) {
			name = name.substring(0, 200);
		}
		processor.setTempValue(Csid.CLIENT, name);
		processor.logDebug("Client: "+name);
		processor.reply(REPLY_200_OK,"Noted");
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return CLNT;
	}
}
