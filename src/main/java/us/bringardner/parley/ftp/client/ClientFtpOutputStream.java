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

    private void startUpload() throws IOException {
        if( ascii ) {
            client.setAsciiType();
        } else {
            client.setImageType();
        }
        
        dtp = client.getDataTransferProcess();

        String cmd = append ? APPE : STOR;
        ClientFtpResponse res = null;
        try {
            dtp.connectBeforeCommand();
            if( startAt > 0 ) {
                // straight before STOR, after PASV/PORT, so no other command comes between them
                ClientFtpResponse rest = client.executeCommand(REST, ""+startAt);
                if( !rest.isPositiveIntermediate() ) {
                    throw new IOException("The server won't start a STOR of "+path+" at "+startAt+": REST "+startAt+" = "+rest);
                }
            }
            res = client.executeCommand(cmd, path);
        } catch (IOException e) {
            dtp.close();
            throw e;
        }
        if( !res.isPositivePreliminay()) {
            dtp.close();
            throw new IOException ("Error invalid respones to "+cmd+" = "+res);
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
        out.write(b);
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
    			client.streamHasClosed(path,this);
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
        out.write(b, off, len);
    }

    public void write(byte[] b) throws IOException {
        out.write(b);
    }

}
