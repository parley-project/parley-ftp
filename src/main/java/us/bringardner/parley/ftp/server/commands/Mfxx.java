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
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceGroup;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.ftp.server.FeatCommand;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * MFMT, MFCT and MFF (draft-somers-ftp-mfxx, an expired draft that FileZilla, ProFTPD and others have):
 * change a file's modification time, creation time, or facts. Times are RFC 3659 time-vals in UTC
 * (YYYYMMDDHHMMSS, optionally with a fraction of a second).
 * <pre>
 *   MFMT 20020717210715 Fred.txt              213 Modify=20020717210715.000; Fred.txt
 *   MFCT 20020717212230 Jim.txt               213 Create=20020717212230.000; Jim.txt
 *   MFF UNIX.mode=644;Modify=...; Fred.txt    213 UNIX.mode=644;Modify=...; Fred.txt
 * </pre>
 * The reply shows the values as they are after the change (the file system may store less precision).
 * MFF can set Modify, Create, UNIX.mode (octal), UNIX.owner and UNIX.group; owner and group need the
 * ADMIN permission, the rest WRITE. A fact MFF doesn't know gets 504 and nothing is changed; an invalid
 * time 501; no such file 550; a fact the file system won't set 450 (MFF isn't atomic: facts before it
 * may have changed). A link is replaced by a copy that gets the change, as SITE CHMOD does, so what it
 * leads to isn't changed (a link to a directory is refused). Each is listed in FEAT; MFF with its facts.
 *
 * @since 1.1.0
 */
public class Mfxx extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;
	public static final String FACT_MODIFY = "Modify";
	public static final String FACT_CREATE = "Create";
	public static final String FACT_MODE = "UNIX.mode";
	public static final String FACT_OWNER = "UNIX.owner";
	public static final String FACT_GROUP = "UNIX.group";
	private static final String[] FACTS = {FACT_MODIFY, FACT_CREATE, FACT_MODE, FACT_OWNER, FACT_GROUP};
	private static final java.time.format.DateTimeFormatter STRICT = java.time.format.DateTimeFormatter
			.ofPattern("uuuuMMddHHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT);

	public Mfxx(String name) {
		super(name);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine();
		int sp = line.indexOf(' ');
		String arg = sp < 0 ? "" : line.substring(sp+1);
		int sp2 = arg.indexOf(' ');
		if( sp2 <= 0 || arg.substring(sp2+1).trim().isEmpty() ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: "+(getName().equals(MFF) ? "MFF fact=value;...; path" : getName()+" YYYYMMDDHHMMSS path"));
			return;
		}
		String first = arg.substring(0, sp2);
		String path = arg.substring(sp2+1).trim();

		// fact (as the client spelled it, in its order) to value
		Map<String, String> facts = new LinkedHashMap<String, String>();
		if( getName().equals(MFF) ) {
			if( !first.endsWith(";") ) {
				processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: MFF fact=value;...; path");
				return;
			}
			List<String> unknown = new ArrayList<String>();
			for(String f : first.split(";")) {
				int eq = f.indexOf('=');
				if( eq <= 0 ) {
					processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: MFF fact=value;...; path");
					return;
				}
				String name = canonical(f.substring(0, eq));
				if( name == null ) {
					unknown.add(f.substring(0, eq));
				} else {
					facts.put(name, f.substring(eq+1));
				}
			}
			if( !unknown.isEmpty() ) {
				processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,"Parameter Not Implemented ("+String.join(";", unknown)+")");
				return;
			}
		} else {
			facts.put(getName().equals(MFMT) ? FACT_MODIFY : FACT_CREATE, first);
		}

		// check them all before changing anything
		Map<String, Object> values = new LinkedHashMap<String, Object>();
		for(Map.Entry<String, String> e : facts.entrySet()) {
			String name = e.getKey();
			String v = e.getValue();
			if( name.equals(FACT_MODIFY) || name.equals(FACT_CREATE) ) {
				if( !v.matches("\\d{14}(\\.\\d{1,9})?") ) {
					processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Invalid time "+v+" (YYYYMMDDHHMMSS in UTC)");
					return;
				}
				try {
					// strict: February 30 is an error, not March 2 (the draft says 501 MUST be sent)
					java.time.LocalDateTime.parse(v.substring(0, 14), STRICT);
					values.put(name, Mlst.parseTime(v));
				} catch (DateTimeException ex) {
					processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Invalid time "+v);
					return;
				}
			} else if( name.equals(FACT_MODE) ) {
				if( !v.matches("0?[0-7]{3}") ) {
					processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Invalid UNIX.mode "+v+" (three octal digits)");
					return;
				}
				values.put(name, Integer.parseInt(v, 8));
			} else {
				if( !v.matches("[^;=\\s]+") ) {
					processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Invalid "+name+" "+v);
					return;
				}
				if( !processor.isAuthorized(ADMIN_PERMISSION) ) {
					processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Permission denied: "+name+" needs an administrator");
					return;
				}
				values.put(name, v);
			}
		}

		FileSource target = processor.createNewFile(path);
		if( target == null || !target.exists() ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"No such file: "+path);
			return;
		}
		if( processor.isLink(target) ) {
			if( target.isDirectory() ) {
				processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Permission denied.");
				return;
			}
			try {
				target = processor.replaceLinkWithCopy(target);
			} catch (IOException ex) {
				processor.logError("Can't replace link "+target, ex);
				processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Permission denied.");
				return;
			}
		}

		StringBuilder reply = new StringBuilder();
		for(Map.Entry<String, Object> e : values.entrySet()) {
			String name = e.getKey();
			boolean done;
			try {
				done = set(target, name, e.getValue());
			} catch (IOException | RuntimeException ex) {
				processor.logDebug(getName()+" can't set "+name+" of "+target, ex);
				done = false;
			}
			if( !done ) {
				processor.reply(REPLY_450_FILE_ACTION_FAILED,"Can't set "+name+" of "+path+(values.size() > 1 ? "; other facts may have changed" : ""));
				return;
			}
		}
		FileSource after = processor.createNewFile(path);
		for(String name : values.keySet()) {
			reply.append(name).append('=').append(get(after, name)).append(';');
		}
		processor.reply(REPLY_213_FILE_STATUS,reply+" "+path);
	}

	/** @return the fact as this server spells it, or null if it can't set it */
	private static String canonical(String name) {
		for(String f : FACTS) {
			if( f.equalsIgnoreCase(name) ) {
				return f;
			}
		}
		return null;
	}

	private static boolean set(FileSource f, String name, Object value) throws IOException {
		switch(name) {
		case FACT_MODIFY:
			return f.setLastModifiedTime((Long) value);
		case FACT_CREATE:
			return f.setCreateTime((Long) value);
		case FACT_MODE:
			int mode = (Integer) value;
			boolean done = true;
			done &= f.setOwnerReadable((mode & 0400) != 0);
			done &= f.setOwnerWritable((mode & 0200) != 0);
			done &= f.setOwnerExecutable((mode & 0100) != 0);
			done &= f.setGroupReadable((mode & 040) != 0);
			done &= f.setGroupWritable((mode & 020) != 0);
			done &= f.setGroupExecutable((mode & 010) != 0);
			done &= f.setOtherReadable((mode & 04) != 0);
			done &= f.setOtherWritable((mode & 02) != 0);
			done &= f.setOtherExecutable((mode & 01) != 0);
			return done;
		case FACT_OWNER:
			return f.setOwner(new FileSourceUser(-1, (String) value));
		case FACT_GROUP:
			return f.setGroup(new FileSourceGroup(-1, (String) value));
		default:
			return false;
		}
	}

	private static String get(FileSource f, String name) throws IOException {
		switch(name) {
		case FACT_MODIFY:
			return Mlst.formatTime(f.lastModified());
		case FACT_CREATE:
			return Mlst.formatTime(f.creationTime());
		case FACT_MODE:
			int mode = (f.canOwnerRead() ? 0400 : 0) | (f.canOwnerWrite() ? 0200 : 0) | (f.canOwnerExecute() ? 0100 : 0)
					| (f.canGroupRead() ? 040 : 0) | (f.canGroupWrite() ? 020 : 0) | (f.canGroupExecute() ? 010 : 0)
					| (f.canOtherRead() ? 04 : 0) | (f.canOtherWrite() ? 02 : 0) | (f.canOtherExecute() ? 01 : 0);
			return String.format("%03o", mode);
		case FACT_OWNER:
			return f.getOwner() == null ? "" : f.getOwner().getName();
		case FACT_GROUP:
			return f.getGroup() == null ? "" : f.getGroup().getName();
		default:
			return "";
		}
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		if( getName().equals(MFF) ) {
			return MFF+" "+String.join(";", FACTS)+";";
		}
		return getName();
	}

	@Override
	public IPermission getPermission() {
		return WRITE_PERMISSION;
	}
}
