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
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.ftp.server.FeatCommand;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.ftp.server.FtpServer;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * HOST (RFC 7151): the client names the host it wants, before it logs in, so one server can serve several
 * virtual hosts (see {@link FtpServer#addVirtualHost(String, FileSource)}). Listed in FEAT as "HOST".
 * <pre>
 *   host-command = "HOST" SP hostname CRLF
 * </pre>
 * <ul>
 * <li>220: accepted. The session gets the host's root (the server's root for a name that isn't a
 * virtual host, when the server has none, or for this server's own IP address).</li>
 * <li>501: not a host name, or it has a port.</li>
 * <li>503: sent after logging in (send REIN first).</li>
 * <li>504: a name that isn't one of the server's virtual hosts, an IP address that isn't this server's,
 * or a name that doesn't match the one the client asked for in TLS (SNI). The session keeps the host
 * it had.</li>
 * </ul>
 * A later HOST replaces an earlier one, and forgets a USER, PASS or ACCT already sent; REIN forgets
 * the host. HOST sent before AUTH TLS is checked against the TLS server name once TLS is up: a mismatch
 * gets 421 and the connection is closed.
 *
 * @since 1.1.0
 */
public class Host extends NoAuthReqBaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;
	/** What {@link #normalize(String)} returns for a host name with a port */
	public static final String HAS_PORT = ":port";

	public Host() {
		super(HOST);
	}

	/**
	 * @return the host name or IP literal in lower case (a trailing '.' dropped, IPv6 in brackets),
	 * {@link #HAS_PORT} if it has a port, or null if it isn't a host name (RFC 7151 section 3.1)
	 */
	public static String normalize(String name) {
		if( name == null ) {
			return null;
		}
		String n = name.trim();
		if( n.isEmpty() || n.length() > 255 ) {
			return null;
		}
		if( n.startsWith("[") ) {
			int end = n.indexOf(']');
			if( end < 0 ) {
				return null;
			}
			if( end != n.length()-1 ) {
				return n.charAt(end+1) == ':' ? HAS_PORT : null;
			}
			String v6 = n.substring(1, end);
			if( !v6.contains(":") || !v6.matches("[0-9A-Fa-f:.]+") ) {
				return null;
			}
			return "["+v6.toLowerCase(Locale.ROOT)+"]";
		}
		if( n.contains(":") ) {
			// a port, or an IPv6 address without the brackets RFC 7151 requires
			return n.indexOf(':') == n.lastIndexOf(':') ? HAS_PORT : null;
		}
		if( n.endsWith(".") ) {
			n = n.substring(0, n.length()-1);
		}
		if( n.matches("\\d{1,3}(\\.\\d{1,3}){3}") ) {
			for(String part : n.split("\\.")) {
				if( Integer.parseInt(part) > 255 ) {
					return null;
				}
			}
			return n;
		}
		if( n.length() > 253 ) {
			return null;
		}
		for(String label : n.split("\\.", -1)) {
			if( !label.matches("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?") ) {
				return null;
			}
		}
		return n.toLowerCase(Locale.ROOT);
	}

	private static boolean isLiteral(String name) {
		return name.startsWith("[") || name.matches("[0-9.]+");
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		if( processor.getPrincipal() != null ) {
			processor.reply(REPLY_503_BAD_SEQ_OF_COMMANDS,"HOST must come before logging in (send REIN first)");
			return;
		}
		String line = context.getCommandLine().trim();
		int sp = line.indexOf(' ');
		String arg = sp < 0 ? "" : line.substring(sp+1).trim();
		String name = arg.contains(" ") ? null : normalize(arg);
		if( HAS_PORT.equals(name) ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"HOST takes a host name without a port");
			return;
		}
		if( name == null ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: HOST <host name or IP address>");
			return;
		}
		boolean literal = isLiteral(name);
		if( literal ) {
			// an IP literal must be this server's address (RFC 7151 section 3.1); no name lookup
			InetAddress addr = InetAddress.getByName(name.startsWith("[") ? name.substring(1, name.length()-1) : name);
			if( !processor.isServerAddress(addr) ) {
				processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,arg+" isn't this server's address");
				return;
			}
		}
		List<String> sni = processor.getTlsServerNames();
		if( !sni.isEmpty() && !sni.contains(name) ) {
			// RFC 7151 section 4
			processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,arg+" doesn't match the TLS server name "+sni.get(0));
			return;
		}
		FtpServer server = (FtpServer) processor.getServer();
		FileSource root = server.getVirtualHostRoot(name);
		if( root == null ) {
			if( !literal && !server.getVirtualHosts().isEmpty() ) {
				processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,"Unknown host "+arg);
				return;
			}
			root = server.getFtpRoot();
		}
		processor.selectVirtualHost(name, root);
		processor.reply(REPLY_220_READY_NEW_USER,name+" ready");
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return HOST;
	}
}
