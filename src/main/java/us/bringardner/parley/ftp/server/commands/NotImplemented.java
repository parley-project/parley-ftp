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
import us.bringardner.parley.net.server.IRequestContext;

/**
 * A command this server knows but doesn't implement: 502 (RFC 959), rather than the 500 an unknown
 * command gets. Used for SMNT (there is nothing to mount on these file systems).
 *
 * @since 1.1.0
 */
public class NotImplemented extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;
	private final String why;

	public NotImplemented(String name, String why) {
		super(name);
		this.why = why;
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		processor.reply(REPLY_502_NOT_IMPLEMENTED,getName()+" is not implemented"+(why == null ? "" : ": "+why));
	}
}
