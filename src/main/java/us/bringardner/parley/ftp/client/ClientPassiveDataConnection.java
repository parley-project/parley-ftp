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
 * ~version~V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 13, 2006
 *
 */
package us.bringardner.parley.ftp.client;

import java.io.IOException;

/**
 * Coordinate a passive data connection with the server by issuing a PASV command to get the host and port from the server.
 * 
 * @author Tony Bringardner
 *
 */
public final class ClientPassiveDataConnection extends ClientDataTransferProcess {

    private static final java.util.regex.Pattern EPSV_REPLY =
            java.util.regex.Pattern.compile("\\((.)\\1\\1(\\d+)\\1\\)");

    /**
     * @return the address in an LPSV reply ({@code (af,hal,h1,...,hn,pal,p1,p2)}, RFC 1639), or null
     * @since 1.1.0
     */
    public static java.net.InetSocketAddress parseLong(String text) {
        int open = text.indexOf('(');
        int close = text.indexOf(')', open+1);
        if( open < 0 || close < 0 ) {
            return null;
        }
        String[] parts = text.substring(open+1, close).split(",");
        try {
            int af = Integer.parseInt(parts[0].trim());
            int hal = Integer.parseInt(parts[1].trim());
            if( !((af == 4 && hal == 4) || (af == 6 && hal == 16)) || parts.length != 2+hal+3
                    || Integer.parseInt(parts[2+hal].trim()) != 2 ) {
                return null;
            }
            byte[] b = new byte[hal];
            for(int i=0; i < hal; i++ ) {
                b[i] = (byte) Integer.parseInt(parts[2+i].trim());
            }
            int port = Integer.parseInt(parts[2+hal+1].trim())*256 + Integer.parseInt(parts[2+hal+2].trim());
            return new java.net.InetSocketAddress(java.net.InetAddress.getByAddress(b), port);
        } catch (RuntimeException | java.net.UnknownHostException e) {
            return null;
        }
    }

    public ClientPassiveDataConnection(FtpClient client) throws IOException {    	
        super(client);
        getLogger().setLevel(client.getLogger().getLevel());
        setPassive(true);

        /*
         * RFC 2428: EPSV returns only a port, the address is the control connection's
         * (works for IPv6 and through NAT). Fall back to PASV if the server doesn't know it.
         */
        if( client.isUseEpsv() && !client.isEpsvRejected() ) {
            ClientFtpResponse res = client.executeCommand(EPSV);
            java.util.regex.Matcher m = EPSV_REPLY.matcher(res.getResponseText());
            // 229 is the RFC reply; older versions of this project's server replied 227
            if( (res._getResponseCode() == 229 || res._getResponseCode() == 227) && m.find() ) {
                setHost(client.getHost());
                setPort(Integer.parseInt(m.group(2)));
                return;
            }
            int code = res._getResponseCode();
            if( code >= 500 ) {
                // not supported (500/502/504) or wrong protocol (522): use PASV from now on
                client.setEpsvRejected(true);
            } else {
                throw new IOException("Invalid response from "+EPSV+" command = "+res);
            }
        }

        ClientFtpResponse res = client.executeCommand(PASV);
        if( !res.isPositiveComplet() && res._getResponseCode() >= 500 ) {
            // RFC 1639 (historic): a server without EPSV whose PASV can't give an IPv6 address
            ClientFtpResponse lpsv = client.executeCommand(LPSV);
            java.net.InetSocketAddress addr = lpsv._getResponseCode() == 228 ? parseLong(lpsv.getResponseText()) : null;
            if( addr != null ) {
                setHost(client.isUsePasvAddress() ? addr.getAddress().getHostAddress() : client.getHost());
                setPort(addr.getPort());
                return;
            }
        }
        if( !res.isPositiveComplet()) {
            throw new IOException("Invalid response from "+PASV+" command = "+res._getResponseCode());
        }
        setHostAndPort(res.getResponseText());
    }
    
    
    

}
