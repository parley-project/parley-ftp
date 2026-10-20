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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;

import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.ftp.server.commands.BaseCommand;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * LONG PORT (LPRT), RFC 1639 (historic; EPRT replaced it): an active data connection to an IPv4 or IPv6
 * address. {@code LPRT af,hal,h1,...,hn,pal,p1,p2} with af 4 (hal 4) or 6 (hal 16) and pal 2, every
 * number 0-255. The same checks as PORT and EPRT apply.
 *
 * @since 1.1.0
 */
public class Lprt extends BaseCommand {

	private static final long serialVersionUID = 1L;

	public Lprt() {
		super(LPRT);
	}

	/** @return the address and port, or null if the argument isn't a valid one */
	public static java.net.InetSocketAddress parse(String arg) {
		String[] parts = arg.trim().split(",");
		try {
			int[] n = new int[parts.length];
			for(int i=0; i < parts.length; i++ ) {
				n[i] = Integer.parseInt(parts[i].trim());
				if( n[i] < 0 || n[i] > 255 ) {
					return null;
				}
			}
			if( n.length < 2 ) {
				return null;
			}
			int af = n[0], hal = n[1];
			if( !((af == 4 && hal == 4) || (af == 6 && hal == 16)) || n.length != 2+hal+1+2 || n[2+hal] != 2 ) {
				return null;
			}
			byte[] b = new byte[hal];
			for(int i=0; i < hal; i++ ) {
				b[i] = (byte) n[2+i];
			}
			int port = n[2+hal+1]*256 + n[2+hal+2];
			return new java.net.InetSocketAddress(InetAddress.getByAddress(b), port);
		} catch (NumberFormatException | IOException e) {
			return null;
		}
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine().trim();
		int sp = line.indexOf(' ');
		java.net.InetSocketAddress target = sp < 0 ? null : parse(line.substring(sp+1));
		if( target == null ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: LPRT af,hal,h1,...,hn,pal,p1,p2");
			return;
		}
		InetAddress addr = target.getAddress();
		int port = target.getPort();
		if( port < 1 ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Invalid LPRT port");
			return;
		}
		String rejected = processor.checkActiveTarget(addr, port);
		if( rejected != null ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,rejected);
			return;
		}
		processor.setPasvSocket(null);
		try	{
			InetAddress mine = processor.getConnection().getSocket().getLocalAddress();
			if( (mine instanceof Inet4Address) != (addr instanceof Inet4Address) ) {
				mine = null;
			}
			Socket socket = processor.createSocket(addr, port, mine, processor.getConnectTimeout());
			processor.setDataSocket(socket);
			processor.reply(REPLY_200_OK,"LPRT command successful");
		} catch(IOException e) {
			processor.setDataSocket(null);
			processor.logDebug("LPRT can't connect to "+addr+":"+port, e);
			processor.reply(REPLY_425_CANT_OPEN_DATA_CON,"Can't open data connection to "+addr.getHostAddress()+":"+port);
		}
	}
}
