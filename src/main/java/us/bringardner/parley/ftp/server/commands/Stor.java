/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 <A href="http://bringardner.com/tony">Tony Bringardner</A>
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
 *
 *
 * ~version~V000.01.37-V000.01.35-V000.01.09-V000.01.07-V000.01.05-V000.01.02-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 14, 2004
 *
 */
package us.bringardner.parley.ftp.server.commands;

import java.io.IOException;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IRequestContext;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;


/**
 * @author Tony Bringardner
 *
 */
public class Stor  extends BaseCommand {

	private static final long serialVersionUID = 1L;

	/**
	 * 
	 */
	public Stor() {
		super(STOR);
	
	}

	/*
	 * RFC 959
	 * 
	 * STORE (STOR)

            This command causes the server-DTP to accept the data
            transferred via the data connection and to store the data as
            a file at the server site.  If the file specified in the
            pathname exists at the server site, then its contents shall
            be replaced by the data being transferred.  A new file is
            created at the server site if the file specified in the
            pathname does not alREAD_PERMISSIONy exist.


                  125, 150
                     (110)
                     226, 250
                     425, 426, 451, 551, 552
                  532, 450, 452, 553
                  500, 501, 421, 530


	 * @see us.bringardner.parley.ftp.server.FtpCommand#execute(us.bringardner.parley.ftp.server.FtpRequestProcessor, java.lang.String)
	 */
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String commandLine = pathArgument(processor, context);
		if( commandLine == null ) {
			return;
		}
			
		
		FileSource target = processor.createNewFile(commandLine);
		if( target == null || target.isDirectory()){
			processor.reply(REPLY_450_FILE_ACTION_FAILED," Invalid or non existant name");
			return;
		} 
		processor.logDebug(getName()+" target = "+target.getAbsolutePath());
		// A file that can't be written can't be replaced either (the upload goes to a temporary
		// file that is renamed over it, which ignores the file's own permissions). A link has the
		// permissions of what it leads to, so an administrator makes one read-only by making that
		// read-only, or the directory it is in.
		if( target.exists() && !target.canWrite() ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Permission denied.");
			return;
		}

		Rang.Range range = (Rang.Range)processor.removeTempValue(RANG);
		if( range != null ) {
			processor.removeTempValue(REST);
			storeRange(processor, target, range);
			return;
		}

		Long rest = (Long)processor.removeTempValue(REST);
		// REST 0 means "no restart" (RFC 3659)
		boolean resume = rest != null && rest.longValue() > 0;
		if( resume && processor.isStoreAtOffset() ) {
			// random access: write in place from the marker, leaving the rest of the file as it was
			if( processor.isLink(target) ) {
				// written in place, the file it leads to would change
				processor.reply(REPLY_450_FILE_ACTION_FAILED," Restart marker "+rest+" is not accepted on a link");
				return;
			}
			long len = target.exists() ? target.length() : 0;
			if( rest.longValue() > len ) {
				// RFC 3659 section 5.5
				processor.reply(REPLY_554_INVALID_REST,"Invalid REST "+rest+": the file is "+len+" bytes");
				return;
			}
			processor.receiveFileAt(target, rest.longValue());
			return;
		}
		if( resume ){
			long len = target.exists() ? target.length() : 0;
			if( len != rest.longValue()) {
				processor.reply(REPLY_450_FILE_ACTION_FAILED," Restart marker "+rest+" does not match the file size ("+len+")");
				return;
			}
			/*
			 * REST and STOR carry on an upload that stopped. A file the server doesn't know to be
			 * unfinished is complete: SIZE gives its length, and a client that resumes with that
			 * (an upload that was interrupted over an old file) would have it added to, which is
			 * neither version. It starts again instead.
			 */
			if( !processor.isUnfinishedUpload(target, len) ) {
				processor.reply(REPLY_450_FILE_ACTION_FAILED," Restart marker "+rest+" is not accepted: store the file again without REST");
				return;
			}
		}
		boolean inPlace = resume || processor.getUploadMode() == us.bringardner.parley.ftp.server.FtpServer.UploadMode.IN_PLACE;
		if( inPlace && !resume && processor.isLink(target) ) {
			// written in place a link would be written through, and the original changed: a store
			// replaces the link with a regular file (a temporary file does that by itself)
			if( !target.delete() ) {
				processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Permission denied.");
				return;
			}
		}
		/*
		 * receiveFile gets the data connection before touching the file and, unless
		 * this is a restart or the upload mode is IN_PLACE, writes to a temporary file that
		 * replaces the target only when the whole upload has been received.
		 */
		processor.receiveFile(target, resume, inPlace);

	}
	
	/**
	 * RANG then STOR (draft-bryan-ftp-range): the upload is written into the file from the range's start,
	 * in place, and nothing outside the range changes. Unlike REST then STOR, the client says exactly
	 * which octets it writes, so this doesn't need FtpServer.setStoreAtOffset.
	 */
	private void storeRange(FtpRequestProcessor processor, FileSource target, Rang.Range range) throws IOException {
		if( processor.isLink(target) ) {
			// written in place, the file it leads to would change
			processor.reply(REPLY_450_FILE_ACTION_FAILED," RANG with STOR is not accepted on a link");
			return;
		}
		long len = target.exists() ? target.length() : 0;
		if( range.getStart() > len ) {
			processor.reply(REPLY_554_INVALID_REST,"Invalid RANG "+range.getStart()+" "+range.getEnd()+": the file is "+len+" bytes");
			return;
		}
		processor.receiveFileAt(target, range.getStart(), range.getLength());
	}

	@Override
	public IPermission getPermission() {
		return WRITE_PERMISSION;
	}


}
