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
 * ~version~V000.01.51-V000.01.47-V000.01.03-V000.01.02-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 14, 2006
 *
 */
package us.bringardner.parley.ftp.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

import us.bringardner.parley.ftp.FTP;

public class ClientFtpInputStream extends InputStream implements FTP {

    //  This client is a private connection just for downloading one file.
    private FtpClient client;
    private String path;
    /** The command that sends the data, with its argument: RETR path unless set (THMB) */
    private String command;
    private InputStream in ;
    private boolean eof = false;
    private boolean closed = false;
    private ClientDataTransferProcess dtp ;
    private boolean ascii;
    private long startAt;
    /** The last octet wanted (inclusive, sent with RANG), or -1 for to the end of the file. */
    private long endAt = -1;
    /** Octets left to hand out when the server couldn't do RANG and this stops at endAt itself; -1 for no limit. */
    private long remaining = -1;
    /** What the buffer was made with: the size asked for, or the client's, and never under 8 KB. */
    private int effectiveBufferSize;
    /** The final reply, when it was read before the stream was handed out */
    private ClientFtpResponse earlyReply;

    public ClientFtpInputStream(String path, FtpClient client) throws IOException {
        this(path,client,false);
    }

    public ClientFtpInputStream(String path, FtpClient client, boolean ascii) throws IOException {
        this(path,client,ascii,0l); // was always false, so ASCII was ignored
    }

    public ClientFtpInputStream(String path, FtpClient client, boolean ascii, long startingPos) throws IOException {
        this(path,client,ascii,startingPos,0);
    }

    /**
     * @param bufferSize the size of this stream's buffer, or 0 for the client's
     * {@link FtpClient#getTransferBufferSize()} (it is never less than 8 KB)
     */
    public ClientFtpInputStream(String path, FtpClient client, boolean ascii, long startingPos, int bufferSize) throws IOException {
        this.path = path;
        this.ascii = ascii;
        this.client = client;
        this.startAt = startingPos;
        this.effectiveBufferSize = Math.max(8192, bufferSize > 0 ? bufferSize : client.getTransferBufferSize());
        startDownload();

    }

    /**
     * The data another command sends on the data connection, as RETR does (THMB). Binary.
     *
     * @param key what the stream is kept under in the client while it is open
     * @param commandLine the command and its arguments
     * @since 1.1.0
     */
    ClientFtpInputStream(String key, FtpClient client, String commandLine, int bufferSize) throws IOException {
        this.path = key;
        this.command = commandLine;
        this.client = client;
        this.effectiveBufferSize = Math.max(8192, bufferSize > 0 ? bufferSize : client.getTransferBufferSize());
        startDownload();
    }

    /**
     * A download of the octets from startingPos to endingPos, inclusive. A server that lists RANG in
     * FEAT (draft-bryan-ftp-range) is sent RANG startingPos endingPos and sends just that range.
     * Otherwise, or if it refuses the RANG, this starts at startingPos as with REST and stops after
     * endingPos itself (the rest of the transfer is abandoned when the stream is closed).
     * An endingPos past the end of the file reads to the end of the file.
     *
     * @param startingPos the first octet wanted
     * @param endingPos the last octet wanted (inclusive)
     * @param bufferSize the size of this stream's buffer, or 0 for the client's
     * {@link FtpClient#getTransferBufferSize()} (it is never less than 8 KB)
     * @throws IllegalArgumentException if startingPos is negative or endingPos is before it
     * @since 1.1.0
     */
    public ClientFtpInputStream(String path, FtpClient client, boolean ascii, long startingPos, long endingPos, int bufferSize) throws IOException {
        if( startingPos < 0 || endingPos < startingPos ) {
            throw new IllegalArgumentException("Invalid range "+startingPos+"-"+endingPos);
        }
        this.path = path;
        this.ascii = ascii;
        this.client = client;
        this.startAt = startingPos;
        this.endAt = endingPos;
        this.effectiveBufferSize = Math.max(8192, bufferSize > 0 ? bufferSize : client.getTransferBufferSize());
        startDownload();
    }

    
    private void startDownload() throws IOException {
        if( ascii ) {
            client.setAsciiType();
        } else {
            client.setImageType();
        }
        boolean canRestore = false;
        boolean ranged = false;

        if( endAt >= 0 && client.getFeatResponse().containsKey(RANG) ) {
            ClientFtpResponse res = client.executeCommand(RANG, startAt+" "+endAt);
            if( res.isPositiveIntermediate() ) {
                ranged = true;
            } else {
                logError(" Server said it would support RANG but did not.  ResponseCode="+res._getResponseCode());
            }
        }

        if( startAt > 0l && !ranged) {
            canRestore = client.getFeatResponse().containsKey(REST);
            if(canRestore) {
                //  WE can do a restore
                ClientFtpResponse res = client.executeCommand(REST,""+startAt);
                if( !res.isPositiveIntermediate()) {
                    canRestore = false;
                    logError(" Sever said it would support REST but did not.  ResponseCode="+res._getResponseCode());
                }

            }
        }

        
        dtp = client.getDataTransferProcess();
        ClientFtpResponse res;
        try {
            dtp.connectBeforeCommand();
            res = command == null ? client.executeCommand(RETR, path.trim()) : client.executeCommand(command);
        } catch (IOException e) {
            dtp.close();
            throw e;
        }
        
        if(!res.isPositivePreliminay()) {
            dtp.close();
            throw new IOException ("Error invalid respones to RETR = "+res._getResponseCode());
        }
        // Active mode accepts the server's connection here, after the 1xx reply. TLS is
        // started now so a refused session can still be retried (BJL-28).
        ClientFtpResponse finished = client.startDataTls(dtp);
        if( finished != null ) {
            // an empty file, already complete
            earlyReply = finished;
            eof = true;
            in = new java.io.ByteArrayInputStream(new byte[0]);
            return;
        }
        // Buffered: read() on a raw socket stream is one system call per byte
        in = new BufferedInputStream(dtp.getInput(), effectiveBufferSize);
        if( client.isDataChannelSecure() ) {
            /*
             * A server that refuses the TLS session after the handshake (vsftpd with TLS 1.3)
             * just closes the data connection and replies 522. Look at the first byte before
             * handing the stream out, so such a refusal can still be retried (BJL-28).
             */
            in.mark(1);
            if( in.read() < 0 ) {
                // no data at all: an empty file, or a refused data connection
                dtp.close();
                ClientFtpResponse done = client.readResponse();
                client.checkTlsResumeRefusal(done);
                earlyReply = done;
                eof = true;
            } else {
                in.reset();
            }
        }
        
        if( endAt >= 0 && !ranged ) {
            // the server sends to the end of the file: stop at endAt here
            long len = endAt - startAt;
            remaining = len == Long.MAX_VALUE ? Long.MAX_VALUE : len + 1;
        }

        if( startAt > 0l && !canRestore && !ranged) {
            //  Server could not do it so ignore the startAt data
            long skipped = in.skip(startAt);
            if( skipped != startAt ) {
                throw new IOException("Server would not honor REST and we could not skip "+startAt+" bytes of data");
            }
        }
    }

    private void logError(String msg) {
        client.logError(getClass().getName()+" : "+msg);   
    }

    /**
     * Close the data connection and read the server's final reply.
     * @return the final reply
     */
    private ClientFtpResponse completeDownload() throws IOException {
        if( earlyReply != null ) {
            return earlyReply;
        }
        dtp.close();
        try {
            return client.readResponse();
        } catch(SocketTimeoutException ex) {
            // We don't know where the control connection is in the conversation any more;
            // drop it so the next command reconnects instead of reading the wrong reply.
            client.abandonConnection();
            throw new IOException("Timed out waiting for the server to confirm the download of "+path, ex);
        }
    }

    public int available() throws IOException {
        return in.available();
    }

    public int read(byte[] arg0) throws IOException {
        return read(arg0, 0, arg0.length);
    }

    public long skip(long arg0) throws IOException {
        return super.skip(arg0);
    }

    public int read() throws IOException {
        int ret = -1;
        if( remaining == 0 ) {
            // the end of the range: closing abandons the rest of the transfer
            return -1;
        }
        if( !eof ) {
            if((ret=in.read()) == -1 ) {
                eof = true; 
            } else if( remaining > 0 ) {
                remaining--;
            }
        }
        return ret;
    }


    public int read(byte[] b, int off, int len) throws IOException {
        if( remaining == 0 ) {
            // the end of the range: closing abandons the rest of the transfer
            return len == 0 ? 0 : -1;
        }
        if( remaining > 0 ) {
            len = (int)Math.min(len, remaining);
        }
        int ret = in.read(b, off, len);
        if( ret == -1 ) {
            eof = true;
        } else if( remaining > 0 ) {
            remaining -= ret;
        }
        return ret;
    }


    /**
     * Close the download. If the whole stream was read (EOF) and the server reports that the
     * transfer failed (e.g. 426), an IOException is thrown because the data is incomplete.
     * Closing before EOF abandons the transfer, so a negative reply is expected and ignored.
     * Calling close more than once has no effect.
     */
    /** @return the size of this stream's buffer: what it was opened with, else the client's, and at least 8 KB */
    public int getBufferSize() {
        return effectiveBufferSize;
    }

    public void close() throws IOException {
        if( closed ) {
            return;
        }
        closed = true;
        try {
            ClientFtpResponse res = completeDownload();
            if( eof && !res.isPositiveComplet() ) {
                if( client.noteTlsResumeRefusal(res) ) {
                    throw new IOException("Download of "+path+" failed, server refused the TLS 1.3 data connection ("+res+"); retry, the next connection uses TLS 1.2");
                }
                throw new IOException("Download of "+path+" failed: "+res);
            }
        } finally {
            client.streamHasClosed(path, this);
        }
    }




}
