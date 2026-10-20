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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Locale;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.ftp.server.FeatCommand;
import us.bringardner.parley.ftp.server.FtpRequestProcessor;
import us.bringardner.parley.net.server.IRequestContext;

/**
 * THMB format width height path (draft-peterson-streamlined-ftp-command-extensions): a thumbnail of an
 * image, no larger than width x height (the aspect kept, never made larger), in that format, sent on the
 * data connection as RETR sends a file (150 with the size, then 226), always as binary. Formats are what
 * Java can write (FEAT lists them: {@code THMB BMP|GIF|JPEG|PNG}); JPG is JPEG. 501 for a format it can't
 * write or a bad size; 550 for no such file or one that isn't an image it can read.
 *
 * @since 1.1.0
 */
public class Thmb extends BaseCommand implements FeatCommand {

	private static final long serialVersionUID = 1L;
	/** The largest thumbnail side asked for that is made */
	public static final int MAX_SIDE = 4096;

	public Thmb() {
		super(THMB);
	}

	/** @return the format names Java can write here, upper case */
	static TreeSet<String> formats() {
		TreeSet<String> ret = new TreeSet<String>();
		for(String f : ImageIO.getWriterFormatNames()) {
			String u = f.toUpperCase(Locale.ROOT);
			if( u.equals("JPEG") || u.equals("PNG") || u.equals("GIF") || u.equals("BMP") ) {
				ret.add(u);
			}
		}
		return ret;
	}

	@Override
	public void execute(FtpRequestProcessor processor, IRequestContext context) throws IOException {
		String line = context.getCommandLine();
		String[] t = line.trim().split(" +", 5);
		if( t.length != 5 ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Syntax: THMB format width height path");
			return;
		}
		String format = t[1].toUpperCase(Locale.ROOT);
		if( format.equals("JPG") ) {
			format = "JPEG";
		}
		if( !formats().contains(format) ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Format "+t[1]+" not supported; "+String.join("|", formats()));
			return;
		}
		int w, h;
		try {
			w = Integer.parseInt(t[2]);
			h = Integer.parseInt(t[3]);
		} catch (NumberFormatException e) {
			w = h = -1;
		}
		if( w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE ) {
			processor.reply(REPLY_501_SYNTAXT_ERROR_IN_PARAM,"Width and height are 1 to "+MAX_SIDE);
			return;
		}
		String path = t[4].trim();
		FileSource f = processor.createNewFile(path);
		if( f == null || !f.exists() || f.isDirectory() || !f.canRead() ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"No such file: "+path);
			return;
		}
		byte[] thumb;
		try {
			thumb = thumbnail(f, format, w, h);
		} catch (IOException | RuntimeException e) {
			processor.logDebug("THMB "+path, e);
			thumb = null;
		}
		if( thumb == null ) {
			processor.reply(REPLY_550_ACTION_NOT_TAKEN,"Can't make a thumbnail of "+path);
			return;
		}
		Socket sock = processor.getDataSocket();
		if( sock == null ) {
			processor.reply(REPLY_425_CANT_OPEN_DATA_CON,"Can't open data socket");
			return;
		}
		OutputStream out;
		try {
			out = sock.getOutputStream();
		} catch (IOException e) {
			processor.reply(REPLY_425_CANT_OPEN_DATA_CON,"Can't open data connection: "+e.getMessage());
			return;
		}
		processor.transferStream(new ByteArrayInputStream(thumb), out, sock, false, null,
				"Opening BINARY mode data connection for thumbnail ("+thumb.length+" Bytes)");
	}

	/** @return the thumbnail, or null if the file isn't an image Java can read */
	static byte[] thumbnail(FileSource f, String format, int maxW, int maxH) throws IOException {
		BufferedImage src;
		try (InputStream in = f.getInputStream()) {
			src = ImageIO.read(in);
		}
		if( src == null ) {
			return null;
		}
		double scale = Math.min(1.0, Math.min(maxW / (double) src.getWidth(), maxH / (double) src.getHeight()));
		int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
		boolean alpha = src.getColorModel().hasAlpha() && (format.equals("PNG") || format.equals("GIF"));
		BufferedImage dst = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		Graphics2D g = dst.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			if( !alpha ) {
				g.setColor(java.awt.Color.WHITE);
				g.fillRect(0, 0, w, h);
			}
			g.drawImage(src, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}
		ByteArrayOutputStream ret = new ByteArrayOutputStream();
		if( !ImageIO.write(dst, format, ret) ) {
			return null;
		}
		return ret.toByteArray();
	}

	@Override
	public String getFeatResponse(FtpRequestProcessor processor) {
		return THMB+" "+String.join("|", formats());
	}
}
