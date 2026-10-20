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
import java.util.ArrayList;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * STATUS (STAT), RFC 959.
 * <ul>
 * <li>{@code STAT} during a transfer: 213 with the bytes transferred so far.</li>
 * <li>{@code STAT} otherwise: the session's status, a multi-line 211 (who is logged in, TYPE, STRU,
 * MODE, the data connection, TLS, the current directory).</li>
 * <li>{@code STAT path}: what LIST would send for it, on the control connection instead of a data
 * connection: 212 for a directory, 213 for a file. It needs a login, as LIST does.</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class Stat extends NoAuthReqBaseCommand {

	private static final long serialVersionUID = 1L;
	/** LIST's lines, so STAT path lists the same way. */
	private static final List LIST_FORMAT = new List();

	public Stat() {
		super(STAT);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		if( context.hasNext() ) {
			statPath(processor, context.getRemainingTokens());
		} else if( processor.transferInProcess.isTransferInProgress() ) {
			long n = processor.transferInProcess.getBytesTransferred();
			processor.reply(REPLY_213_FILE_STATUS,"Transfer in progress: "+Math.max(n, 0)+" bytes transferred");
		} else {
			statSession(processor);
		}
	}

	private void statPath(FtpRequestProcessor processor, String path) throws IOException {
		if( !processor.isAuthorized(READ_PERMISSION) ) {
			processor.reply(REPLY_530_USER_NOT_LOGGED_IN,"Log in with USER and PASS first");
			return;
		}
		FileSource f = processor.createNewFile(path);
		if( f == null || !f.exists() ) {
			processor.reply(REPLY_450_FILE_ACTION_FAILED," Invalid or non existant name");
			return;
		}
		boolean dir = f.isDirectory();
		FileSource[] list;
		if( dir ) {
			list = f.listFiles();
			if( list == null ) {
				list = new FileSource[0];
			}
		} else {
			list = new FileSource[] {f};
		}
		int code = dir ? REPLY_212_DIR_STATUS : REPLY_213_FILE_STATUS;
		java.util.List<String> lines = new ArrayList<String>();
		lines.add(code+"-Status of "+processor.getVirtualPath(f)+":");
		for(FileSource file : list) {
			try {
				// a line starts with the type and permissions (d, -), never with a reply code
				lines.add(LIST_FORMAT.formatFile(file));
			} catch (IOException | RuntimeException e) {
				processor.logError("Can't list "+file, e);
			}
		}
		lines.add(processor.translateResponseCode(code)+" End of status");
		processor.reply(lines);
	}

	private void statSession(FtpRequestProcessor processor) throws IOException {
		java.util.List<String> lines = new ArrayList<String>();
		lines.add(REPLY_211_SYSTEM_STATUS+"-"+processor.getServer().getName()+" status:");
		Socket sock = processor.getConnection() == null ? null : processor.getConnection().getSocket();
		if( sock != null ) {
			lines.add(" Connected to "+sock.getLocalAddress().getHostAddress()+" from "+sock.getInetAddress().getHostAddress());
		}
		IPrincipal user = processor.getPrincipal();
		lines.add(user == null ? " Not logged in" : " Logged in as "+user.getName());
		lines.add(" TYPE: "+(processor.isImageMode() ? "BINARY" : "ASCII")+", STRU: File, MODE: Stream");
		String data;
		if( processor.getPasvSocket() != null ) {
			data = "passive, waiting for the client";
		} else if( processor.hasDataSocket() ) {
			data = "active (PORT/EPRT)";
		} else {
			data = "none";
		}
		lines.add(" Data connection: "+data);
		lines.add(" Control connection: "+(processor.isControlChannelSecure() ? "TLS" : "clear")
				+"; data connections: "+(processor.isDataChannelSecure() ? "TLS" : "clear"));
		if( user != null ) {
			lines.add(" Current directory: "+processor.getVirtualPath(processor.getCurrentDir()));
		}
		lines.add(processor.translateResponseCode(REPLY_211_SYSTEM_STATUS)+" End of status");
		processor.reply(lines);
	}
}
