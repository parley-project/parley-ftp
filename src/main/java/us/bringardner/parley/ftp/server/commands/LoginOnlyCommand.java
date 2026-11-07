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
 */
package us.bringardner.parley.ftp.server.commands;

import java.io.IOException;

import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.ICommandProcessor;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * A command that needs a login but no permission: it only sets up what the next command does
 * (PORT, PASV, MODE, REST and the like), and that command checks read or write. Without a login it
 * is answered "530 COMMAND refused: nobody is logged in", the same as a command that needs a
 * permission.
 *
 * @since 2.0.0
 */
public abstract class LoginOnlyCommand extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public LoginOnlyCommand(String command) {
		super(command);
	}

	/** The dispatcher checks no permission; {@link #execute(ICommandProcessor, IRequestContext)} checks the login. */
	@Override
	public boolean requiresAuthorization() {
		return false;
	}

	@Override
	public void execute(ICommandProcessor processor, IRequestContext context) throws IOException {
		FtpRequestProcessor ftp = (FtpRequestProcessor) processor;
		if( ftp.getPrincipal() == null ) {
			ftp.refuseNotLoggedIn(getName());
		} else {
			execute(ftp, context);
		}
	}
}
