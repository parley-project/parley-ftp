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

import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * ALLOCATE (ALLO), RFC 959:
 * <pre>
 *   ALLO &lt;SP&gt; &lt;decimal-integer&gt; [&lt;SP&gt; R &lt;SP&gt; &lt;decimal-integer&gt;] &lt;CRLF&gt;
 * </pre>
 * Reserves space for a file before it is stored. No file system here needs that (space is taken as the
 * data arrives), so, as RFC 959 says such a server should, it is answered with 202 and otherwise does
 * nothing. A badly formed argument gets 501.
 *
 * @since 1.1.0
 */
public class Allo extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Allo() {
		super(ALLO);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine().trim();
		int sp = line.indexOf(' ');
		String args = sp < 0 ? "" : line.substring(sp+1).trim();
		if( !args.matches("\\d{1,18}( +[Rr] +\\d{1,18})?") ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: ALLO <size> [R <record size>]");
			return;
		}
		processor.reply(REPLY_202_NOT_IMPLEMENTED,"ALLO not needed: space is allocated as the data arrives");
	}

	@Override
	public IPermission getPermission() {
		// it does nothing, so anyone logged in may send it
		return READ_PERMISSION;
	}
}
