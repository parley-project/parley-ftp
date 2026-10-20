// ~version~V000.01.42-V000.01.35-V000.01.20-V000.01.11-V000.01.05-V000.00.02-V000.00.00-
/**
 FtpCommandFactory.java

 Copyright 1998-2009 Tony Bringardner

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.   

*/


package us.bringardner.parley.ftp.server;

import java.util.Map;

import us.bringardner.parley.net.server.AbstractCommandFactory;
import us.bringardner.parley.net.server.ICommand;
import us.bringardner.parley.ftp.server.commands.Abor;
import us.bringardner.parley.ftp.server.commands.Allo;
import us.bringardner.parley.ftp.server.commands.Rein;
import us.bringardner.parley.ftp.server.commands.Stat;
import us.bringardner.parley.ftp.server.commands.Acct;
import us.bringardner.parley.ftp.server.commands.Appe;
import us.bringardner.parley.ftp.server.commands.AuthError;
import us.bringardner.parley.ftp.server.commands.Cdup;
import us.bringardner.parley.ftp.server.commands.Cwd;
import us.bringardner.parley.ftp.server.commands.Debug;
import us.bringardner.parley.ftp.server.commands.Dele;
import us.bringardner.parley.ftp.server.commands.Feat;
import us.bringardner.parley.ftp.server.commands.Help;
import us.bringardner.parley.ftp.server.commands.Host;
import us.bringardner.parley.ftp.server.commands.List;
import us.bringardner.parley.ftp.server.commands.Mdtm;
import us.bringardner.parley.ftp.server.commands.Mkd;
import us.bringardner.parley.ftp.server.commands.Mlsd;
import us.bringardner.parley.ftp.server.commands.Mlst;
import us.bringardner.parley.ftp.server.commands.Mode;
import us.bringardner.parley.ftp.server.commands.Nlst;
import us.bringardner.parley.ftp.server.commands.Noop;
import us.bringardner.parley.ftp.server.commands.NotAuthError;
import us.bringardner.parley.ftp.server.commands.Opts;
import us.bringardner.parley.ftp.server.commands.Pass;
import us.bringardner.parley.ftp.server.commands.Pasv;
import us.bringardner.parley.ftp.server.commands.Port;
import us.bringardner.parley.ftp.server.commands.Pwd;
import us.bringardner.parley.ftp.server.commands.Quit;
import us.bringardner.parley.ftp.server.commands.Rang;
import us.bringardner.parley.ftp.server.commands.Rest;
import us.bringardner.parley.ftp.server.commands.Retr;
import us.bringardner.parley.ftp.server.commands.Rmd;
import us.bringardner.parley.ftp.server.commands.Rnfr;
import us.bringardner.parley.ftp.server.commands.Rnto;
import us.bringardner.parley.ftp.server.commands.Site;
import us.bringardner.parley.ftp.server.commands.Size;
import us.bringardner.parley.ftp.server.commands.Stor;
import us.bringardner.parley.ftp.server.commands.Stru;
import us.bringardner.parley.ftp.server.commands.Syst;
import us.bringardner.parley.ftp.server.commands.Tvfs;
import us.bringardner.parley.ftp.server.commands.Type;
import us.bringardner.parley.ftp.server.commands.User;
import us.bringardner.parley.ftp.server.commands.rfc2228.Adat;
import us.bringardner.parley.ftp.server.commands.rfc2228.Auth;
import us.bringardner.parley.ftp.server.commands.rfc2228.Ccc;
import us.bringardner.parley.ftp.server.commands.rfc2228.Conf;
import us.bringardner.parley.ftp.server.commands.rfc2228.Enc;
import us.bringardner.parley.ftp.server.commands.rfc2228.Mic;
import us.bringardner.parley.ftp.server.commands.rfc2228.Pbsz;
import us.bringardner.parley.ftp.server.commands.rfc2228.Prot;
import us.bringardner.parley.ftp.server.commands.rfc2428.Epsv;
import us.bringardner.parley.ftp.server.commands.rfc2428.Eprt;

/**
 * @author Tony Bringardner
 *
 */
public class FtpCommandFactory extends AbstractCommandFactory {

	private static final long serialVersionUID = 1L;
	
	private static final Map<String, ICommand> commands = newRegistry();
	
	static {
		//  Create the command processors		
		//	  These commands are available after authentication
		addCommand(new AuthError());
		addCommand(new NotAuthError());
		addCommand(new Abor());
		addCommand(new Appe());
		addCommand(new Cdup());
		addCommand(new Cwd());
		addCommand(new Dele());
		addCommand(new Debug());
		addCommand(new Help());
		addCommand(new Mode());
		addCommand(new Mkd());
		addCommand(new List());
		addCommand(new Nlst());
		addCommand(new Noop());
		addCommand(new Pass());
		addCommand(new Pasv());
		addCommand(new Port());
		addCommand(new Pwd());
		addCommand(new Quit());
		addCommand(new Retr());
		addCommand(new Rmd());
		addCommand(new Rnfr());
		addCommand(new Rnto());
		addCommand(new Site());
		addCommand(new Stor());
		addCommand(new Stru());
		addCommand(new Syst());
		addCommand(new Type());
		addCommand(new User());
		addCommand(new Acct());
		// RFC 959
		addCommand(new Allo());
		addCommand(new Rein());
		addCommand(new Stat());
		
		
		Feat feat = new Feat();
		addCommand(feat);
		/*
         * These commands are defined in a draft document at
         * http://www.ietf.org/internet-drafts/draft-ietf-ftpext-mlst-16.txt 
		 */
		feat.addSupportedCommand(new Rest());
		feat.addSupportedCommand(new Size());
		feat.addSupportedCommand(new Tvfs());
		feat.addSupportedCommand(new Mlst());
		feat.addSupportedCommand(new Mlsd());
		feat.addSupportedCommand(new Mdtm());
		// RFC 2640 UTF8 (OPTS UTF8 ON), and OPTS MLST (BJL-50)
		feat.addSupportedCommand(new Opts());
		// draft-bryan-ftp-range (RANG STREAM): an octet range for RETR and STOR
		feat.addSupportedCommand(new Rang());
		// RFC 7151 virtual hosts
		feat.addSupportedCommand(new Host());
		
		
		//  Security (RFC 2228)
		//  Should these be in FEAT???
		addCommand(new Adat());
		addCommand(new Auth());
		addCommand(new Ccc());
		addCommand(new Conf());
		addCommand(new Enc());
		addCommand(new Mic());
		addCommand(new Pbsz());
		addCommand(new Prot());
		
		//  (RFC 2428) Extended address (IPV6)
		addCommand(new Eprt());
		addCommand(new Epsv());
		
	}
	
	public FtpCommandFactory() {
		super(commands);
	}

	public static void addCommand(ICommand cmd){
		register(commands, cmd);
	}
}
