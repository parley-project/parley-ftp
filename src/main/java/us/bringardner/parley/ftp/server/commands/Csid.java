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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import us.bringardner.parley.ftp.server.FeatCommand;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * CSID name=value;... (draft-peterson-streamlined-ftp-command-extensions): the client says what it is
 * (Name, Version, Vendor; kept for STAT), the server replies the same of itself:
 * {@code 200 Name=parley-ftp; Version=1.1.0; Vendor=Bringardner; CaseSensitive=1; OS=Linux; OSVer=6.1;}.
 * Needs a login. Listed in FEAT.
 *
 * @since 1.1.0
 */
public class Csid extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;
	/** The temp value the client's identity is kept under (CSID and CLNT); REIN forgets it */
	public static final String CLIENT = "CLIENT-ID";

	public Csid() {
		super(CSID);
	}

	/** @return this server's version (from the jar), or "1.1.0" */
	public static String version() {
		String v = Csid.class.getPackage() == null ? null : Csid.class.getPackage().getImplementationVersion();
		return v == null ? "1.1.0" : v;
	}

	/** @return 1 if file names on this system tell case apart, as far as the OS name tells */
	static int caseSensitive() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return os.contains("win") || os.contains("mac") ? 0 : 1;
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine();
		int sp = line.indexOf(' ');
		String arg = sp < 0 ? "" : line.substring(sp+1);
		Map<String, String> facts = new LinkedHashMap<String, String>();
		for(String f : arg.split(";")) {
			int eq = f.indexOf('=');
			if( eq > 0 ) {
				facts.put(f.substring(0, eq).trim(), f.substring(eq+1).trim());
			}
		}
		String name = value(facts, "Name");
		String version = value(facts, "Version");
		String vendor = value(facts, "Vendor");
		String id = (name+" "+version).trim()+(vendor.isEmpty() ? "" : " ("+vendor+")");
		if( !id.isEmpty() ) {
			processor.setTempValue(CLIENT, id);
		}
		processor.reply(REPLY_200_OK,"Name=parley-ftp; Version="+version()+"; Vendor=Bringardner; CaseSensitive="+caseSensitive()
				+"; OS="+System.getProperty("os.name", "")+"; OSVer="+System.getProperty("os.version", "")+";");
	}

	private static String value(Map<String, String> facts, String name) {
		for(Map.Entry<String, String> e : facts.entrySet()) {
			if( e.getKey().equalsIgnoreCase(name) ) {
				return e.getValue();
			}
		}
		return "";
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return CSID;
	}
}
