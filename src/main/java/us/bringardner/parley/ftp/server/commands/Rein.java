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
 * REINITIALIZE (REIN), RFC 959: ends the login and puts the session back as it was when the control
 * connection was opened, without closing it; a USER command may follow. A transfer in progress is
 * allowed to complete first. The reply is 220.
 * <p>
 * After AUTH TLS, the reply is sent protected and then the control connection goes back to clear text
 * (RFC 4217 section 13); the client sends AUTH again to protect it. A connection that was TLS from the
 * start (implicit TLS) stays TLS.
 *
 * @see FtpRequestProcessor#reinitialize()
 * @since 1.1.0
 */
public class Rein extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public Rein() {
		super(REIN);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		processor.reinitialize();
	}
}
