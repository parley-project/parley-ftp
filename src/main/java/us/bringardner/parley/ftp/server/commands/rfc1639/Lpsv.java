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
package us.bringardner.parley.ftp.server.commands.rfc1639;

import java.io.IOException;

import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.ftp.server.PassiveSocket;
import us.bringardner.parley.ftp.server.commands.BaseCommand;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * LONG PASSIVE (LPSV), RFC 1639 (historic; EPSV replaced it): a passive data connection, the address
 * given in long form, IPv4 or IPv6: {@code 228 Entering Long Passive Mode (af,hal,h1,...,hn,pal,p1,p2)}.
 *
 * @since 1.1.0
 */
public class Lpsv extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Lpsv() {
		super(LPSV);
	}

	/** @return the address and port in LPRT/LPSV form */
	public static String format(java.net.InetAddress addr, int port) {
		byte[] b = addr.getAddress();
		StringBuilder ret = new StringBuilder();
		ret.append(b.length == 4 ? 4 : 6).append(',').append(b.length);
		for(byte v : b) {
			ret.append(',').append(v & 0xff);
		}
		return ret.append(",2,").append(port / 256).append(',').append(port % 256).toString();
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		processor.setPasvSocket(null);
		PassiveSocket pasvSocket;
		try {
			pasvSocket = PassiveSocket.open(processor);
		} catch (IOException e) {
			processor.logError("Can't open passive data port", e);
			processor.reply(REPLY_425_CANT_OPEN_DATA_CON,"Can't open passive connection");
			return;
		}
		processor.setPasvSocket(pasvSocket);
		processor.reply(REPLY_228_ENTERING_LONG_PASSIVE_MODE,"Entering Long Passive Mode ("+format(pasvSocket.getAdvertisedAddress(), pasvSocket.getPort())+")");
	}
}
