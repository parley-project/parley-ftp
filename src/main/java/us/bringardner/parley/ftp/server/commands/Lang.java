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
import java.util.Locale;

import us.bringardner.parley.ftp.server.FeatCommand;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * LANG (RFC 2640 section 4): the language of the server's reply text. This server has English only, so
 * {@code LANG}, {@code LANG EN} and any English variant ({@code LANG en-US}) get 200 and change nothing;
 * another language gets 504, a tag that isn't one 501. Listed in FEAT as {@code LANG EN*}.
 *
 * @since 1.1.0
 */
public class Lang extends NoAuthReqBaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;

	public Lang() {
		super(LANG);
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine().trim();
		int sp = line.indexOf(' ');
		String tag = sp < 0 ? "" : line.substring(sp+1).trim();
		if( tag.isEmpty() ) {
			processor.reply(REPLY_200_OK,"Using the default language, English");
			return;
		}
		if( !tag.matches("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*") ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: LANG [language tag]");
			return;
		}
		String primary = tag.split("-")[0].toUpperCase(Locale.ROOT);
		if( primary.equals("EN") ) {
			processor.reply(REPLY_200_OK,"Responses in English");
		} else {
			processor.reply(REPLY_504_NOT_IMP_FOR_PARAM,"Language "+tag+" not supported; only EN");
		}
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return LANG+" EN*";
	}
}
