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
 * ~version~V000.01.51-V000.01.09-V000.01.03-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 15, 2006
 *
 */
package us.bringardner.parley.ftp.client;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;

import us.bringardner.parley.ftp.FTP;

public class ClientFtpOutputStream extends OutputStream implements FTP {
    //  This client is a private connection just for downloading one file.
    private FtpClient client;
    private String path;
    private OutputStream out ;
    private ClientDataTransferProcess dtp ;
    private boolean ascii;
    private boolean append = false;
    /** Where STOR starts writing (sent as REST first); 0 for a plain STOR or APPE. */
    private long startAt = 0;
    /** STOU: the name to start from (may be null) and the name the server stored under */
    private boolean unique = false;
    private String storedName;
    /** The last octet of a RANG store (inclusive), or -1 when there is no range. */
    private long endAt = -1;
    /** Octets the range still holds; -1 for no limit. */
    private long remaining = -1;
    /** What the buffer was made with: the size asked for, or the client's, and never under 8 KB. */
    private int effectiveBufferSize;
    private boolean closed = false;
    
    public ClientFtpOutputStream(String path, FtpClient client, boolean ascii, boolean append) throws IOException {
        this(path,client,ascii,append,0);
    }

    /**
     * @param bufferSize the size of this stream's buffer, or 0 for the client's
     * {@link FtpClient#getTransferBufferSize()} (it is never less than 8 KB)
     */
    public ClientFtpOutputStream(String path, FtpClient client, boolean ascii, boolean append, int bufferSize) throws IOException {
        this(path, client, ascii, append, 0, bufferSize);
    }

    /**
     * A STOR that starts at a position: REST startAt is sent just before STOR, so the server
     * writes the data into the file from there (RFC 3659 section 5.5). What the server does with
     * the rest of the file is up to the server; one that writes in place leaves it as it was.
     * If the server refuses the REST, nothing is stored and this throws: a STOR without it would
     * replace the whole file.
     *
     * @param startAt where the data goes in the file; 0 for a plain STOR (or APPE when append is true)
     * @param bufferSize the size of this stream's buffer, or 0 for the client's
     * {@link FtpClient#getTransferBufferSize()} (it is never less than 8 KB)
     * @throws IllegalArgumentException if startAt is negative, or not 0 when append is true
     * @since 1.1.0
     */
    public ClientFtpOutputStream(String path, FtpClient client, boolean ascii, boolean append, long startAt, int bufferSize) throws IOException {
        if( startAt < 0 ) {
            throw new IllegalArgumentException("Negative starting position "+startAt);
        }
        if( append && startAt != 0 ) {
            throw new IllegalArgumentException("APPE has no starting position");
        }
        this.startAt = startAt;
        this.path = path;
        this.client = client;
        this.ascii = ascii;
        this.append = append;
        this.effectiveBufferSize = Math.max(8192, bufferSize > 0 ? bufferSize : client.getTransferBufferSize());
        startUpload();
    }

    /**
     * A STOR of the octets from startAt to endAt, inclusive: RANG startAt endAt is sent just before
     * STOR (draft-bryan-ftp-range), so the server writes the data into the file from startAt and
     * changes nothing outside the range (the file isn't truncated). If the server refuses the RANG,
     * nothing is stored and this throws. Writing more than the range holds throws, and less writes
     * only what was written.
     *
     * @param startAt the first octet written
     * @param endAt the last octet the range holds (inclusive)
     * @param bufferSize the size of this stream's buffer, or 0 for the client's
     * {@link FtpClient#getTransferBufferSize()} (it is never less than 8 KB)
     * @throws IllegalArgumentException if startAt is negative or endAt is before it
     * @since 1.1.0
     */
    public ClientFtpOutputStream(String path, FtpClient client, boolean ascii, long startAt, long endAt, int bufferSize) throws IOException {
        if( startAt < 0 || endAt < startAt ) {
            throw new IllegalArgumentException("Invalid range "+startAt+"-"+endAt);
        }
        this.startAt = startAt;
        this.endAt = endAt;
        long len = endAt - startAt;
        this.remaining = len == Long.MAX_VALUE ? Long.MAX_VALUE : len + 1;
        this.path = path;
        this.client = client;
        this.ascii = ascii;
        this.effectiveBufferSize = Math.max(8192, bufferSize > 0 ? bufferSize : client.getTransferBufferSize());
        startUpload();
    }

    /**
     * STORE UNIQUE (STOU): the server stores the data under a name it chooses, in the current directory,
     * and says which in its 150 reply ({@link #getStoredName()}).
     *
     * @param baseName a name for the server to start from, or null (some servers ignore it)
     * @since 1.1.0
     */
    public ClientFtpOutputStream(String baseName, FtpClient client, boolean ascii, int bufferSize, boolean unique) throws IOException {
        if( !unique ) {
            throw new IllegalArgumentException("Use another constructor for STOR");
        }
        this.unique = true;
        this.path = baseName;
        this.client = client;
        this.ascii = ascii;
        this.effectiveBufferSize = Math.max(8192, bufferSize > 0 ? bufferSize : client.getTransferBufferSize());
        startUpload();
    }

    /**
     * @return for STOU, the name the server stored the data under (from {@code 150 FILE: name}, RFC 1123),
     * or null if its reply didn't say; null for other uploads
     * @since 1.1.0
     */
    public String getStoredName() {
        return storedName;
    }

    /** The key a STOU stream is kept under while it is open */
    static String uniqueKey(String baseName) {
        return STOU+":"+(baseName == null ? "" : baseName);
    }

    /** @return the name in a STOU reply: "FILE: name" (RFC 1123), else null */
    static String storedNameIn(String text) {
        if( text == null ) {
            return null;
        }
        int idx = text.toUpperCase(java.util.Locale.ROOT).indexOf("FILE:");
        if( idx < 0 ) {
            return null;
        }
        String ret = text.substring(idx+5).trim();
        return ret.isEmpty() ? null : ret;
    }

    private void startUpload() throws IOException {
        if( ascii ) {
            client.setAsciiType();
        } else {
            client.setImageType();
        }
        
        dtp = client.getDataTransferProcess();

        String cmd = unique ? STOU : append ? APPE : STOR;
        ClientFtpResponse res = null;
        try {
            dtp.connectBeforeCommand();
            if( endAt >= 0 ) {
                // straight before STOR, after PASV/PORT, so no other command comes between them
                ClientFtpResponse rang = client.executeCommand(RANG, startAt+" "+endAt);
                if( !rang.isPositiveIntermediate() ) {
                    throw new IOException("The server won't STOR "+path+" in the range "+startAt+"-"+endAt+": RANG "+startAt+" "+endAt+" = "+rang);
                }
            } else if( startAt > 0 ) {
                // straight before STOR, after PASV/PORT, so no other command comes between them
                ClientFtpResponse rest = client.executeCommand(REST, ""+startAt);
                if( !rest.isPositiveIntermediate() ) {
                    throw new IOException("The server won't start a STOR of "+path+" at "+startAt+": REST "+startAt+" = "+rest);
                }
            }
            if( unique ) {
                res = path == null || path.isEmpty() ? client.executeCommand(STOU) : client.executeCommand(STOU, path);
            } else {
                res = client.executeCommand(cmd, path);
            }
        } catch (IOException e) {
            dtp.close();
            throw e;
        }
        if( !res.isPositivePreliminay()) {
            dtp.close();
            throw new IOException ("Error invalid respones to "+cmd+" = "+res);
        }
        if( unique ) {
            storedName = storedNameIn(res.getResponseText());
        }
        // Active mode accepts the server's connection here, after the 1xx reply. TLS is
        // started now so a refused session can still be retried (BJL-28).
        ClientFtpResponse finished = client.startDataTls(dtp);
        if( finished != null ) {
            throw new IOException(cmd+" "+path+": the server ended the transfer before any data was sent ("+finished+")");
        }
        // Buffered: write(int) on a raw socket stream is one system call (and TCP packet) per byte
        out = new BufferedOutputStream(dtp.getOutput(), effectiveBufferSize);
        
        closed = false;
    }

    public void write(int b) throws IOException {
        if( remaining == 0 ) {
            throw tooMuch();
        }
        out.write(b);
        if( remaining > 0 ) {
            remaining--;
        }
    }

    private IOException tooMuch() {
        return new IOException("More data than the range "+startAt+"-"+endAt+" of "+path+" holds");
    }

    /** @return the size of this stream's buffer: what it was opened with, else the client's, and at least 8 KB */
    public int getBufferSize() {
        return effectiveBufferSize;
    }

    public void close() throws IOException {
    	if( !closed ) {
    		closed = true;
    		try {
    			completeUpload();
    		} finally {
    			// Always release the path, even if the upload failed
    			client.streamHasClosed(unique ? uniqueKey(path) : path,this);
    		}
    	}
    }

    private void completeUpload() throws IOException {
    	flush();
        dtp.close();
        
        long time = System.currentTimeMillis();
        try {
        	ClientFtpResponse res = client.readResponse();
        	if( !res.isPositiveComplet()) {
        		if( client.noteTlsResumeRefusal(res) ) {
        			throw new IOException("Upload of "+path+" failed, server refused the TLS 1.3 data connection ("+res+"); retry, the next connection uses TLS 1.2");
        		}
        		throw new IOException("Error completing transfer.  response = "+res);
        	}
        } catch(SocketTimeoutException ex) {
        	// The control connection is out of step now; reconnect on the next command
        	client.abandonConnection();
        	throw new IOException("Timeout Error completing the upload time="+(System.currentTimeMillis()-time), ex);
        } 

    }

    public void flush() throws IOException {
        out.flush();
    }

    public void write(byte[] b, int off, int len) throws IOException {
        if( remaining >= 0 && len > remaining ) {
            // nothing of it is written: the server would refuse what's past the range anyway
            throw tooMuch();
        }
        out.write(b, off, len);
        if( remaining > 0 ) {
            remaining -= len;
        }
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

}
