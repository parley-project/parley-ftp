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
import java.net.Socket;

import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * SPDT id (draft-rosenau-ftp-single-port): sent on a second connection to the control port after SPSV on
 * the first; {@code 200 DATA}, and this connection is that session's next data connection. An identifier
 * that isn't waiting gets 504 and the connection is closed. No login is needed (the identifier is a
 * random one-time token from a session that has logged in).
 *
 * @since 1.1.0
 */
public class Spdt extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;

	public Spdt() {
		super(SPDT);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine().trim();
		int sp = line.indexOf(' ');
		String id = sp < 0 ? null : line.substring(sp+1).trim();
		FtpRequestProcessor.SinglePort wait = ((FtpServer) processor.getServer()).takeSinglePort(id);
		Socket sock = processor.getConnection().getSocket();
		if( wait == null ) {
			processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,"Unknown SPDT identifier");
			processor.stop();
			return;
		}
		if( !wait.isAllowedPeer(sock.getInetAddress()) ) {
			// as PASV: only the client's own address, so an identifier seen on the wire can't be used by another
			wait.cancel();
			processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,"Unknown SPDT identifier");
			processor.stop();
			return;
		}
		processor.reply(REPLY_200_OK,"DATA");
		if( !wait.deliver(sock) ) {
			// the session that asked no longer wants it
			processor.stop();
			return;
		}
		// the other session's transfer has the socket now: read nothing more from it, and end when it is closed
		while( !sock.isClosed() ) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		processor.stop();
	}
}
