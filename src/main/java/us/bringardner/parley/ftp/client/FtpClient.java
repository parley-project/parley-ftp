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
 * ~version~V000.01.56-V000.01.55-V000.01.52-V000.01.51-V000.01.50-V000.01.48-V000.01.46-V000.01.43-V000.01.42-V000.01.40-V000.01.36-V000.01.35-V000.01.33-V000.01.18-V000.01.16-V000.01.15-V000.01.13-V000.01.12-V000.01.11-V000.01.09-V000.01.05-V000.01.03-V000.01.02-V000.01.02-V000.00.03-V000.00.01-V000.00.00-
 */
/*
 * Created on Nov 24, 2006
 *
 */
package us.bringardner.parley.ftp.client;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import us.bringardner.parley.core.SecureBaseObject;
import us.bringardner.parley.core.util.TlsSockets;
import us.bringardner.parley.io.CRLFLineReader;
import us.bringardner.parley.io.CRLFLineWriter;
import us.bringardner.parley.ftp.FTP;
import us.bringardner.parley.ftp.server.commands.Site;
import us.bringardner.parley.io.IoUtils;

/**
 * @author Tony Bringardner
 * This is a VERY limited client but it can be used to build more robust solutions.
 *  
 */
public class FtpClient extends SecureBaseObject implements FTP {

	public enum Permissions {
		OwnerRead('r'),
		OwnerWrite('w'),
		OwnerExecute('x'),

		GroupRead('r'),
		GroupWrite('w'),
		GroupExecute('x'),

		OtherRead('r'),
		OtherWrite('w'),
		OtherExecute('x');

	    public final char label;

	    private Permissions(char label) {
	        this.label = label;
	    }
	}

	/** The path separator FTP servers use. */
	public static final char SEPARATOR_CHAR = '/';
	/** {@link #SEPARATOR_CHAR} as a String. */
	public static final String SEPARATOR = ""+SEPARATOR_CHAR;
	/** @deprecated misspelled, use {@link #SEPARATOR_CHAR} (same value); to be removed in 2.0.0 */
	@Deprecated
	public static final char SEPERATOR_CHAR = SEPARATOR_CHAR;
	/** @deprecated misspelled, use {@link #SEPARATOR} (same value); to be removed in 2.0.0 */
	@Deprecated
	public static final String SEPERATOR = SEPARATOR;
	private static final String [] SECURE_TYPES = {"TLS","SSL"};

	/**
	 * Servers (host:port) whose data connections can't resume a TLS 1.3 session, so this JVM
	 * uses TLS 1.2 with them (BJL-28). vsftpd sends TLS 1.3 session tickets with a lifetime
	 * longer than RFC 8446 allows; Java discards them, and vsftpd (require_ssl_reuse) then
	 * refuses every data connection with "522 SSL connection failed: session reuse required".
	 */
	private static final java.util.Set<String> TLS12_SERVERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final String TLS12 = "TLSv1.2";
	private volatile boolean tls12Fallback = true;

	/**
	 * Thrown inside a transfer when the server refused the data connection's TLS session and
	 * the transfer can be retried over TLS 1.2.
	 */
	static final class Tls12Needed extends IOException {
		private static final long serialVersionUID = 1L;

		Tls12Needed(String message, Throwable cause) {
			super(message, cause);
		}
	}
	private SocketFactory socketFactory;
	private ServerSocketFactory serverSocketFactory = ServerSocketFactory.getDefault();

	private boolean requestSecure = true;
	/** "false" turns off the host name check for secure connections, see {@link #isVerifyHostname()}. */
	public static final String PROPERTY_VERIFY_HOSTNAME = "verifyHostname";
	//  null means the verifyHostname property has not been read yet
	private volatile Boolean verifyHostname;
	private boolean requireSecure = false;


	private String host;
	/** The name sent with HOST (RFC 7151) and used for TLS; null for the host connected to */
	private volatile String virtualHost;
	/** FtpClientFile.setLastModified uses MFMT when the server has it */
	private volatile boolean useModifyFacts = true;

	/** @return true (the default) if FtpClientFile.setLastModified uses MFMT when the server lists it */
	public boolean isUseModifyFacts() {
		return useModifyFacts;
	}

	/**
	 * FtpClientFile.setLastModified (and so parley-files-ftp) sets a file's time with MFMT
	 * (draft-somers-ftp-mfxx) when the server lists it in FEAT; false: always with parley's SITE command,
	 * as before. On by default.
	 * @since 1.1.0
	 */
	public void setUseModifyFacts(boolean useModifyFacts) {
		this.useModifyFacts = useModifyFacts;
	}

	/** Send ABOR when a download is closed before its end */
	private volatile boolean abortOnClose = true;
	/** Send CLNT (the client's name) to servers that list it in FEAT */
	private volatile boolean sendClientName = true;
	/** What CLNT says */
	private volatile String clientName = "parley-ftp "+clientVersion();

	private static String clientVersion() {
		Package p = FtpClient.class.getPackage();
		String v = p == null ? null : p.getImplementationVersion();
		return v == null ? "1.1.0" : v;
	}

	/** @return true (the default) if closing a download before its end sends ABOR */
	public boolean isAbortOnClose() {
		return abortOnClose;
	}

	/**
	 * Closing a download before its end sends ABOR (RFC 959), so the server stops sending the rest at once,
	 * and reads both replies (the transfer's and ABOR's). Off: the data connection is just closed, and the
	 * server finds out when its next write fails. On by default.
	 * @since 1.1.0
	 */
	public void setAbortOnClose(boolean abortOnClose) {
		this.abortOnClose = abortOnClose;
	}

	/** @return true (the default) if CLNT is sent to servers that list it in FEAT */
	public boolean isSendClientName() {
		return sendClientName;
	}

	/**
	 * CLNT (FileZilla, Serv-U and others; in no RFC): after logging in, tell a server that lists CLNT in FEAT
	 * what this client is ({@link #getClientName()}). On by default.
	 * @since 1.1.0
	 */
	public void setSendClientName(boolean sendClientName) {
		this.sendClientName = sendClientName;
	}

	/** @return what CLNT says: "parley-ftp &lt;version&gt;" unless set */
	public String getClientName() {
		return clientName;
	}

	/** @param clientName what CLNT says */
	public void setClientName(String clientName) {
		this.clientName = clientName;
	}

	/** SPSV (draft-rosenau-ftp-single-port) for passive data connections when the server has it */
	private volatile boolean useSinglePort = false;
	/** MODE Z (draft-preston-ftpext-deflate) asked for */
	private volatile boolean compression = false;
	/** MODE Z is on, on this connection */
	private volatile boolean modeZActive = false;
	/** The language asked for with LANG (RFC 2640), sent again after a reconnect; null for none */
	private volatile String language;
	/** Send HOST before logging in (RFC 7151) */
	private volatile boolean sendHost = true;
	private int port = FTP_PORT;
	private boolean useSsl;
	/**
	 * One command at a time on the control connection, and the connection state that goes
	 * with it. A lock, not synchronized methods: commands wait for the server's reply while
	 * holding it, and on Java 21-23 a virtual thread blocked inside a monitor pins its carrier
	 * thread, so many clients used from virtual threads stalled (BJL-58).
	 */
	private final ReentrantLock commandLock = new ReentrantLock();
	private volatile Socket socket;
	private volatile SSLSocket sslSocket;
	private final StringBuilder dialog = new StringBuilder();

	/**
	 * @return the commands and replies of this client so far (passwords hidden), for
	 * debugging and tests
	 */
	public String getDialog() {
		synchronized (dialog) {
			return dialog.toString();
		}
	}
	/**
	 * The representation type the server has accepted on this connection (A or I), or null
	 * when unknown; TYPE is only sent when it changes (BJL-34).
	 */
	private volatile String currentType;
	private volatile CRLFLineReader input;
	private volatile CRLFLineWriter output;
	private boolean connected = false;
	//  Socket timeout in milliseconds
	private int cmdTimeout = 60000;
	//  Socket linger in seconds (off by default, a positive value makes close() block)
	private int cmdLinger = -1;
	private int txferTimeout = 60000;
	private int transferLinger = -1;    
	private volatile String currentDir;
	private ClientFtpResponse lastResponse;

	private volatile String userId;
	private volatile String password;
	private volatile String account;
	private Map<String, String> featResponse;
	private Map<String,AutoCloseable> streamsInProcess = new HashMap<>();
	/** Try EPSV before PASV (RFC 2428). */
	private volatile boolean useEpsv = true;
	/** Set when the server rejected EPSV, so PASV is used for the rest of the session. */
	private volatile boolean epsvRejected = false;
	/** Reconnect (and retry safe commands once) when the server has closed the control connection. */
	private volatile boolean autoReconnect = true;
	/** Set by readLine() when the server closed the control connection. */
	private volatile boolean peerClosed = false;

	/**
	 * Commands that can be sent again on a new connection without changing anything on
	 * the server (after the connection was found to be closed).
	 */
	private static final java.util.Set<String> RETRY_SAFE = new java.util.HashSet<>(java.util.Arrays.asList(
			"NOOP","PWD","XPWD","CWD","CDUP","TYPE","MODE","STRU","PASV","EPSV","SIZE","MDTM",
			"FEAT","OPTS","MLST","SYST","STAT","HELP"));
	




	/**
	 * Manage concurrency <code>socketLock</code>
	 */
	private final ReentrantLock socketLock = new ReentrantLock();
	/**
	 * Manage concurrency <code>inputLock</code>
	 */
	private final ReentrantLock inputLock = new ReentrantLock();
	/**
	 * Manage concurrency <code>outputLock</code>
	 */
	private final ReentrantLock outputLock = new ReentrantLock();

	private volatile boolean mlstTested = false;
	private volatile boolean mlstSupported = false;
	private int transferBufferSize = 1024*65;
	private int socketBufferSize = 0;
	private boolean usePasvAddress = false;
	private boolean active = false;
	private volatile boolean channelSecure;
	/** true if data connections use TLS (PROT P accepted, or a legacy server without PBSZ) */
	private volatile boolean dataChannelSecure;
	private volatile boolean forceList;

	/**
	 * @return true if directory listings always use LIST even when the server supports MLSD.
	 */
	public boolean isForceList() {
		return forceList;
	}

	/**
	 * @param forceList true to always use LIST instead of MLSD for directory listings.
	 */
	public void setForceList(boolean forceList) {
		this.forceList = forceList;
	}


	/**
	 * Create a new FtpClient without specifying a host name. Use setHost instead.
	 */
	public FtpClient() {
		this.host = "Undefined";
	}

	/**
	 * Create a new FtpClient
	 * 
	 * @param host to connect to port is assumed to be 21 
	 * unless modified with the setPort method.
	 */
	public FtpClient(String host) {
		this.host = host;
	}

	/**
	 * @param host The host name to connect to 
	 * @param port The port to connect to (usually 21)
	 */
	public FtpClient(String host, int port) {
		this(host);
		this.port = port;
	}



	public SocketFactory getSocketFactory() throws IOException {
		if( socketFactory == null ) {
			if( isSecure() || isChannelSecure() ) {
				socketFactory = getSSLContext().getSocketFactory();
			} else {
				socketFactory = SocketFactory.getDefault();				
			}
		}
		return socketFactory;
	}

	public void setSocketFactory(SocketFactory socketFactory) {
		this.socketFactory = socketFactory;
	}



	public boolean isChannelSecure() {
		return channelSecure;
	}

	public boolean isUseSsl() {
		return useSsl;
	}

	public void setUseSsl(boolean useSsl) {
		this.useSsl = useSsl;
	}

	public int getTxferTimeout() {
		return txferTimeout;
	}

	public void setTxferTimeout(int txferTimeout) {
		this.txferTimeout = txferTimeout;
	}

	public String getCurrentDir() throws IOException {
		return executePwd();
	}

	public boolean setCurrentDir(String dir) throws IOException {
		return executeCwd(dir);
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public char[] getPermissions(String path) throws IOException {
		char[] ret = "---------".toCharArray();
		String[] resp= executeList(true, path);
		// should be one and only one line
		if( resp!=null && resp.length==1) {
			if( resp[0].length()>=9) {
				ret = resp[0].substring(1,10).toCharArray();
			}
		}
		return ret;
	}

	public int getUnixPermitionValue(char perms []) throws IOException {
		
		int user = ((perms[Permissions.OwnerRead.ordinal()]=='r') ? 4:0)
				| ((perms[Permissions.OwnerWrite.ordinal()]=='w') ? 2:0)
				| ((perms[Permissions.OwnerExecute.ordinal()]=='x') ? 1:0)
				;
		
		int group = ((perms[Permissions.GroupRead.ordinal()]=='r') ? 4:0)
				| ((perms[Permissions.GroupWrite.ordinal()]=='w') ? 2:0)
				| ((perms[Permissions.GroupExecute.ordinal()]=='x') ? 1:0)
				;
		int other = ((perms[Permissions.OtherRead.ordinal()]=='r') ? 4:0)
				| ((perms[Permissions.OtherWrite.ordinal()]=='w') ? 2:0)
				| ((perms[Permissions.OtherExecute.ordinal()]=='x') ? 1:0)
				;
		
		int ret = (user<<6) | (group<<3) | other;
		
		return ret;
	}
	
	public boolean canOwnerRead(String path) throws IOException {
		return getPermissions(path)[Permissions.OwnerRead.ordinal()]!='-';
	}
	public boolean canOwnerWrite(String path) throws IOException {
		return getPermissions(path)[Permissions.OwnerWrite.ordinal()]!='-';
	}
	
	public boolean canOwnerExecute(String path) throws IOException {
		return getPermissions(path)[Permissions.OwnerExecute.ordinal()]!='-';
	}
	
	public boolean canOtherRead(String path) throws IOException {
		return getPermissions(path)[Permissions.OtherRead.ordinal()]!='-';
	}
	public boolean canOtherWrite(String path) throws IOException {
		return getPermissions(path)[Permissions.OtherWrite.ordinal()]!='-';
	}
	
	public boolean canOtherExecute(String path) throws IOException {
		return getPermissions(path)[Permissions.OtherExecute.ordinal()]!='-';
	}
	
	public boolean canGroupRead(String path) throws IOException {
		return getPermissions(path)[Permissions.GroupRead.ordinal()]!='-';
	}
	public boolean canGroupWrite(String path) throws IOException {
		return getPermissions(path)[Permissions.GroupWrite.ordinal()]!='-';
	}
	
	public boolean canGroupExecute(String path) throws IOException {
		return getPermissions(path)[Permissions.GroupExecute.ordinal()]!='-';
	}
	
	public boolean setOwnerReadable(String path,boolean b) throws IOException {
		return setPermission(Permissions.OwnerRead, b, path);
	}
	
	public boolean setOwnerWritable(String path,boolean b) throws IOException {
		return setPermission(Permissions.OwnerWrite, b, path);
	}
	
	public boolean setOwnerExecutable(String path,boolean b) throws IOException {
		return setPermission(Permissions.OwnerExecute, b, path);
	}
	
	
	public boolean setGroupReadable(String path,boolean b) throws IOException {
		return setPermission(Permissions.GroupRead, b, path);
	}
	
	public boolean setGroupWritable(String path,boolean b) throws IOException {
		return setPermission(Permissions.GroupWrite, b, path);
	}

	public boolean setGroupExecutable(String path,boolean b) throws IOException {
		return setPermission(Permissions.GroupExecute, b, path);
	}
	
	public boolean setOtherReadable(String path,boolean b) throws IOException {
		return setPermission(Permissions.OtherRead, b, path);
	}
	
	public boolean setOtherWritable(String path,boolean b) throws IOException {
		return setPermission(Permissions.OtherWrite, b, path);
	}
	
	public boolean setOtherExecutable(String path,boolean b) throws IOException {
		return setPermission(Permissions.OtherExecute, b, path);
	}
	
	private boolean setPermission(Permissions p, boolean b,String path) throws IOException {
		int idx = p.ordinal();
		// get the current permissions
		char perms [] = getPermissions(path);
		char label = b ? p.label:'-';
		boolean ret = perms[idx] == label;
		// nothing to do if it's already set
		if( !ret ) {
			perms[idx] = label;
			int val = getUnixPermitionValue(perms);
			String arg = Integer.toOctalString(val);
			ClientFtpResponse resp = executeCommand(FTP.SITE, Site.CMD_CHMOD,arg,path);
			ret = resp.isPositiveComplet();						
		}
		
		return ret;
	}


	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}


	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	/**
	 * Size (bytes) of the stream buffers used to copy transfer data (at least 8 KB).
	 * It no longer sets the data sockets' TCP buffers, see {@link #setSocketBufferSize(int)}.
	 */
	public void setTransferBufferSize(int transferBufferSize) {
		this.transferBufferSize = transferBufferSize;
	}

	/**
	 * @return true (the default) to switch to TLS 1.2 with a server that refuses data
	 * connections because they can't resume the TLS 1.3 session (BJL-28)
	 */
	public boolean isTls12Fallback() {
		return tls12Fallback;
	}

	/**
	 * When true (the default) and a server refuses a protected data connection because its
	 * TLS 1.3 session wasn't resumed (vsftpd's require_ssl_reuse, see BJL-28), the client
	 * reconnects with TLS 1.2, retries the transfer once, and uses TLS 1.2 with that server
	 * (host and port) for the rest of the JVM's life. It never goes below TLS 1.2, and logs
	 * when it happens. Set false to get the error instead.
	 */
	public void setTls12Fallback(boolean tls12Fallback) {
		this.tls12Fallback = tls12Fallback;
	}

	/** @return true if this JVM uses TLS 1.2 with the server because of a fallback */
	public static boolean isTls12Server(String host, int port) {
		return TLS12_SERVERS.contains(serverKey(host, port));
	}

	/**
	 * Use TLS 1.2 with this server from the next connection on, as if a fallback had happened
	 * (for a server known to need it, e.g. vsftpd with require_ssl_reuse).
	 */
	public static void addTls12Server(String host, int port) {
		TLS12_SERVERS.add(serverKey(host, port));
	}

	/** Forget the servers that needed a fallback (tests, or after the server is fixed). */
	public static void clearTls12Servers() {
		TLS12_SERVERS.clear();
	}

	private static String serverKey(String host, int port) {
		return (host == null ? "" : host.toLowerCase(java.util.Locale.ROOT))+":"+port;
	}

	/** Before a new connection: TLS 1.2 for a server known to need it. */
	private void applyTls12Fallback() {
		if( tls12Fallback && isTls12Server(getHost(), getPort()) && !TLS12.equals(getProtocol()) ) {
			logInfo("Using "+TLS12+" with "+getHost()+":"+getPort()+": its data connections can't resume TLS 1.3 sessions");
			useTls12();
		}
	}

	private void useTls12() {
		setProtocol(TLS12);
		// factories built from the old context
		socketFactory = null;
		serverSocketFactory = ServerSocketFactory.getDefault();
	}

	/** @return the control connection's TLS version, or null if it isn't encrypted */
	String controlTlsProtocol() {
		SSLSocket ssl = sslSocket;
		if( ssl == null && socket instanceof SSLSocket ) {
			ssl = (SSLSocket) socket;
		}
		return ssl == null ? null : ssl.getSession().getProtocol();
	}

	/**
	 * Called after the 1xx reply to a transfer command: completes the data connection's TLS
	 * handshake now, so a refused session is noticed before a stream is handed out and the
	 * transfer can be retried (BJL-28).
	 * @return null normally; the final (2xx) reply if the server already finished the transfer
	 * without TLS (some servers, and this project's before BJL-28, close an empty transfer's
	 * data connection without a handshake)
	 */
	ClientFtpResponse startDataTls(ClientDataTransferProcess dtp) throws IOException {
		if( !isDataChannelSecure() ) {
			return null;
		}
		Socket s = dtp.getSocket();
		if( !(s instanceof SslEngineSocket) ) {
			return null;
		}
		try {
			((SslEngineSocket) s).startHandshake();
			return null;
		} catch (IOException e) {
			dtp.close();
			ClientFtpResponse res = null;
			try {
				res = readResponse();
			} catch (IOException e2) {
				abandonConnection();
			}
			if( res != null && res.isPositiveComplet() ) {
				// an empty transfer
				return res;
			}
			if( res != null && isTlsResumeRefusal(res) && tls12Fallback && "TLSv1.3".equals(controlTlsProtocol()) ) {
				TLS12_SERVERS.add(serverKey(getHost(), getPort()));
				throw new Tls12Needed("Server refused the TLS 1.3 data connection: "+res, e);
			}
			throw new IOException("TLS handshake on the data connection failed"+(res == null ? "" : ", server said: "+res), e);
		}
	}

	/**
	 * A transfer's final reply says the data connection's TLS session wasn't resumed, after
	 * the handshake itself had succeeded (FileZilla Server checks then). Remember the server
	 * for TLS 1.2 and drop the connection, so the next command reconnects with TLS 1.2.
	 * @return true if it was such a refusal
	 */
	boolean noteTlsResumeRefusal(ClientFtpResponse res) {
		if( res != null && isTlsResumeRefusal(res) && tls12Fallback && "TLSv1.3".equals(controlTlsProtocol()) ) {
			TLS12_SERVERS.add(serverKey(getHost(), getPort()));
			abandonConnection();
			return true;
		}
		return false;
	}

	/**
	 * After a data connection carried nothing: if the reply is a TLS resumption refusal that
	 * TLS 1.2 can fix, remember the server and throw Tls12Needed so the caller retries.
	 */
	void checkTlsResumeRefusal(ClientFtpResponse res) throws Tls12Needed {
		if( res != null && isTlsResumeRefusal(res) && tls12Fallback && "TLSv1.3".equals(controlTlsProtocol()) ) {
			TLS12_SERVERS.add(serverKey(getHost(), getPort()));
			throw new Tls12Needed("Server refused the TLS 1.3 data connection: "+res, null);
		}
	}

	/** 522 (RFC 4217: data connection protection refused), or 425/450/451 about TLS resumption */
	static boolean isTlsResumeRefusal(ClientFtpResponse res) {
		int code = res._getResponseCode();
		String text = String.valueOf(res.getResponseText()).toLowerCase(java.util.Locale.ROOT);
		boolean aboutResume = text.contains("resum") || text.contains("reuse");
		return code == 522 || ((code == 425 || code == 450 || code == 451) && aboutResume);
	}

	/** Reconnect over TLS 1.2 (same user, same directory) after a Tls12Needed. */
	private void fallBackToTls12(Tls12Needed e) throws IOException {
		logInfo(e.getMessage()+"; reconnecting to "+getHost()+":"+getPort()+" with "+TLS12+" and retrying");
		String dir = currentDir;
		abandonConnection();
		useTls12();
		currentDir = dir;
		reconnect();
	}

	/**
	 * TCP buffer size (SO_RCVBUF / SO_SNDBUF) for data connections, in bytes.
	 * <p>
	 * 0 (the default) leaves them to the operating system, which grows them as a transfer
	 * needs (TCP autotuning, several MB on Linux, macOS and Windows). Setting a size turns
	 * autotuning off for that connection, and throughput is then limited to about
	 * size / round-trip time whatever the link speed: 64 KB at 50 ms is about 1.3 MB/s.
	 * Only set it to limit memory or bandwidth, or for an OS without autotuning (BJL-29;
	 * this used to be fixed at the transfer buffer size, 65 KB).
	 * @param socketBufferSize bytes, or 0 for the operating system's default
	 */
	public void setSocketBufferSize(int socketBufferSize) {
		if( socketBufferSize < 0 ) {
			throw new IllegalArgumentException("socketBufferSize must be >= 0");
		}
		this.socketBufferSize = socketBufferSize;
	}

	/** @return the data connections' TCP buffer size, 0 for the operating system's default */
	public int getSocketBufferSize() {
		return socketBufferSize;
	}

	/**
	 * @return soLinger value for the Command Socket (in seconds)
	 */
	public int getCmdLinger() {
		return cmdLinger;
	}

	/**
	 * Set the soLinger value for the Command socket (in seconds)
	 * @param cmdLinger
	 */
	public void setCmdLinger(int cmdLinger) {
		this.cmdLinger = cmdLinger;
	}

	/**
	 * @return soLinger value for the Transfer Socket (in seconds)
	 */
	public int getTransferLinger() {
		return transferLinger;
	}

	/**
	 * Set the soLinger value for the Transfer socket (in seconds)
	 * @param transferLinger
	 */
	public void setTransferLinger(int transferLinger) {
		this.transferLinger = transferLinger;
	}

	/**
	 * @return The host name of the FTP Server.
	 */
	public String getHost() {
		return host;
	}


	/**
	 * @param host (The host name of the FTP Server)
	 */
	public void setHost(String host) {
		this.host = host;
	}

	public String getAccount() {
		return account;
	}

	public void setAccount(String account) {
		this.account = account;
	}

	/**
	 * @return The CRLFLineReader assigned to the command channel.
	 * 
	 * @throws UnknownHostException
	 * @throws IOException
	 */
	public CRLFLineReader getInput() throws UnknownHostException, IOException {
		if( input == null ) {
			inputLock.lock();
			try {
				if(input == null ) {
					input = new CRLFLineReader(getSocket().getInputStream());
				}
			} finally {
				inputLock.unlock();
			}
		}
		return input;
	}


	/**
	 * @return The CRLFLineWriter assigned to the Command channel.
	 * 
	 * @throws IOException
	 * 
	 */
	public CRLFLineWriter getOutput() throws IOException {
		if( output == null ) {
			outputLock.lock();
			try {
				if( output == null ) {
					output = new CRLFLineWriter(getSocket().getOutputStream());
				}
			} finally {
				outputLock.unlock();
			}
		}
		return output;
	}


	/**
	 * @return The port currenty defined for this connection.
	 */
	public int getPort() {
		return port;
	}

	/**
	 * @param port to connect to
	 */
	public void setPort(int port) {
		this.port = port;
	}

	/**
	 * @return A Socket connected to the current host and port.
	 * 
	 * @throws IOException
	 */
	private Socket getSocket() throws IOException {
		if( socket == null ) {
			socketLock.lock();
			try {
				if( socket == null ) {
					sslSocket = null;// just in case :-)
					String host = getHost();
					int port = getPort();
					logDebug("Attempt connect to "+host+":"+port);
					int timeout = getCmdTimeout();
					int linger = getCmdLinger();
					// Unconnected first so we can use a connect timeout
					Socket tmp = getSocketFactory().createSocket();
					try {
						if( tmp instanceof SSLSocket ) {
							//  Implicit TLS: SNI and the host name check, before the handshake
							TlsSockets.configureClient((SSLSocket) tmp, tlsName(), isVerifyHostname());
						}
						tmp.setKeepAlive(true);
						tmp.setTcpNoDelay(true);
						tmp.connect(new java.net.InetSocketAddress(host, port), timeout);
						tmp.setSoTimeout(timeout);
						if( linger > 0 ) {
							tmp.setSoLinger(true, linger);
						}
					} catch (IOException e) {
						IoUtils.closeQuietly(tmp);
						throw e;
					}
					logDebug("Connected to "+host+":"+port+" timeout="+timeout+" linger = "+linger);
					socket = tmp;
				}				
			} finally {
				socketLock.unlock();
			}
		}

		if( sslSocket == null ) {
			return socket;
		} else {
			return sslSocket;
		}
	}



	/**
	 * Write one line of text to the command channel.
	 *  
	 * @param line 
	 * @throws IOException
	 */
	public void writeLine(String line) throws IOException {
		String safe = maskCredentials(line);
		if( isDebugEnabled() ) {
			logDebug(""+Thread.currentThread().hashCode()+" Write:"+safe);
		}
		CRLFLineWriter out = getOutput();
		out.writeLine(line);
		out.flush();
		appendDialog(" Write:"+safe);
	}

	/** Maximum number of characters kept in {@link #dialog}. */
	public static final int MAX_DIALOG_SIZE = 64 * 1024;

	/**
	 * Replace the argument of a PASS command so passwords never reach logs or the dialog.
	 */
	static String maskCredentials(String line) {
		if( line != null && line.length() >= 4 && line.regionMatches(true, 0, PASS, 0, 4)
				&& (line.length() == 4 || line.charAt(4) == ' ') ) {
			return PASS+" ****";
		}
		return line;
	}

	private void appendDialog(String text) {
		synchronized (dialog) {
			dialog.append(Thread.currentThread().hashCode()).append(text).append('\n');
			if( dialog.length() > MAX_DIALOG_SIZE ) {
				// keep the most recent half
				dialog.delete(0, dialog.length() - MAX_DIALOG_SIZE/2);
			}
		}
	}

	/**
	 * @return one line of text read from the command channel.
	 * 
	 * @throws IOException
	 */
	protected String readLine() throws IOException {
		String ret = getInput().readLine();
		if( ret == null ) {
			peerClosed = true;
		}
		if( isDebugEnabled() ) {
			logDebug(""+Thread.currentThread().hashCode()+" Read: "+ret);
		}
		appendDialog(" Read: "+ret);
		return ret;
	}

	/**
	 * Drop the control connection without sending QUIT. Used when the conversation with
	 * the server is out of step (e.g. a reply timed out); the next command reconnects.
	 */
	void abandonConnection() {
		commandLock.lock();
		try {
			Socket s = socket;
			socket = null;
			sslSocket = null;
			input = null;
			output = null;
			connected = false;
			mlstTested = false;
			currentType = null;
			featResponse = null;
			if( !isSecure() && channelSecure ) {
				setSocketFactory(SocketFactory.getDefault());
				setServerSocketFactory(ServerSocketFactory.getDefault());
			}
			channelSecure = false;
			dataChannelSecure = false;
			if( s != null ) {
				IoUtils.closeQuietly(s);
			}
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * Close the connect.  If connected a Quit command is send to the server.
	 */
	public void close() {
		commandLock.lock();
		try {
			if(connected ) {
				try {
					ClientFtpResponse res = sendCommand(QUIT);
					if( !res.isPositiveComplet()) {
						logDebug("Invalid resp from quit ="+res);
					}
				} catch(Exception ex) {}
			}

			if( socket != null ) {
				int linger = getCmdLinger();
				if( linger > 0 ) {
					try {
						socket.setSoLinger(true, linger);
						/*
						 *  The timeout overrides the linger, 
						 *  Set the timeout to 1sec longer than linger
						 */
						socket.setSoTimeout((linger*1000)+1000);
					} catch (SocketException e) {
					}

				}
				IoUtils.closeQuietly(socket);
			}
			socket = null;
			input = null;
			output = null;
			mlstTested = false;
			connected = false;
			currentType = null;
			if( !isSecure() && channelSecure) {
				//  reset these to defaults.
				setSocketFactory(SocketFactory.getDefault());
				setServerSocketFactory(ServerSocketFactory.getDefault());
			}
			// A new connection must negotiate AUTH again. Previously channelSecure stayed true,
			// so a reconnect skipped AUTH and sent USER/PASS in clear text.
			channelSecure = false;
			dataChannelSecure = false;
			sslSocket = null;
			// a new session starts in the server's default directory
			currentDir = null;
			epsvRejected = false;
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * Send a command to the server and read the response.
	 * 
	 * @param command
	 * @return The ClientFtpResponse from the server
	 * @throws UnknownHostException
	 * @throws IOException
	 * 
	 */
	public ClientFtpResponse executeCommand(String command) throws  IOException {
		commandLock.lock();
		try {
			String name = firstToken(command);
			if( !streamsInProcess.isEmpty() ) {
				// One control connection can only run one transfer; interleaving commands with
				// an open stream reads the wrong replies.
				throw new IOException("A transfer is in progress ("+streamsInProcess.keySet()+"); close its stream before sending "
						+name+", or use a separate FtpClient for concurrent transfers.");
			}
			boolean wasConnected = connected;
			if( !connected ) {
				reconnect();
			}

			peerClosed = false;
			IOException failure = null;
			ClientFtpResponse res = null;
			try {
				res = sendCommand(command);
			} catch (java.net.SocketTimeoutException e) {
				throw e;
			} catch (IOException e) {
				failure = e;
			}
			if( failure == null && !peerClosed ) {
				return res;
			}

			// The server closed the connection (e.g. idle timeout) or the network failed.
			abandonConnection();
			if( autoReconnect && wasConnected && RETRY_SAFE.contains(name) ) {
				logInfo("Control connection to "+getHost()+" was closed, reconnecting to retry "+name);
				reconnect();
				return sendCommand(command);
			}
			if( failure != null ) {
				throw new IOException("Connection to "+getHost()+" lost during "+name+"; it will be reopened on the next command", failure);
			}
			return res; // the 421 left by a closed connection
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * Connect again with the saved credentials and restore the current directory.
	 */
	private void reconnect() throws IOException {
		String dir = currentDir;
		if( !connect(userId,password,account) ) {
			throw new IOException("Can't reconnect to "+getHost()+":"+getPort()+" as "+userId);
		}
		if( dir != null ) {
			ClientFtpResponse res = sendCommand(CWD+" "+dir);
			if( res.isPositiveComplet() ) {
				currentDir = dir;
			} else {
				logError("Can't restore directory "+dir+" after reconnect: "+res);
				currentDir = null;
			}
		}
	}

	private static String firstToken(String command) {
		String c = command.trim();
		int idx = c.indexOf(' ');
		return (idx < 0 ? c : c.substring(0, idx)).toUpperCase(java.util.Locale.ROOT);
	}

	/**
	 * @return true (default) to reconnect and retry safe commands once when the server
	 * has closed the control connection.
	 */
	public boolean isAutoReconnect() {
		return autoReconnect;
	}

	public void setAutoReconnect(boolean autoReconnect) {
		this.autoReconnect = autoReconnect;
	}

	/**
	 * @return true (default) to try EPSV before PASV.
	 */
	public boolean isUseEpsv() {
		return useEpsv;
	}

	/**
	 * @param useEpsv true (default) to use EPSV (RFC 2428, needed for IPv6) and fall back
	 * to PASV when the server doesn't support it; false to always use PASV.
	 */
	public void setUseEpsv(boolean useEpsv) {
		this.useEpsv = useEpsv;
	}

	boolean isEpsvRejected() {
		return epsvRejected;
	}

	void setEpsvRejected(boolean rejected) {
		this.epsvRejected = rejected;
	}

	/**
	 * @return the local address of the control connection (used for PORT/EPRT).
	 */
	java.net.InetAddress getControlLocalAddress() throws IOException {
		return getSocket().getLocalAddress();
	}

	/**
	 * This is used by all commands and it does not requires we're currently logged in 
	 * @param command
	 * @return ClientFtpResponse 
	 * @throws IOException
	 */
	private ClientFtpResponse sendCommand(String command) throws  IOException {
		String name = firstToken(command);
		if( name.equals(TYPE) || name.equals("REIN") ) {
			// set again by executeType when it succeeds; a raw TYPE or a REIN resets it
			currentType = null;
		}
		writeLine(command);        
		lastResponse = readResponse(); 
		return lastResponse;
	}
	/**
	 * Send a command with one argument to the server
	 * 
	 * @param command 
	 * @param arg
	 * @return The ClientFtpResponse from the server
	 * @throws UnknownHostException
	 * @throws IOException
	 */
	public ClientFtpResponse executeCommand(String ... args) throws IOException {
		StringBuilder buf = new StringBuilder();
		for (int idx = 0; idx < args.length; idx++) {
			if(idx > 0 ) {
				buf.append(' ');
			}
			buf.append(args[idx]);
		}
		return executeCommand(buf.toString());
	}

	/**
	 * Connect using the specified userId and password.
	 * 
	 * @param userId
	 * @param passwd
	 * @return true if the client is able to connect to the server.
	 * @throws UnknownHostException
	 * @throws IOException
	 */
	public boolean connect(String userId, String passwd, String account) throws IOException {
		commandLock.lock();
		try {
			if( !connected ) {
				if( socket == null ) {
					applyTls12Fallback();
				}
				mlstTested = false;
				currentType = null;
				this.userId = userId;
				this.password = passwd;
				this.account = account;
				logDebug("userid="+userId+" account="+account);

				// connecting a socket will trigger the server to send us a greeting line
				ClientFtpResponse res = readResponse();
				if( res.isPositiveComplet() ) {
					login();
				}

				//  All done, if we're not connected we need to close socket
				if( !connected) {
					try {
						close();	
					} catch (Exception e) {
					}
				}
			}    


			return connected;
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * After the greeting (or REIN's 220): HOST, AUTH, USER, PASS, ACCT, then what follows a login.
	 * @return true if logged in (connected is set)
	 */
	private boolean login() throws IOException {
		if( isSendHost() && !executeHost() ) {
			return false;
		}
		if( !isSecure() ) {
			boolean ok = executeAuth();
			if( isRequireSecure() && !ok) {
				return false;
			}
		}

		ClientFtpResponse res = sendCommand(USER+" "+userId);

		if( res.isPositiveIntermediate()) {
			res = sendCommand(PASS+" "+password);
			// 332 asks for an account (RFC 959). A server may also answer 530 and
			// accept the account in a following ACCT, as the BJL server does
			// (user@account logins), so a configured account is tried once either way.
			int code = res._getResponseCode();
			if( account != null && !account.isEmpty()
					&& (code == REPLY_332_NEED_ACCOUNT || code == REPLY_530_USER_NOT_LOGGED_IN)) {
				res = sendCommand(ACCT+" "+account);
			}
		}

		if( res.isPositiveComplet()) {
			connected = true;
			if( isSecure() || isChannelSecure() ) {
				negotiateDataProtection();
			}
			afterLogin();
		}
		return connected;
	}

	/**
	 * REIN (RFC 959): the server ends the login and resets the session, then this client logs in again
	 * with the same user (and HOST, AUTH TLS, PBSZ/PROT, LANG, MODE Z, CLNT as set), on the same control
	 * connection. After AUTH TLS the server ends TLS first (RFC 4217 section 13), and it is negotiated
	 * again.
	 *
	 * @return true if logged in again
	 * @since 1.1.0
	 */
	public boolean reinitialize() throws IOException {
		commandLock.lock();
		try {
			checkStreamInProcess("REIN");
			ClientFtpResponse res = sendCommand(REIN);
			// RFC 959: 120 (ready in a while), then 220
			while( res._getResponseCode() / 100 == 1 ) {
				res = readResponse();
			}
			if( res._getResponseCode() != REPLY_220_READY_NEW_USER ) {
				logError("REIN: "+res);
				return false;
			}
			if( isChannelSecure() && !isSecure() ) {
				endControlTls();
			}
			connected = false;
			dataChannelSecure = false;
			mlstTested = false;
			currentType = null;
			currentDir = null;
			modeZActive = false;
			if( login() ) {
				return true;
			}
			// logged out on an open connection: drop it, so the next command connects again
			abandonConnection();
			return false;
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * CCC (RFC 2228, RFC 4217): the control connection goes back to clear text (for a firewall or NAT
	 * that must read it, e.g. to open data ports), with TLS shut down both ways; data connections stay
	 * protected as PROT set them.
	 * @return true if the server cleared it; false if it refused, or the control connection isn't
	 * protected with AUTH TLS (implicit TLS can't be cleared)
	 * @since 1.1.0
	 */
	public boolean clearCommandChannel() throws IOException {
		commandLock.lock();
		try {
			if( !isChannelSecure() || isSecure() ) {
				return false;
			}
			ClientFtpResponse res = sendCommand(CCC);
			if( !res.isPositiveComplet() ) {
				return false;
			}
			endControlTls();
			return true;
		} finally {
			commandLock.unlock();
		}
	}

	/** Ends TLS on the control connection, close_notify both ways (RFC 4217 section 12.3), then clear text */
	private void endControlTls() {
		SSLSocket ssl = sslSocket;
		if( ssl == null ) {
			return;
		}
		try {
			ssl.shutdownOutput();
		} catch (IOException | UnsupportedOperationException e) {
			logDebug("Can't send close_notify", e);
		}
		try {
			ssl.setSoTimeout(5000);
			InputStream in = ssl.getInputStream();
			while( in.read() >= 0 ) {
				// nothing should come but the server's close_notify
			}
		} catch (IOException e) {
			logDebug("No close_notify from the server", e);
		}
		sslSocket = null;
		channelSecure = false;
		input = null;
		output = null;
		if( !isSecure() ) {
			// the next control connection (a reconnect) starts plain again; PROT P keeps the server
			// socket factory for active data connections
			setSocketFactory(SocketFactory.getDefault());
		}
		try {
			socket.setSoTimeout(getCmdTimeout());
		} catch (IOException e) {
			logDebug("Can't set the timeout", e);
		}
	}

	/**
	 * STAT (RFC 959): the server's status of this session (or of a transfer in progress).
	 * @return the reply's text, its lines separated by '\n'
	 * @since 1.1.0
	 */
	public String status() throws IOException {
		return executeCommand(STAT).getResponseText();
	}

	/**
	 * STAT path (RFC 959): what LIST would send for the path, on the control connection instead of a data
	 * connection (no data connection is opened, which helps through some firewalls).
	 * @return the listing's lines; empty if the server says nothing about it
	 * @since 1.1.0
	 */
	public String[] status(String path) throws IOException {
		ClientFtpResponse res = executeCommand(STAT, path);
		java.util.List<String> ret = new ArrayList<String>();
		int code = res._getResponseCode();
		if( code == REPLY_211_SYSTEM_STATUS || code == REPLY_212_DIR_STATUS || code == REPLY_213_FILE_STATUS ) {
			String[] lines = res.getResponseText().split("\n");
			// the first and last lines are the server's own text
			for(int i=1; i < lines.length-1; i++ ) {
				if( !lines[i].trim().isEmpty() ) {
					ret.add(lines[i].trim());
				}
			}
		}
		return ret.toArray(new String[ret.size()]);
	}

	/**
	 * NLST (RFC 959): the names in a remote directory (or the current one), on a data connection.
	 * @param path the directory, or null for the current one
	 * @return the names as the server sends them; empty if there are none (450/550)
	 * @since 1.1.0
	 */
	public String[] nameList(String path) throws IOException {
		try {
			return nameListOnce(path);
		} catch (Tls12Needed e) {
			fallBackToTls12(e);
			return nameListOnce(path);
		}
	}

	private String[] nameListOnce(String path) throws IOException {
		commandLock.lock();
		try {
			checkStreamInProcess(path == null ? "NLST" : path);
			ClientDataTransferProcess dtp = getDataTransferProcess();
			java.util.List<String> ret = new ArrayList<String>();
			try {
				dtp.connectBeforeCommand();
				ClientFtpResponse res = path == null || path.isEmpty() ? executeCommand(NLST) : executeCommand(NLST, path);
				if( !res.isPositivePreliminay() ) {
					if( res._getResponseCode() == REPLY_450_FILE_ACTION_FAILED || res._getResponseCode() == REPLY_550_ACTION_NOT_TAKEN ) {
						return new String[0];
					}
					throw new IOException("NLST "+(path == null ? "" : path)+": "+res);
				}
				if( startDataTls(dtp) == null ) {
					try (CRLFLineReader in = new CRLFLineReader(isModeZActive() ? inflate(dtp.getInput()) : dtp.getInput())) {
						String line;
						while( (line = in.readLine()) != null ) {
							if( !line.trim().isEmpty() ) {
								ret.add(line);
							}
						}
					}
					dtp.close();
					res = readResponse();
					if( !res.isPositiveComplet() ) {
						throw new IOException("NLST "+(path == null ? "" : path)+": "+res);
					}
				}
			} finally {
				dtp.close();
			}
			return ret.toArray(new String[ret.size()]);
		} finally {
			commandLock.unlock();
		}
	}

	protected FtpClient getNewConnection() throws  IOException {
		FtpClient ret = new FtpClient(getHost(),getPort());
		ret.useSsl = useSsl;
		ret.virtualHost = virtualHost;
		ret.language = language;
		ret.compression = compression;
		ret.useSinglePort = useSinglePort;
		ret.abortOnClose = abortOnClose;
		ret.useModifyFacts = useModifyFacts;
		ret.sendClientName = sendClientName;
		ret.clientName = clientName;
		ret.sendHost = sendHost;
		ret.connect(userId,password,account);

		//  The response should always be the same so we only need to do this once.
		ret.featResponse = getFeatResponse();
		return ret;

	}

	/**
	 * Local helper to parse a directory name from the 
	 * response text of an FTP command (PWD)
	 * 
	 * @param dirName the text from a FTP response
	 * @return a directory name parsed from the text
	 */
	private String parserDirectoryName(String dirName) {
		/*
		 * The next line is a typical response.  We need to pull the data out. 
		 * "/path/path" is the current directory
		 */
		String ret = dirName;
		int idx1 = ret.indexOf('"');
		if( idx1 > 0 ) {
			int idx2 = ret.indexOf('"',++idx1);
			if( idx2 > 0 ) {
				ret = ret.substring(idx1,idx2);
			}
		}
		return ret;

	}

	/**
	 * 
	 * Execute the FTP CWD Command
	 * 
	 * @param dirName
	 * @return true if the command succeed
	 * @throws IOException
	 * @throws UnknownHostException
	 */
	public boolean executeCwd(String dirName) throws IOException {
		commandLock.lock();
		try {

			ClientFtpResponse res = executeCommand(CWD,dirName); 
			boolean ret = res.isPositiveComplet();
			// Force a PWD to get the correct value (also needed to restore it after a reconnect)
			currentDir = null;
			if( ret ) {
				executePwd();
			}
			return ret;
		} finally {
			commandLock.unlock();
		}
	}


	public boolean setImageType() throws IOException {
		return executeType(TYPE_IMAGE);
	}

	public boolean setAsciiType() throws IOException {
		return executeType(TYPE_ASCII);
	}

	/**
	 * Sends TYPE only when the type changes: it was sent before every transfer, an extra round
	 * trip per file (BJL-34). The remembered type is forgotten when the connection is closed,
	 * dropped or reopened, after REIN or a TYPE sent with executeCommand, and when TYPE fails.
	 */
	private boolean executeType(String type) throws IOException {
		commandLock.lock();
		try {
			if( connected && type.equals(currentType) ) {
				return true;
			}
			ClientFtpResponse res = executeCommand(TYPE,type);
			boolean ret = res.isPositiveComplet();
			// executeCommand may have reconnected; the type is set on the connection in use now
			currentType = ret ? type : null;

			return ret;
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * Execute the CDUP Command 
	 * @return true is successful
	 * @throws IOException
	 */
	public boolean executeCdup() throws IOException {
		ClientFtpResponse res = executeCommand(CDUP);
		currentDir=null;
		boolean ret = res.isPositiveComplet();
		if( ret ) {
			executePwd();
		}

		return ret;
	}


	/**
	 * @return The current working directory of the FTP session 
	 * @throws IOException
	 */
	public String executePwd() throws IOException {

		if( currentDir == null ) {

			ClientFtpResponse res = executeCommand(PWD);
			if(res.isPositiveComplet()) {
				currentDir = parserDirectoryName(res.getResponseText());
			} else {
				logError("Can't change directory response = "+res);
			}
		}

		return currentDir;
	}

	/*
	 * 250 CWD command successful.
/hold/TDM/config/Tdm-14.0.11  loaded from [Directory Listing Cache]DIR3D.tmp
PWD
257 "/hold/TDM/config/Tdm-14.0.11" is current directory.
TYPE A
200 Type set to A; form set to N.
PASV
227 Entering Passive Mode (10,129,15,18,200,135)
connecting data channel to 10.129.15.18:51335
data channel connected to 10.129.15.18:51335
LIST
150 Opening data connection for /bin/ls.
transferred 3358 bytes in 0.016 seconds, 1679.000 Kbps ( 209.875 KBps), transfer succeeded.

	 */


	/**
	 * @return timewout value used for the command socket. 
	 */
	public int getCmdTimeout() {
		return cmdTimeout;
	}


	/**
	 * Set the timeout values used for the command socket.
	 * @param cmdTimeout in milliseconds.
	 */
	public void setCmdTimeout(int cmdTimeout) {
		this.cmdTimeout = cmdTimeout;
	}


	/**
	 * @return true is currently connected to a server.
	 */
	public boolean isConnected() {
		commandLock.lock();
		try {
			return connected;
		} finally {
			commandLock.unlock();
		}
	}


	/**
	 * Execute the SIZE command
	 * @param path file name
	 * @return size of the file or -1 if size is not available (maybe file does not exists).
	 * @throws IOException
	 */
	public long executeSize(String path) throws IOException {
		long ret = -1;
		ClientFtpResponse resp = executeCommand(SIZE+" "+path);
		if( resp.isPositiveComplet()) {
			String tmp = resp.getResponseText().trim();
			try {
				ret = Long.parseLong(tmp);	
			} catch (NumberFormatException e) {
				logDebug("SIZE reply is not a number: "+tmp);
			}
		}

		return ret;
	}

	/**
	 * RFC 4217: after logging in on a secure control connection, ask for protected data
	 * connections (PBSZ 0, PROT P). If the server refuses PROT P, data connections are clear.
	 * A server that doesn't know PBSZ at all is treated as a legacy server that always uses
	 * TLS for data after AUTH (older versions of this project's server).
	 */
	private void negotiateDataProtection() throws IOException {
		ClientFtpResponse res = sendCommand(PBSZ+" 0");
		if( !res.isPositiveComplet() ) {
			logDebug("Server does not support PBSZ ("+res+"), assuming protected data connections");
			dataChannelSecure = true;
			return;
		}
		res = sendCommand(PROT+" P");
		dataChannelSecure = res.isPositiveComplet();
		if( !dataChannelSecure ) {
			logInfo("Server refused PROT P ("+res+"), data connections will not be encrypted");
		}
	}

	/**
	 * @return true if data connections are encrypted.
	 */
	public boolean isDataChannelSecure() {
		return dataChannelSecure;
	}

	/**
	 * @return the SocketFactory for passive data connections: TLS when the data channel is
	 * protected, plain otherwise.
	 */
	public SocketFactory getDataSocketFactory() throws IOException {
		return dataChannelSecure ? getSocketFactory() : SocketFactory.getDefault();
	}

	/**
	 * Puts TLS on a connected data socket, as the TLS client (RFC 4217), so that it resumes
	 * the control connection's TLS session, which RFC 4217 recommends and servers such as
	 * vsftpd (require_ssl_reuse) and FileZilla Server require (BJL-18).
	 * <p>
	 * Java looks a client session up by host and port. An SSLSocket always uses the port it
	 * is connected to (the data port), so it never found the control session; an SSLEngine
	 * uses the host and port it is created with, so the data connection runs through one
	 * created with the control connection's. It must come from the control connection's
	 * SSLContext, which holds the session.
	 *
	 * @param plain a connected, plain data socket; closed with the returned socket
	 * @return the TLS socket; the handshake happens on first use
	 */
	public Socket secureDataSocket(Socket plain) throws IOException {
		// the name the control connection's session is kept under: the one connected to with implicit
		// TLS, the one TLS was layered with after AUTH
		SSLEngine engine = getSSLContext().createSSLEngine(isSecure() ? getHost() : tlsName(), getPort());
		SslEngineSocket ssl = new SslEngineSocket(plain, engine);
		ssl.setSoTimeout(plain.getSoTimeout());
		return ssl;
	}

	/**
	 * @return the ServerSocketFactory for active data connections.
	 */
	public ServerSocketFactory getDataServerSocketFactory() throws IOException {
		return dataChannelSecure ? getServerSocketFactory() : ServerSocketFactory.getDefault();
	}

	/**
	 * @return ServerSocketFactory used to create ServerSockets 
	 * @throws IOException 
	 */
	public ServerSocketFactory getServerSocketFactory() throws IOException {
		if( serverSocketFactory == null ) {
			if( isSecure()) {
				serverSocketFactory = getSSLContext().getServerSocketFactory();
			} else {
				serverSocketFactory = ServerSocketFactory.getDefault();
			}
		}
		return serverSocketFactory;
	}

	/**
	 * @param serverSocketFactory Factory to use when creating ServerSockets
	 */
	public void setServerSocketFactory(ServerSocketFactory serverSocketFactory) {
		this.serverSocketFactory = serverSocketFactory;
	}



	/**
	 * @return timeout used when transferring data (in milliseconds)
	 */
	public int getTransferTimeout() {
		return txferTimeout;
	}

	/**
	 * @param timeout value used when transferring data (in milliseconds)
	 */
	public void setTransferTimeout(int timeout) {
		txferTimeout = timeout;
	}


	/**
	 * @return The list of file entries for the current directory.
	 * @throws IOException
	 */
	public String[] executeList() throws IOException {
		return executeList(
				executePwd()
				);    
	}

	public boolean isMlstSupported() throws IOException {

		if( !mlstTested ) {
			commandLock.lock();
			try {
				if( !mlstTested ) {

					String tmp = (String) getFeatResponse().get(MLST);
					mlstTested = true;

					if( tmp != null ) {
						/*
						 * Tell the server which facts we want. FtpClientFile reads Perm for
						 * canRead/canWrite; a server that honours OPTS MLST (RFC 3659 7.9)
						 * sends only the facts asked for (BJL-50).
						 */
						ClientFtpResponse res = executeCommand(OPTS+" "+MLST
								+" "
								+PERM+";"
								+TYPE+";"
								+MODIFY+";"
								+SIZE+";"
								);
						
						mlstSupported = res.isPositiveComplet();						
					}
				}
			} finally {
				commandLock.unlock();
			}
		}

		return mlstSupported;
	}

	private ClientFtpResponse sendMlsdOrList(String dirPath, boolean useList) throws IOException {
		ClientFtpResponse ret = null;

		if( !useList && isMlstSupported() ) {
			/*
			 * MLST is the preferred method.  It is clear and platform independent.
			 * If the server does not support MLST this will only exec once.
			 */
			ret = executeCommand(MLSD,dirPath);
		} else { 
			/*
			 *Create the list from server that doesn't not support MLST.
			 * 
			 */
			ret = executeCommand(LIST,dirPath);
		} 

		return ret;
	}


	public String[] executeList(boolean dontUseMlst, String dirPath) throws IOException {
		commandLock.lock();
		try {
			// Pass the choice down instead of temporarily changing the shared forceList field
			return list(dirPath, dontUseMlst);
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * Execute the LIST command on the specified directory and
	 * 
	 * @param dirPath
	 * @return The list of file entries for the specified directory.
	 * @throws IOException
	 */
	public String[] executeList(String dirPath) throws IOException {
		commandLock.lock();
		try {
			return list(dirPath, forceList);
		} finally {
			commandLock.unlock();
		}
	}

	private String[] list(String dirPath, boolean useList) throws IOException {
		try {
			return listOnce(dirPath, useList);
		} catch (Tls12Needed e) {
			fallBackToTls12(e);
			return listOnce(dirPath, useList);
		}
	}

	private String[] listOnce(String dirPath, boolean useList) throws IOException {

		/*
		if(!setAsciiType()) {
			throw new IllegalStateException("Can't set type to ascii."); 
		}
		*/
		String [] ret = null;

		ClientDataTransferProcess dtp = getDataTransferProcess();
		CRLFLineReader in = null;
		try {
			dtp.connectBeforeCommand();
			ClientFtpResponse res = sendMlsdOrList(dirPath, useList);


			if( res.isPositivePreliminay()) {
				// Active mode accepts the server's connection here, after the 1xx reply
				if( startDataTls(dtp) != null ) {
					// empty listing, already complete
					return new String[0];
				}
				in = new CRLFLineReader(isModeZActive() ? inflate(dtp.getInput()) : dtp.getInput());
				String line = null;
				List<String> list = new ArrayList<String>();
				while((line=in.readLine()) != null) {
					line = line.trim();
					if( isDebugEnabled()) {
						logDebug("Line="+line);
					}

					if( line.length() > 20) {
						// 20 is a min len for a file entry.  If it's less than that, it probably server dialog.
						list.add(line);
					}
				}
				dtp.close();
				ret = (String [])list.toArray(new String[list.size()]);
				res = readResponse();
				if( !res.isPositiveComplet()) {
					if( list.isEmpty() ) {
						// nothing came: a refused TLS 1.3 data connection is retried (BJL-28)
						checkTlsResumeRefusal(res);
					}
					logError("Invalid response after list ="+res);
				}
			}
		} finally {
			if( in != null ) {
				try {
					in.close();
				} catch (Exception e) {
					logDebug("Error closing listing stream", e);
				}
			}
			// releases the socket or active listener if the command was refused
			dtp.close();
		}
		return ret;
	}





	protected ClientDataTransferProcess getDataTransferProcess() throws IOException {
		//  To support active we would need a psv/actv flag
		if( active ) {
			return  new ClientActiveDataConnection(this);
		} else {
			return  new ClientPassiveDataConnection(this);
		}

	}


	protected ClientFtpResponse readResponse() throws IOException {
		ClientFtpResponse ret = new ClientFtpResponse();
		ret.readResponse(this);

		return ret;
	}

	/**
	 * Open an input stream to a remote file
	 * @param path to remote file
	 * @param ascii type of transfer
	 * @param startingPos
	 * @return
	 * @throws IOException
	 */
	public InputStream getInputStream(String path, boolean ascii, long startingPos) throws IOException {
		return getInputStream(path, ascii, startingPos, 0);
	}

	/**
	 * Open an input stream to a remote file with a buffer of its own.
	 * @param bufferSize the size of the stream's buffer in bytes (at least 8 KB is used), or 0 for
	 * {@link #getTransferBufferSize()}
	 */
	public InputStream getInputStream(String path, boolean ascii, long startingPos, int bufferSize) throws IOException {
		commandLock.lock();
		try {
			checkStreamInProcess(path);
			ClientFtpInputStream ret;
			try {
				ret = new ClientFtpInputStream(path,this,ascii, startingPos, bufferSize);
			} catch (Tls12Needed e) {
				fallBackToTls12(e);
				ret = new ClientFtpInputStream(path,this,ascii, startingPos, bufferSize);
			}
			streamsInProcess.put(path, ret);

			return ret;
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * Open an input stream to the octets of a remote file from startingPos to endingPos, inclusive.
	 * A server that lists RANG in FEAT (draft-bryan-ftp-range) is sent RANG and sends just that
	 * range; with any other the download starts at startingPos as with REST and the stream stops
	 * after endingPos. An endingPos past the end of the file reads to the end of the file.
	 *
	 * @param startingPos the first octet wanted
	 * @param endingPos the last octet wanted (inclusive)
	 * @param bufferSize the size of the stream's buffer in bytes (at least 8 KB is used), or 0 for
	 * {@link #getTransferBufferSize()}
	 * @since 1.1.0
	 */
	public InputStream getInputStream(String path, boolean ascii, long startingPos, long endingPos, int bufferSize) throws IOException {
		commandLock.lock();
		try {
			checkStreamInProcess(path);
			ClientFtpInputStream ret;
			try {
				ret = new ClientFtpInputStream(path,this,ascii, startingPos, endingPos, bufferSize);
			} catch (Tls12Needed e) {
				fallBackToTls12(e);
				ret = new ClientFtpInputStream(path,this,ascii, startingPos, endingPos, bufferSize);
			}
			streamsInProcess.put(path, ret);

			return ret;
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * Open an input stream to a remote file
	 * @param path
	 * @param ascii
	 * @return
	 * @throws IOException
	 */
	public InputStream getInputStream(String path, boolean ascii) throws IOException {
		return getInputStream(path,ascii,0);
	}

	/**
	 * OPen an oputput stream to a remote file
	 * @param path
	 * @return	the OutputStream
	 * @throws IOException
	 */
	public OutputStream getOutputStream(String path) throws IOException {
		return getOutputStream(path,false);
	}

	public OutputStream getAppendOutputStream(String path) throws IOException {
		return getOutputStream(path,false,true);
	}

	public OutputStream getOutputStream(String path, boolean ascii, boolean append) throws IOException {
		return getOutputStream(path, ascii, append, 0);
	}

	/**
	 * Open an output stream to a remote file with a buffer of its own.
	 * @param bufferSize the size of the stream's buffer in bytes (at least 8 KB is used), or 0 for
	 * {@link #getTransferBufferSize()}
	 */
	public OutputStream getOutputStream(String path, boolean ascii, boolean append, int bufferSize) throws IOException {
		commandLock.lock();
		try {
			checkStreamInProcess(path);

			ClientFtpOutputStream ret;
			try {
				ret = new ClientFtpOutputStream(path,this,ascii, append, bufferSize);
			} catch (Tls12Needed e) {
				// nothing was written yet
				fallBackToTls12(e);
				ret = new ClientFtpOutputStream(path,this,ascii, append, bufferSize);
			}
			streamsInProcess.put(path, ret);

			return ret;
		} finally {
			commandLock.unlock();
		}
	}



	public OutputStream getOutputStream(String path, boolean ascii) throws IOException {
		return getOutputStream(path,ascii, false);
	}

	/**
	 * Open an output stream that writes into a remote file from a position: REST startingPos, then
	 * STOR. A server that writes in place leaves the rest of the file as it was, so this writes a
	 * block in the middle of a file. Not every server does: many accept REST before STOR only to
	 * resume an upload at the end of the file, and parley's FtpServer only when
	 * {@code FtpServer.setStoreAtOffset(true)} (otherwise only to resume one it knows was cut off).
	 * If the server refuses the REST this throws, and nothing is stored.
	 *
	 * @param startingPos where the data goes; 0 is a plain STOR, which replaces the file
	 * @param bufferSize the size of the stream's buffer in bytes (at least 8 KB is used), or 0 for
	 * {@link #getTransferBufferSize()}
	 * @since 1.1.0
	 */
	public OutputStream getOutputStream(String path, boolean ascii, long startingPos, int bufferSize) throws IOException {
		commandLock.lock();
		try {
			checkStreamInProcess(path);

			ClientFtpOutputStream ret;
			try {
				ret = new ClientFtpOutputStream(path,this,ascii, false, startingPos, bufferSize);
			} catch (Tls12Needed e) {
				// nothing was written yet
				fallBackToTls12(e);
				ret = new ClientFtpOutputStream(path,this,ascii, false, startingPos, bufferSize);
			}
			streamsInProcess.put(path, ret);

			return ret;
		} finally {
			commandLock.unlock();
		}
	}

	/**
	 * Open an output stream that writes the octets from startingPos to endingPos, inclusive, of a
	 * remote file: RANG startingPos endingPos, then STOR (draft-bryan-ftp-range). The server writes
	 * the data into the file in place and changes nothing outside the range. If the server refuses
	 * the RANG this throws, and nothing is stored; writing more than the range holds throws.
	 *
	 * @param startingPos the first octet written
	 * @param endingPos the last octet the range holds (inclusive)
	 * @param bufferSize the size of the stream's buffer in bytes (at least 8 KB is used), or 0 for
	 * {@link #getTransferBufferSize()}
	 * @since 1.1.0
	 */
	public OutputStream getOutputStream(String path, boolean ascii, long startingPos, long endingPos, int bufferSize) throws IOException {
		commandLock.lock();
		try {
			checkStreamInProcess(path);

			ClientFtpOutputStream ret;
			try {
				ret = new ClientFtpOutputStream(path,this,ascii, startingPos, endingPos, bufferSize);
			} catch (Tls12Needed e) {
				// nothing was written yet
				fallBackToTls12(e);
				ret = new ClientFtpOutputStream(path,this,ascii, startingPos, endingPos, bufferSize);
			}
			streamsInProcess.put(path, ret);

			return ret;
		} finally {
			commandLock.unlock();
		}
	}

	private static final java.time.format.DateTimeFormatter TIME_VAL =
			java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(java.time.ZoneOffset.UTC);

	/** @return the time as an RFC 3659 time-val (UTC, whole seconds) */
	public static String timeVal(long time) {
		return TIME_VAL.format(java.time.Instant.ofEpochMilli(time));
	}

	/**
	 * Set a remote file's modification time: MFMT (draft-somers-ftp-mfxx) when the server lists it in
	 * FEAT, else MFF Modify= when it lists that. With milliseconds when the server takes them (parley's
	 * does), else whole seconds; UTC.
	 *
	 * @param time milliseconds since the epoch
	 * @return true if the server set it; false if it has neither, or refused
	 * @since 1.1.0
	 */
	public boolean setModificationTime(String path, long time) throws IOException {
		return setTime(path, time, MFMT, "Modify");
	}

	/**
	 * Set a remote file's creation time: MFCT, else MFF Create= (draft-somers-ftp-mfxx). Not every file
	 * system keeps one that can be set.
	 *
	 * @return true if the server set it
	 * @since 1.1.0
	 */
	public boolean setCreationTime(String path, long time) throws IOException {
		return setTime(path, time, MFCT, "Create");
	}

	private boolean setTime(String path, long time, String command, String fact) throws IOException {
		return setTimeAs(path, time, command, fact) >= 0;
	}

	/** @return the time as an RFC 3659 time-val with milliseconds (UTC), e.g. 20020717210715.123 */
	public static String timeValMillis(long time) {
		return timeVal(time)+String.format(".%03d", Math.floorMod(time, 1000L));
	}

	/**
	 * Sets the time with milliseconds when it has any and the server takes them (RFC 3659 allows a
	 * fraction), else in whole seconds.
	 * @return the time that was set (time, or time in whole seconds), or -1 if it wasn't
	 */
	long setTimeAs(String path, long time, String command, String fact) throws IOException {
		Map<String, String> feat = getFeatResponse();
		String mff = feat.get(MFF);
		boolean byCommand = feat.containsKey(command);
		boolean byMff = !byCommand && mff != null && mff.toLowerCase(java.util.Locale.ROOT).contains(fact.toLowerCase(java.util.Locale.ROOT));
		if( !byCommand && !byMff ) {
			return -1;
		}
		long whole = time - Math.floorMod(time, 1000L);
		String[] tries = time == whole ? new String[] {timeVal(time)} : new String[] {timeValMillis(time), timeVal(time)};
		for(int i=0; i < tries.length; i++ ) {
			boolean ok = byCommand
					? executeCommand(command, tries[i], path)._getResponseCode() == REPLY_213_FILE_STATUS
					: !setFacts(path, java.util.Collections.singletonMap(fact, tries[i])).isEmpty();
			if( ok ) {
				return i == 0 ? time : whole;
			}
		}
		return -1;
	}

	/**
	 * MFF (draft-somers-ftp-mfxx): change facts of a remote file, e.g. Modify, Create, UNIX.mode (octal),
	 * UNIX.owner, UNIX.group; FEAT's MFF line lists what the server can change.
	 *
	 * @param facts fact name to value, sent in this order
	 * @return the facts as the server says they are now (which may differ from what was asked); empty if
	 * the server refused (not every fact may have been left as it was) or has no MFF
	 * @since 1.1.0
	 */
	public Map<String, String> setFacts(String path, Map<String, String> facts) throws IOException {
		Map<String, String> ret = new java.util.LinkedHashMap<String, String>();
		if( facts.isEmpty() || !getFeatResponse().containsKey(MFF) ) {
			return ret;
		}
		StringBuilder arg = new StringBuilder();
		for(Map.Entry<String, String> e : facts.entrySet()) {
			arg.append(e.getKey()).append('=').append(e.getValue()).append(';');
		}
		ClientFtpResponse res = executeCommand(MFF, arg.toString(), path);
		if( res._getResponseCode() == REPLY_213_FILE_STATUS ) {
			String text = res.getResponseText().trim();
			int sp = text.indexOf(' ');
			for(String f : (sp < 0 ? text : text.substring(0, sp)).split(";")) {
				int eq = f.indexOf('=');
				if( eq > 0 ) {
					ret.put(f.substring(0, eq), f.substring(eq+1));
				}
			}
		}
		return ret;
	}

	/** @return the number in a 213 reply, or -1 */
	private long number213(String command, String path) throws IOException {
		ClientFtpResponse res = path == null || path.isEmpty() ? executeCommand(command) : executeCommand(command, path);
		if( res._getResponseCode() == REPLY_213_FILE_STATUS ) {
			try {
				return Long.parseLong(res.getResponseText().trim().split("\\s+")[0]);
			} catch (NumberFormatException e) {
				logDebug(command+": "+res);
			}
		}
		return -1;
	}

	/**
	 * AVBL (draft-peterson-streamlined-ftp-command-extensions): the octets that can be uploaded to a remote
	 * directory.
	 * @param path the directory, or null for the current one
	 * @return the octets, or -1 if the server doesn't say (no AVBL, not a directory, unknown)
	 * @since 1.1.0
	 */
	public long getFreeSpace(String path) throws IOException {
		return getFeatResponse().containsKey(AVBL) ? number213(AVBL, path) : -1;
	}

	/**
	 * DSIZ (draft-peterson-streamlined-ftp-command-extensions): the octets in the files of a remote
	 * directory and all below it.
	 * @param path the directory, or null for the current one
	 * @return the octets, or -1 if the server doesn't say
	 * @since 1.1.0
	 */
	public long getDirectorySize(String path) throws IOException {
		return getFeatResponse().containsKey(DSIZ) ? number213(DSIZ, path) : -1;
	}

	/**
	 * RMDA (draft-peterson-streamlined-ftp-command-extensions): remove a remote directory and everything
	 * in it, on the server.
	 * @return true if all of it was removed; false if the server has no RMDA, or left something
	 * @since 1.1.0
	 */
	public boolean deleteRecursively(String path) throws IOException {
		checkStreamInProcess(path);
		if( !getFeatResponse().containsKey(RMDA) ) {
			return false;
		}
		return executeCommand(RMDA, path).isPositiveComplet();
	}

	/**
	 * THMB (draft-peterson-streamlined-ftp-command-extensions): a thumbnail of a remote image, made by the
	 * server, no larger than width x height.
	 * @param format an image format the server lists for THMB in FEAT, e.g. PNG or JPEG
	 * @return the image's bytes; empty if the server has no THMB
	 * @throws IOException if the server can't make it (not an image, a format it doesn't write)
	 * @since 1.1.0
	 */
	public byte[] getThumbnail(String path, String format, int width, int height) throws IOException {
		if( !getFeatResponse().containsKey(THMB) ) {
			return new byte[0];
		}
		String key = THMB+":"+path;
		InputStream in;
		commandLock.lock();
		try {
			checkStreamInProcess(key);
			ClientFtpInputStream s;
			String line = THMB+" "+format+" "+width+" "+height+" "+path;
			try {
				s = new ClientFtpInputStream(key, this, line, 0);
			} catch (Tls12Needed e) {
				fallBackToTls12(e);
				s = new ClientFtpInputStream(key, this, line, 0);
			}
			streamsInProcess.put(key, s);
			in = s;
		} finally {
			commandLock.unlock();
		}
		try (InputStream i = in) {
			java.io.ByteArrayOutputStream ret = new java.io.ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while( (n = i.read(buf)) > 0 ) {
				ret.write(buf, 0, n);
			}
			return ret.toByteArray();
		}
	}

	/**
	 * CSID (draft-peterson-streamlined-ftp-command-extensions): tell the server what this client is, and
	 * learn what it is.
	 * @return the server's facts (Name, Version, Vendor, CaseSensitive, OS, OSVer, ...); empty if it has
	 * no CSID
	 * @since 1.1.0
	 */
	public Map<String, String> identify(String name, String version, String vendor) throws IOException {
		Map<String, String> ret = new java.util.LinkedHashMap<String, String>();
		if( !getFeatResponse().containsKey(CSID) ) {
			return ret;
		}
		StringBuilder arg = new StringBuilder();
		if( name != null ) {
			arg.append("Name=").append(name).append(';');
		}
		if( version != null ) {
			arg.append("Version=").append(version).append(';');
		}
		if( vendor != null ) {
			arg.append("Vendor=").append(vendor).append(';');
		}
		ClientFtpResponse res = executeCommand(CSID, arg.toString());
		if( res.isPositiveComplet() ) {
			for(String f : res.getResponseText().split(";")) {
				int eq = f.indexOf('=');
				if( eq > 0 ) {
					ret.put(f.substring(0, eq).trim(), f.substring(eq+1).trim());
				}
			}
		}
		return ret;
	}

	/**
	 * ABOR's reply after the transfer's (ClientFtpInputStream): waited for 5 seconds at most. A server that
	 * sends none gets its connection dropped (the next command connects again) rather than this hanging.
	 */
	void readAborReply() {
		try {
			Socket s = getSocket();
			int old = s.getSoTimeout();
			s.setSoTimeout(5000);
			try {
				readResponse();
			} finally {
				s.setSoTimeout(old);
			}
		} catch (IOException e) {
			logDebug("No reply to ABOR", e);
			abandonConnection();
		}
	}

	/** The X checksum command for each HASH algorithm name */
	private static final String[][] X_COMMANDS = {
			{"SHA-256", XSHA256}, {"SHA-512", XSHA512}, {"SHA-1", XSHA1}, {"MD5", XMD5}, {"CRC32", XCRC}};

	/** @return the algorithm as HASH names it (SHA-256, SHA-512, SHA-1, MD5, CRC32), or the name as given */
	private static String hashName(String algorithm) {
		String n = algorithm.trim().toUpperCase(java.util.Locale.ROOT);
		for(String[] x : X_COMMANDS) {
			if( x[0].equals(n) || x[0].replace("-", "").equals(n.replace("-", "")) ) {
				return x[0];
			}
		}
		return n;
	}

	/**
	 * The hash of a remote file, computed by the server: {@link #hash(String, String, long, long)} with
	 * the server's algorithm (SHA-256 on parley's server) and the whole file.
	 * @since 1.1.0
	 */
	public String hash(String path) throws IOException {
		return hash(path, null, 0, -1);
	}

	/**
	 * {@link #hash(String, String, long, long)} of the whole file.
	 * @since 1.1.0
	 */
	public String hash(String path, String algorithm) throws IOException {
		return hash(path, algorithm, 0, -1);
	}

	/**
	 * The hash of a remote file, or of the octets from start to end (inclusive), computed by the server, in
	 * lower case hex. It is the hash of what RETR in TYPE I would send. A server that lists HASH in FEAT
	 * (draft-ietf-ftpext2-hash) is asked with OPTS HASH (when an algorithm is given) and HASH, after RANG
	 * for part of a file. Otherwise, or if it refuses those, the checksum commands many servers have are
	 * tried: XSHA256, XSHA512, XSHA1, XMD5 or XCRC.
	 *
	 * @param algorithm SHA-256, SHA-512, SHA-1, MD5 or CRC32 (SHA256 and so on also do), or null for the
	 *        server's (HASH) or SHA-256 (the X commands)
	 * @param start the first octet
	 * @param end the last octet (inclusive), or -1 for the end of the file
	 * @throws IOException if the server can't or won't
	 * @since 1.1.0
	 */
	public String hash(String path, String algorithm, long start, long end) throws IOException {
		if( start < 0 || (end >= 0 && end < start) ) {
			throw new IllegalArgumentException("Invalid range "+start+"-"+end);
		}
		boolean part = start > 0 || end >= 0;
		String alg = algorithm == null ? null : hashName(algorithm);
		Map<String, String> feat = getFeatResponse();
		ClientFtpResponse res = null;
		if( feat.containsKey(HASH) && (!part || feat.containsKey(RANG)) ) {
			boolean ok = true;
			if( alg != null ) {
				ok = executeCommand(OPTS, HASH+" "+alg).isPositiveComplet();
			}
			if( ok && part ) {
				ok = executeCommand(RANG, start+" "+(end < 0 ? Long.MAX_VALUE : end)).isPositiveIntermediate();
			}
			if( ok ) {
				res = executeCommand(HASH, path);
				if( res._getResponseCode() == REPLY_213_FILE_STATUS ) {
					String[] lines = res.getResponseText().trim().split("\n");
					String[] t = lines[lines.length-1].trim().split(" +");
					// "213 SHA-256 0-4 <hex> path"; a failure after "213-" lines has no hash in it
					if( t.length >= 3 && t[2].matches("[0-9A-Fa-f]+") ) {
						return t[2].toLowerCase(java.util.Locale.ROOT);
					}
				}
			}
		}
		String a = alg == null ? "SHA-256" : alg;
		String x = null;
		for(String[] c : X_COMMANDS) {
			if( c[0].equals(a) ) {
				x = c[1];
			}
		}
		if( x == null ) {
			throw new IOException("No hash "+algorithm+(res == null ? "" : ": "+res));
		}
		String arg = path.contains(" ") ? "\""+path+"\"" : path;
		if( part ) {
			arg += " "+start+(end >= 0 ? " "+(end+1) : "");
		}
		ClientFtpResponse xres = executeCommand(x, arg);
		if( xres.isPositiveComplet() ) {
			for(String t : xres.getResponseText().trim().split("\\s+")) {
				if( t.matches("[0-9A-Fa-f]{8,}") ) {
					return t.toLowerCase(java.util.Locale.ROOT);
				}
			}
		}
		throw new IOException("The server won't hash "+path+" with "+a+": "+(res == null ? "" : res+"; ")+xres);
	}

	/**
	 * STORE UNIQUE (STOU, RFC 959): open an output stream whose data the server stores under a name it
	 * chooses in the current directory; {@link ClientFtpOutputStream#getStoredName()} says which (when the
	 * server's 150 reply does, as RFC 1123 asks).
	 *
	 * @param baseName a name for the server to start from, or null (not every server uses it)
	 * @param bufferSize the size of the stream's buffer in bytes (at least 8 KB is used), or 0 for
	 * {@link #getTransferBufferSize()}
	 * @since 1.1.0
	 */
	public ClientFtpOutputStream storeUnique(String baseName, boolean ascii, int bufferSize) throws IOException {
		commandLock.lock();
		try {
			String key = ClientFtpOutputStream.uniqueKey(baseName);
			checkStreamInProcess(key);
			ClientFtpOutputStream ret;
			try {
				ret = new ClientFtpOutputStream(baseName, this, ascii, bufferSize, true);
			} catch (Tls12Needed e) {
				fallBackToTls12(e);
				ret = new ClientFtpOutputStream(baseName, this, ascii, bufferSize, true);
			}
			streamsInProcess.put(key, ret);
			return ret;
		} finally {
			commandLock.unlock();
		}
	}

	public boolean rename(String from, String to ) throws IOException {
		checkStreamInProcess(from);
		checkStreamInProcess(to);
		boolean ret = false;
		ClientFtpResponse res = executeCommand(RNFR,from);
		if( res.isPositiveIntermediate()) {
			res = executeCommand(RNTO,to);
			ret = res.isPositiveComplet();
		}

		return ret;
	}

	public boolean delete(String path) throws IOException {
		boolean ret = false;
		checkStreamInProcess(path);
		ClientFtpResponse res = executeCommand(DELE,path) ;
		ret = res.isPositiveComplet();
		return ret;
	}

	private void checkStreamInProcess(String path) throws IOException {
		commandLock.lock();
		try {
			// Any open stream blocks the control connection, not just one for the same path
			if( !streamsInProcess.isEmpty()) {
				throw new IOException("A transfer is in progress ("+streamsInProcess.keySet()+"). Close its stream before starting "
						+path+", or use a separate FtpClient for concurrent transfers.");
			}	
		} finally {
			commandLock.unlock();
		}
	}

	public boolean mkDir(String path) throws IOException {
		checkStreamInProcess(path);
		boolean ret = false;
		ClientFtpResponse res = executeCommand(MKD,path);
		ret = res.isPositiveComplet();

		return ret;
	}

	public boolean rmDir(String path) throws IOException {
		checkStreamInProcess(path);
		boolean ret = false;
		ClientFtpResponse res = executeCommand(RMD,path);
		ret = res.isPositiveComplet();

		return ret;
	}

	public ClientFtpResponse getLastResponse() {
		return lastResponse;
	}

	public boolean mkDirs(String absolutePath) throws IOException {
		checkStreamInProcess(absolutePath);
		boolean ret = mkDir(absolutePath);
		if( !ret ) {
			int idx = absolutePath.lastIndexOf(SEPARATOR_CHAR);
			if( idx > 0 ) {
				String path = absolutePath.substring(0,idx);
				if(mkDirs(path)) {
					ret = mkDir(absolutePath);
				}
			}
		}
		return ret;
	}

	public InputStream getInputStream(String remote) throws IOException {

		return getInputStream(remote,false);
	}

	/**
	 * @return A Map containing the features supported by the server (response to a FEAT command)
	 * @throws IOException
	 */
	public Map<String, String> getFeatResponse() throws IOException {
		if( featResponse == null ) {
			ClientFtpResponse res = executeCommand(FEAT);
			featResponse = new HashMap<String, String>();
			// If FEAT is not supported, this will be empty.
			if( res.isPositiveComplet()) {

				String[] lines = res.getResponseText().split("\n");
				if( lines.length>2) {
					/**

                    Replies to the FEAT command MUST comply with the following syntax.
                    Text on the first line of the reply is free form, and not
                    interpreted, and has no practical use, as this text is not expected
                    to be revealed to end users.  The syntax of other reply lines is
                    precisely defined, and if present, MUST be exactly as specified.

                         feat-response   = error-response / no-features / feature-listing
                         no-features     = "211" SP *TCHAR CRLF
                         feature-listing = "211-" *TCHAR CRLF
                                           1*( SP feature CRLF )
                                           "211 End" CRLF
                         feature         = feature-label [ SP feature-parms ]
                         feature-label   = 1*VCHAR
                         feature-parms   = 1*TCHAR

					 */

					for (int idx = 1,sz=lines.length-1; idx < sz; idx++) {
						lines[idx]=lines[idx].trim();
						String tmp = lines[idx];
						int pos = tmp.indexOf(' ');
						if( pos > 0 ) {
							tmp = tmp.substring(0,pos);
						}
						featResponse.put(tmp, lines[idx]);
					}
				}
			}
		}

		return featResponse;
	}

	/**
	 * @return true if the client connects to the address in the server's PASV reply,
	 * false (default) to use the control connection's host.
	 */
	public boolean isUsePasvAddress() {
		return usePasvAddress;
	}

	/**
	 * @param usePasvAddress true to connect to the address in the PASV reply (only needed
	 * when the server deliberately sends a different host, e.g. FXP). Default false.
	 */
	public void setUsePasvAddress(boolean usePasvAddress) {
		this.usePasvAddress = usePasvAddress;
	}

	public int getTransferBufferSize() {		
		return this.transferBufferSize ;
	}



	/**
	 * 
	 * @return true if the client should request a secure channel user AUTH command (RFC 2228)
	 */
	public boolean isRequestSecure() {
		return requestSecure;
	}

	/**
	 * LANG (RFC 2640): ask the server for its reply text in this language, now and after every reconnect.
	 * Only a server that lists LANG in FEAT is asked.
	 *
	 * @param tag a language tag (EN, fr, en-US), or null for the server's default
	 * @return true if the server accepted it (200)
	 * @since 1.1.0
	 */
	public boolean setLanguage(String tag) throws IOException {
		language = tag == null || tag.trim().isEmpty() ? null : tag.trim();
		return sendLanguage();
	}

	/** @return the language asked for with {@link #setLanguage(String)}, or null */
	public String getLanguage() {
		return language;
	}

	/**
	 * @return the languages the server lists in FEAT (LANG, RFC 2640), the one in use marked with '*';
	 * empty if it has no LANG
	 * @since 1.1.0
	 */
	public java.util.List<String> getLanguages() throws IOException {
		java.util.List<String> ret = new java.util.ArrayList<String>();
		String lang = getFeatResponse().get(LANG);
		if( lang != null ) {
			String list = lang.trim();
			if( list.toUpperCase(java.util.Locale.ROOT).startsWith(LANG) ) {
				list = list.substring(LANG.length()).trim();
			}
			for(String t : list.split(";")) {
				if( !t.trim().isEmpty() ) {
					ret.add(t.trim());
				}
			}
		}
		return ret;
	}

	/**
	 * @return true if passive data connections use SPSV (draft-rosenau-ftp-single-port) when the server
	 * lists it in FEAT
	 * @since 1.1.0
	 */
	public boolean isUseSinglePort() {
		return useSinglePort;
	}

	/**
	 * Passive data connections through the control port only (SPSV, draft-rosenau-ftp-single-port): for a
	 * server behind port forwarding that only forwards that port. Used when the server lists SPSV in FEAT
	 * and accepts it (it is refused when the data protection differs from the control port's); otherwise
	 * EPSV and PASV as usual. Off by default.
	 * @since 1.1.0
	 */
	public void setUseSinglePort(boolean useSinglePort) {
		this.useSinglePort = useSinglePort;
	}

	/** @return true if MODE Z was asked for with {@link #setCompression(boolean)} */
	public boolean isCompression() {
		return compression;
	}

	/** @return true if data connections are deflated (MODE Z) on this connection now */
	public boolean isModeZActive() {
		return modeZActive && connected;
	}

	/**
	 * MODE Z (draft-preston-ftpext-deflate): data connections deflated, for servers that list "MODE Z" in
	 * FEAT; sent now (when connected) and after every reconnect. REST and RANG aren't used with it: a
	 * download from a position reads and skips up to it, and an upload at a position fails. Off by
	 * default.
	 *
	 * @return true if MODE Z (or, for false, MODE S) is in effect now; false if not connected or the
	 * server has no MODE Z
	 * @since 1.1.0
	 */
	public boolean setCompression(boolean compression) throws IOException {
		this.compression = compression;
		if( !connected ) {
			return false;
		}
		return sendMode();
	}

	private boolean sendMode() throws IOException {
		if( compression ) {
			String mode = getFeatResponse().get(MODE);
			if( mode == null || !mode.toUpperCase(java.util.Locale.ROOT).matches(".*\\bZ\\b.*") ) {
				modeZActive = false;
				return false;
			}
			modeZActive = executeCommand(MODE, "Z").isPositiveComplet();
			return modeZActive;
		}
		if( modeZActive ) {
			modeZActive = !executeCommand(MODE, "S").isPositiveComplet();
		}
		return !modeZActive;
	}

	/** A deflating stream for MODE Z that ends its Deflater when finished */
	public static final class ZOutputStream extends java.util.zip.DeflaterOutputStream {
		ZOutputStream(OutputStream out) {
			super(out, new java.util.zip.Deflater(), 64*1024);
		}

		/** The end of the deflated data, and the Deflater released */
		public void finishAndEnd() throws IOException {
			try {
				finish();
				flush();
			} finally {
				def.end();
			}
		}
	}

	/** @return out, deflated (MODE Z) */
	ZOutputStream deflate(OutputStream out) {
		return new ZOutputStream(out);
	}

	/** Inflates (MODE Z), and ends its Inflater when closed */
	private static final class ZInputStream extends java.util.zip.InflaterInputStream {
		ZInputStream(InputStream in) {
			super(in, new java.util.zip.Inflater(), 64*1024);
		}

		@Override
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				inf.end();
			}
		}
	}

	/** @return in, inflated (MODE Z) */
	InputStream inflate(InputStream in) {
		return new ZInputStream(in);
	}

	/** What is sent once logged in: CLNT, LANG and MODE Z, as set */
	private void afterLogin() {
		modeZActive = false;
		if( sendClientName && clientName != null && !clientName.trim().isEmpty() ) {
			try {
				if( getFeatResponse().containsKey(CLNT) ) {
					executeCommand(CLNT, clientName.trim());
				}
			} catch (IOException e) {
				logDebug("CLNT failed", e);
			}
		}
		if( compression ) {
			try {
				sendMode();
			} catch (IOException e) {
				logError("MODE Z failed", e);
			}
		}
		if( language != null ) {
			try {
				if( !sendLanguage() ) {
					logDebug("The server didn't take LANG "+language);
				}
			} catch (IOException e) {
				logError("LANG "+language+" failed", e);
			}
		}
	}

	private boolean sendLanguage() throws IOException {
		if( !getFeatResponse().containsKey(LANG) ) {
			return false;
		}
		ClientFtpResponse res = language == null ? executeCommand(LANG) : executeCommand(LANG, language);
		return res.isPositiveComplet();
	}

	/**
	 * @return the virtual host this client asks for, or null (the default) for the host it connects to
	 * @see #setVirtualHost(String)
	 * @since 1.1.0
	 */
	public String getVirtualHost() {
		return virtualHost;
	}

	/**
	 * The virtual host to ask the server for with HOST (RFC 7151), when it isn't the name connected to:
	 * a server reached by address, or one name among several on one server. It is also the name used for
	 * TLS (SNI, and the certificate's host name check), as RFC 7151 section 4 asks. A server that refuses
	 * it (501, 504) or closes (421) makes {@link #connect(String, String, String)} fail; one that doesn't
	 * know HOST (500, 502) is logged in to as usual.
	 *
	 * @param virtualHost a host name, or null for the host connected to
	 * @since 1.1.0
	 */
	public void setVirtualHost(String virtualHost) {
		this.virtualHost = virtualHost == null || virtualHost.trim().isEmpty() ? null : virtualHost.trim();
	}

	/**
	 * @return true (the default) if HOST (RFC 7151) is sent before logging in: the virtual host set, or
	 * else the host connected to
	 * @since 1.1.0
	 */
	public boolean isSendHost() {
		return sendHost;
	}

	/**
	 * Send HOST (RFC 7151) before logging in, or not. With the host connected to (no virtual host set),
	 * any refusal is ignored and the login goes on, so this is safe with servers that don't know HOST.
	 * @since 1.1.0
	 */
	public void setSendHost(boolean sendHost) {
		this.sendHost = sendHost;
	}

	/** The name for TLS: the virtual host, else the host connected to (without IPv6 brackets) */
	private String tlsName() {
		String ret = virtualHost != null ? virtualHost : getHost();
		if( ret != null && ret.startsWith("[") && ret.endsWith("]") ) {
			ret = ret.substring(1, ret.length()-1);
		}
		return ret;
	}

	/** The argument of HOST: the name for TLS, with an IPv6 address in brackets (RFC 7151 section 3.1) */
	private String hostArgument() {
		String ret = tlsName();
		return ret != null && ret.contains(":") ? "["+ret+"]" : ret;
	}

	/**
	 * HOST (RFC 7151), before AUTH so a server without SNI can choose its certificate (section 3.2.2).
	 * @return false if the login can't go on: the server closed (421), or refused a virtual host that
	 * was asked for
	 */
	private boolean executeHost() throws IOException {
		String name = hostArgument();
		if( name == null ) {
			return true;
		}
		ClientFtpResponse res = sendCommand(HOST+" "+name);
		int code = res._getResponseCode();
		if( res.isPositiveComplet() ) {
			return true;
		}
		if( code == REPLY_421_SERVICE_NOT_AVAILIBLE ) {
			logError("HOST "+name+": "+res);
			return false;
		}
		if( virtualHost != null && (code == REPLY_501_SYNTAXT_ERROR_IN_PARAM || code == REPLY_504_NOT_IMP_FOR_PARAM) ) {
			logError("The server won't be virtual host "+name+": "+res);
			return false;
		}
		// a server without HOST (500, 502), or one that won't take the name connected to
		logDebug("HOST "+name+": "+res);
		return true;
	}

	/**
	 * if true the client will request a secure channel user AUTH command (RFC 2228)
	 * @param requestSecure
	 */
	public void setRequestSecure(boolean requestSecure) {
		this.requestSecure = requestSecure;
	}

	/**
	 * @return true (the default) if secure connections (implicit TLS and AUTH TLS) check that the
	 *  server's certificate was issued for the host name (or address) being connected to. Without
	 *  this check any certificate the trust managers accept is accepted for every host, so a server
	 *  with any trusted certificate could pretend to be another. The verifyHostname property
	 *  ("false") changes the default.
	 */
	public boolean isVerifyHostname() {
		Boolean ret = verifyHostname;
		if( ret == null ) {
			ret = getBooleanProperty(propertyName(PROPERTY_VERIFY_HOSTNAME), true);
			verifyHostname = ret;
		}
		return ret;
	}

	/**
	 * Turn the host name check for secure connections on or off. Turn it off only for servers
	 * whose certificate is known not to match the name used to reach them (a test certificate, or
	 * a server reached by an address its certificate doesn't list).
	 *
	 * @param verifyHostname
	 */
	public void setVerifyHostname(boolean verifyHostname) {
		this.verifyHostname = verifyHostname;
	}

	/**
	 * If requireSecure is true the client will refuse to make an insecure connection.
	 *  
	 * @return true if the client should require a secure channel
	 */
	public boolean isRequireSecure() {
		return requireSecure;
	}

	/**
	 * If requireSecure is true the client will refuse to make an insecure connection.
	 * 
	 * @param requireSecure
	 */
	public void setRequireSecure(boolean requireSecure) {
		this.requireSecure = requireSecure;
	}

	/**
	 * This is called before signing in when the connection is not secure.
	 * If the server does not accept the AUTH then the channel will stay un-secured
	 * (and {@link #isRequireSecure()} decides whether the client logs in anyway).
	 * <p>
	 * If the server accepts AUTH but TLS can't be set up (the handshake fails, or the certificate
	 * is rejected), the connection is closed and an IOException thrown. It used to log the error,
	 * try the next mechanism on the broken connection and, when the server then refused it, log in
	 * over the plain connection: an attacker in the middle could accept AUTH, break the TLS step and
	 * so get the login in clear text.
	 * @return true if a secure channel was negotiated, false if the server didn't accept AUTH.
	 * 
	 * @throws IOException in case of a communication problem, or if TLS failed after the server accepted AUTH.
	 */
	private boolean executeAuth() throws IOException {
		logDebug("Enter executeAuth");
		if( isChannelSecure() || isSecure()) {
			return true;
		}
		boolean ret = false;

		if( isRequestSecure()) {
			for(String type : SECURE_TYPES) {
				ClientFtpResponse res = sendCommand(AUTH+" "+type);
				if( res.isPositiveComplet()) {
					try {
						negotiateSecureSocket(type);
						ret = true;
						break;
					} catch (IOException | RuntimeException e) {
						//  The server expects TLS now and the connection is in an unknown state: never
						//  carry on in plain text (or try another mechanism) on it
						logError("Server accepted AUTH "+type+" but a secure connection could not be established", e);
						close();
						throw new IOException("Server accepted AUTH "+type+" but TLS could not be established: "+e.getMessage(), e);
					}
				}
			}
		}
		logDebug("Exit executeAuth now secure="+ret);
		return ret;
	}

	/**
	 * In many cases, if a secure socket is required, the
	 * SocketFactory takes care of the details when a connection is established.
	 * 
	 * In some cases you need to change an existing socket 
	 * to a secure socket.
	 * 
	 * @param sslOrTsl
	 * @throws KeyManagementException
	 * @throws CertificateException
	 * @throws FileNotFoundException
	 * @throws KeyStoreException
	 * @throws NoSuchAlgorithmException
	 * @throws UnrecoverableKeyException
	 * @throws IOException
	 */
	private void negotiateSecureSocket(String sslOrTsl) throws  IOException {
		if( sslSocket != null ) {
			sslSocket.close();
			sslSocket = null;
		}

		if( sslOrTsl == null ) {
			return;
		}


		if( isSecure()) {
			throw new IllegalStateException("Can not negotiate a secure channel from a secure channel.");
		}
		/*
		 * The AUTH mechanism (TLS, SSL) means "start TLS", not a TLS version: use the
		 * configured protocol (default "TLS": the best both sides support). This used to
		 * setProtocol(mechanism), which also threw away a setProtocol("TLSv1.2") made by the
		 * caller (BJL-28).
		 */
		SSLContext ctx=getSSLContext();
		SSLSocketFactory factory = ctx.getSocketFactory();

		// With the host (it was null), Java caches the session under this host and port,
		// which is what lets data connections resume it (see secureDataSocket, BJL-18).
		// TlsSockets also sets SNI and the host name check (it had none).
		// the virtual host's name when one is set (RFC 7151 section 4)
		sslSocket = TlsSockets.layer(ctx, socket, tlsName(), true, isVerifyHostname(), false);
		sslSocket.setWantClientAuth(false);
		sslSocket.startHandshake();
		channelSecure = true;
		input = null;
		output = null;

		//  future sockets will be secure using this context
		setSocketFactory(factory);
		setServerSocketFactory(ctx.getServerSocketFactory());


	}

	protected void streamHasClosed(String path, AutoCloseable stream) {
		commandLock.lock();
		try {
			AutoCloseable obj = streamsInProcess.remove(path);
			if( obj == null ) {
				logInfo(path+" did not have a stream in process.");
			}

		} finally {
			commandLock.unlock();
		}
	}

	@Override
	public void logDebug(String msg) {
		if( isSecure()) {
			msg = "(S) "+msg;
		}
		if( isChannelSecure()) {
			msg = "(CS) "+msg;
		}
		super.logDebug(msg);
	}

	@Override
	public void logDebug(String msg, Throwable error) {
		if( isSecure()) {
			msg = "(S) "+msg;
		}
		if( isChannelSecure()) {
			msg = "(CS) "+msg;
		}

		super.logDebug(msg, error);
	}
}
