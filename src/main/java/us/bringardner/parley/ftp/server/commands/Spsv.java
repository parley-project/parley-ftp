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
 * SPSV (draft-rosenau-ftp-single-port, an expired draft): passive mode through the control port only,
 * for clients and servers behind port forwarding. The reply has an identifier, not a port:
 * {@code 227 Entering single-port mode (id)}. The client opens a second connection to the control port,
 * sends {@code SPDT id} on it ({@link Spdt}), gets {@code 200 DATA}, and that connection carries the next
 * transfer. An identifier is good for one data connection. Listed in FEAT (the draft doesn't say).
 * <p>
 * The second connection is protected as the control port is: TLS with implicit TLS, clear otherwise. So
 * SPSV is refused (504) when the data protection wanted is different (PROT P after AUTH TLS, or PROT C
 * with implicit TLS); EPSV and PASV are the ways then.
 *
 * @since 1.1.0
 */
public class Spsv extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;

	public Spsv() {
		super(SPSV);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		if( processor.isDataChannelSecure() != processor.isSecure() ) {
			processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,"SPSV can't give the data connection the protection set (PROT "
					+processor.getProtLevel()+"); use EPSV");
			return;
		}
		String id = processor.openSinglePort();
		processor.reply(REPLY_227_ENTERING_PASSIVE_MODE,"Entering single-port mode ("+id+")");
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return SPSV;
	}
}
