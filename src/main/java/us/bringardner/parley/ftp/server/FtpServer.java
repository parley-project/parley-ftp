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
 * ~version~V000.01.48-V000.01.39-V000.01.37-V000.01.35-V000.01.28-V000.01.26-V000.01.25-V000.01.24-V000.01.19-V000.01.14-V000.01.13-V000.01.07-V000.01.05-V000.01.03-V000.00.03-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 14, 2004
 *
 */
package us.bringardner.parley.ftp.server;


import java.io.IOException;

import us.bringardner.parley.core.ILogger;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.net.IProcessor;
import us.bringardner.parley.net.IProcessorFactory;
import us.bringardner.parley.net.server.AbstractPrincipal;
import us.bringardner.parley.net.server.IAccessControlList;
import us.bringardner.parley.net.server.IPermission;
import us.bringardner.parley.net.server.IPrincipal;
import us.bringardner.parley.net.server.ServerMain;
import us.bringardner.parley.net.server.IServer;
import us.bringardner.parley.net.server.Server;


/**
 * @author Tony Bringardner
 *
 */
public class FtpServer extends Server {

	/**
	 * System property: how many servers share the store (off, the default: this server alone, and
	 * a second one is refused; host: several on one machine; cluster: several anywhere). Uploads,
	 * deletes and renames of one file keep out of each other across all of them, through
	 * parley-net's {@link us.bringardner.parley.net.lock.StoreLocks}.
	 * @since 1.1.0
	 */
	public static final String SHARED_STORE_PROPERTY = "parley.ftp.sharedStore";
	/**
	 * System property: this server's id among those that share the store (default: the host, the
	 * server's name and its port).
	 * @since 1.1.0
	 */
	public static final String SERVER_ID_PROPERTY = "parley.ftp.serverId";

	public static final int FTP_PORT = 21;
	public static final int FTP_SSL_PORT = 421;
	public static final String FTP_NAME = "JFtp";
	
	
	/** The prefix of the properties of an FTP server. */
	public static final String FTP_PREFIX = "parley.ftp";
	/** The prefix of the properties of an FTP server with implicit TLS. */
	public static final String FTPS_PREFIX = "parley.ftps";
	/** A properties file main loads into the system properties. */
	public static final String CONFIG_PROP = FTP_PREFIX+".configFile";
	public static final String ROOT_PROP = FTP_PREFIX+".root";
	public static final String DEBUG_PROP = FTP_PREFIX+".debug";
	public static final String FILE_SOURCE_PROP = FTP_PREFIX+".fileSource";
    
	public static final String DEFAULT_ROOT_WINDOWS = "C:/ftp";
	public static final String DEFAULT_ROOT = "/ftp";
	public static final String EXTERNAL_ADDRESS_PROP = FTP_PREFIX+".externalAddress";
	/** One port for every passive (PASV/EPSV) data connection. */
	public static final String PASIVE_CONTROL_PORT_PROP = FTP_PREFIX+".passivePort";
	/** The range of ports for passive (PASV/EPSV) data connections. */
	public static final String PASIVE_CONTROL_MIN_PROP = FTP_PREFIX+".passiveMinPort";
	public static final String PASIVE_CONTROL_MAX_PROP = FTP_PREFIX+".passiveMaxPort";
	
	/** Default size (bytes) of the buffer used to copy data-channel transfers. */
	public static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
	public static final String BUFFER_SIZE_PROP = FTP_PREFIX+".bufferSize";
	/** Default data-connection inactivity timeout (ms) */
	public static final int DEFAULT_DATA_TIMEOUT = 10 * 60 * 1000;
	public static final String DATA_TIMEOUT_PROP = FTP_PREFIX+".dataTimeout";
	/** Default timeout (ms) for connecting to the client in active mode (PORT/EPRT) */
	public static final int DEFAULT_CONNECT_TIMEOUT = 15 * 1000;
	public static final String CONNECT_TIMEOUT_PROP = FTP_PREFIX+".connectTimeout";
	/**
	 * If "true", PORT/EPRT may name any address/port and PASV accepts a data connection from any
	 * address (needed for FXP / server-to-server transfers). Default false (RFC 2577 protections).
	 */
	public static final String ALLOW_FOREIGN_DATA_ADDRESS_PROP = FTP_PREFIX+".allowForeignDataAddress";
	/**
	 * How symbolic links inside a user's root are treated. {@code ..} can never leave the
	 * root in any mode (it is resolved on the virtual path first).
	 */
	public enum SymlinkPolicy {
		/** Links that lead outside the root are refused (default). */
		STRICT,
		/** Links may lead outside the root only into the directories listed in allowedLinkTargets. */
		ALLOWED_TARGETS,
		/** Every link inside the root is followed, wherever it points (only when users can't create links). */
		FOLLOW;

		/** Parse "strict", "allowedTargets" / "allowed_targets" or "follow" (case-insensitive). */
		public static SymlinkPolicy parse(String value) {
			String v = value.trim().replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
			switch (v) {
			case "strict": return STRICT;
			case "allowedtargets": return ALLOWED_TARGETS;
			case "follow": return FOLLOW;
			default: throw new IllegalArgumentException("Invalid symlink policy '"+value+"' (use strict, allowedTargets or follow)");
			}
		}
	}
	public static final String SYMLINK_POLICY_PROP = FTP_PREFIX+".symlinkPolicy";

	/**
	 * How STOR puts an upload in place.
	 */
	public enum UploadMode {
		/**
		 * The data goes to a hidden temporary file that replaces the target only when the whole
		 * upload has arrived (default). The original is never half replaced, and an interrupted
		 * upload leaves nothing behind, so there is nothing to resume: the client starts again.
		 */
		ATOMIC,
		/**
		 * The data goes straight into the target, which STOR empties first. An interrupted upload
		 * leaves what arrived, and the client can carry on with REST and STOR. The original is gone
		 * as soon as the upload starts, and a half written file is visible meanwhile.
		 */
		IN_PLACE;

		/** Parse "atomic" or "inPlace" / "in_place" / "in-place" (case-insensitive). */
		public static UploadMode parse(String value) {
			String v = value.trim().replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
			switch (v) {
			case "atomic": return ATOMIC;
			case "inplace": return IN_PLACE;
			default: throw new IllegalArgumentException("Invalid upload mode '"+value+"' (use atomic or inPlace)");
			}
		}
	}
	public static final String UPLOAD_MODE_PROP = FTP_PREFIX+".uploadMode";
	/**
	 * true: REST n then STOR writes the upload into the file from n, in place, leaving the rest of the
	 * file as it was (what ProFTPD calls AllowStoreRestart). Off by default. See {@link #setStoreAtOffset(boolean)}.
	 */
	public static final String STORE_AT_OFFSET_PROP = FTP_PREFIX+".storeAtOffset";
	/** Directories that links may lead into with SymlinkPolicy.ALLOWED_TARGETS, separated by commas. */
	public static final String ALLOWED_LINK_TARGETS_PROP = FTP_PREFIX+".allowedLinkTargets";

	/** Delay (ms) before replying to a failed login, to slow down password guessing. */
	public static final int DEFAULT_LOGIN_FAILURE_DELAY = 1000;
	/**
	 * Virtual hosts for HOST (RFC 7151): {@code name=root;name=root}, each root a path in the server's
	 * file system. See {@link #addVirtualHost(String, FileSource)}.
	 */
	public static final String VIRTUAL_HOSTS_PROP = FTP_PREFIX+".virtualHosts";
	/** SO_LINGER (seconds) for data connections; off by default. */
	public static final String LINGER_PROP = FTP_PREFIX+".linger";

	private volatile int bufferSize = Integer.getInteger(BUFFER_SIZE_PROP, DEFAULT_BUFFER_SIZE);
	private volatile int dataTimeout = Integer.getInteger(DATA_TIMEOUT_PROP, DEFAULT_DATA_TIMEOUT);
	private volatile int connectTimeout = Integer.getInteger(CONNECT_TIMEOUT_PROP, DEFAULT_CONNECT_TIMEOUT);
	private volatile boolean allowForeignDataAddress = Boolean.getBoolean(ALLOW_FOREIGN_DATA_ADDRESS_PROP);
	private volatile SymlinkPolicy symlinkPolicy = SymlinkPolicy.parse(System.getProperty(SYMLINK_POLICY_PROP, "strict"));
	private volatile UploadMode uploadMode = UploadMode.parse(System.getProperty(UPLOAD_MODE_PROP, "atomic"));
	private volatile boolean storeAtOffset = Boolean.getBoolean(STORE_AT_OFFSET_PROP);

	/** How many unfinished uploads are remembered (the oldest are forgotten first). */
	static final int MAX_UNFINISHED_UPLOADS = 1000;
	/**
	 * Files an upload left unfinished (path to the length it stopped at), so that REST and STOR can
	 * carry on with them. A file that isn't here is complete as far as the server knows, and REST
	 * can't make STOR add to it: that is how an upload resumed with the length of the OLD file
	 * (which SIZE gives) used to be appended to it. Kept in memory, so after a restart a client
	 * starts again.
	 */
	private final java.util.Map<String,Long> unfinishedUploads = java.util.Collections.synchronizedMap(new UnfinishedUploads());

	/** The oldest entries go first once there are {@link FtpServer#MAX_UNFINISHED_UPLOADS}. */
	private static final class UnfinishedUploads extends java.util.LinkedHashMap<String,Long> {
		private static final long serialVersionUID = 1L;

		UnfinishedUploads() {
			super(16, 0.75f, false);
		}

		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<String,Long> eldest) {
			return size() > MAX_UNFINISHED_UPLOADS;
		}
	}

	/** @return how STOR puts an upload in place (default ATOMIC). */
	public UploadMode getUploadMode() {
		return uploadMode;
	}

	/**
	 * @param mode ATOMIC (default): the upload goes to a temporary file and replaces the target when it
	 * is complete, so a broken upload can't be resumed; IN_PLACE: the upload goes into the target, so
	 * a broken one can be resumed with REST. Takes effect on the next upload.
	 */
	public void setUploadMode(UploadMode mode) {
		if( mode == null ) {
			throw new IllegalArgumentException("mode must not be null");
		}
		this.uploadMode = mode;
	}

	/** @return true if REST n then STOR writes in place from n (default false) */
	public boolean isStoreAtOffset() {
		return storeAtOffset;
	}

	/**
	 * @param storeAtOffset false (default): REST before STOR only resumes an upload this server knows
	 * was cut off, at the length it stopped at; true: REST n (0 &lt; n &le; the file's length) then STOR
	 * writes the upload into the file from n, in place, and leaves the rest of it as it was, so a
	 * client can write a block in the middle of a file (random access). Such a write isn't atomic: a
	 * transfer that breaks leaves what arrived. With n equal to the length it adds to the file, even
	 * one that wasn't cut off. Takes effect on the next STOR.
	 * @since 1.1.0
	 */
	public void setStoreAtOffset(boolean storeAtOffset) {
		this.storeAtOffset = storeAtOffset;
	}

	void markUnfinished(String path, long length) {
		if( length > 0 ) {
			unfinishedUploads.put(path, length);
		} else {
			unfinishedUploads.remove(path);
		}
	}

	void clearUnfinished(String path) {
		unfinishedUploads.remove(path);
	}

	/** @return true if an upload of this file stopped when the file was this long */
	boolean isUnfinished(String path, long length) {
		Long at = unfinishedUploads.get(path);
		return at != null && at.longValue() == length;
	}
	private volatile java.util.List<String> allowedLinkTargets = parseTargets(System.getProperty(ALLOWED_LINK_TARGETS_PROP, ""));
	private FileSource ftpRoot;
	/** SPSV identifiers waiting for their SPDT connection */
	private final java.util.Map<String, FtpRequestProcessor.SinglePort> singlePorts = new java.util.concurrent.ConcurrentHashMap<String, FtpRequestProcessor.SinglePort>();

	void addSinglePort(FtpRequestProcessor.SinglePort sp) {
		singlePorts.put(sp.id, sp);
	}

	void removeSinglePort(String id) {
		singlePorts.remove(id);
	}

	/**
	 * @return the SPSV session waiting for this identifier, which is used up; null if none is
	 * @since 1.1.0
	 */
	public FtpRequestProcessor.SinglePort takeSinglePort(String id) {
		return id == null ? null : singlePorts.remove(id);
	}

	/** Virtual host name (lower case) to its root */
	private final java.util.Map<String, FileSource> virtualHosts = new java.util.concurrent.ConcurrentHashMap<String, FileSource>();
	//private boolean useJdbc = false;
	private FileSourceFactory factory = FileSourceFactory.getDefaultFactory();
	
	
	/**
	 * @param port
	 * @param name
	 * @param secure
	 * @throws IOException 
	 */
	public FtpServer(int port, String name,  boolean secure) {
		super(port, name);
		setPropertyPrefix(secure ? FTPS_PREFIX : FTP_PREFIX);
		setSecure(secure);
		setDaemon(false);
		initMe();
		// INFO by default: DEBUG logs several lines per transfer, including paths and client addresses
		getLogger().setLevel(us.bringardner.parley.core.ILogger.Level.INFO);
	}
	
	public FtpServer() {
		this(FTP_PORT,FTP_NAME,false);
	}
	
	public FtpServer(boolean secure) {
		this(FTP_PORT,FTP_NAME,secure);
	}
	
	public FtpServer(FileSource root, boolean secure) {
		this(FTP_PORT,FTP_NAME,secure);
		// root was previously ignored
		try {
			setFtpRoot(root);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException("Invalid FTP root "+root, e);
		}
	}

	public static void main(String[] args) throws Exception {
		ServerMain.run("FtpServer", args, CONFIG_PROP, "parley-ftp.properties", () -> {
			boolean secure = System.getProperty(FTP_PREFIX+".secure", "false").toLowerCase(java.util.Locale.ROOT).equals("true");
			int port = FTP_PORT;
			String tmp = System.getProperty((secure ? FTPS_PREFIX : FTP_PREFIX)+".port");
			if( tmp != null ) {
				port = Integer.parseInt(tmp.trim());
			}
			return java.util.Collections.singletonList(ServerMain.service(new FtpServer(port, FTP_NAME, secure), "FtpServer"));
		});
	}

	private void initMe()  {
		
		setName("FtpServer");
		
		setProcessorFactory(new IProcessorFactory() {
			public IProcessor getProcessor() {
				FtpRequestProcessor ret = new FtpRequestProcessor();
				ret.getLogger().setLevel(FtpServer.this.getLogger().getLevel());
				ret.setActivityTimeOut(getDataTimeout());
				return ret;
			}			
		});
		
		setConnectionFactory(newConnectionFactory());

		//  Determine which FileSource to use
		String tmp = System.getProperty(FILE_SOURCE_PROP);
		if( tmp != null ) {
			tmp = tmp.toLowerCase(java.util.Locale.ROOT);
			setFileSourceFactory(FileSourceFactory.getFileSourceFactory(tmp));
		} else {
			setFileSourceFactory(FileSourceFactory.getDefaultFactory());
		}
		
		
		tmp = System.getProperty(ROOT_PROP);
		if( tmp == null ) {

			if(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).indexOf("win") >= 0) {
				tmp = DEFAULT_ROOT_WINDOWS;
			} else {
				tmp = DEFAULT_ROOT;
			}
	 
		}
		
		try {
			setFtpRoot(factory.createFileSource(tmp));
		} catch (IOException e) {
			logInfo("Error attmtping set set root "+tmp+" using "+factory.getTypeId()+" factory");
		}

		tmp = System.getProperty(VIRTUAL_HOSTS_PROP);
		if( tmp != null ) {
			for(String entry : tmp.split(";")) {
				entry = entry.trim();
				if( entry.isEmpty() ) {
					continue;
				}
				int eq = entry.indexOf('=');
				if( eq <= 0 ) {
					logError(VIRTUAL_HOSTS_PROP+": expected name=root, not "+entry);
					continue;
				}
				String name = entry.substring(0, eq).trim();
				String path = entry.substring(eq+1).trim();
				try {
					addVirtualHost(name, factory.createFileSource(path));
				} catch (IOException | RuntimeException e) {
					logError(VIRTUAL_HOSTS_PROP+": can't add "+name+" at "+path, e);
				}
			}
		}
		
		
		if( (tmp = System.getProperty(PASIVE_CONTROL_PORT_PROP)) != null ) {
			try {
				PassiveSocket.setControlPort(Integer.parseInt(tmp));
			} catch (Exception e) {
				throw new IllegalArgumentException("Can't configure passive control port='"+tmp+"'",e);
			}
		}

		
		if( (tmp = System.getProperty(PASIVE_CONTROL_MIN_PROP)) != null ) {
			try {
				PassiveSocket.setMinControlPort(Integer.parseInt(tmp));
			} catch (Exception e) {
				throw new IllegalArgumentException("Can't configure passive min control port='"+tmp+"'",e);
			}
		}
		
		if( (tmp = System.getProperty(PASIVE_CONTROL_MAX_PROP)) != null ) {
			try {
				PassiveSocket.setMaxControlPort(Integer.parseInt(tmp));
			} catch (Exception e) {
				throw new IllegalArgumentException("Can't configure passive max control port='"+tmp+"'",e);
			}
		}
		//  trigger access control initialization 
		IAccessControlList acl = getAccessControl();
		if( acl == null ) {
			//  I'll honor the RFC 959 for ANONYMOUS and FTP users
			setAccessControl(new AnonymousAcl());
		}
		
	}
	
	
	/**
	 * Access control used when none is configured: RFC 959 anonymous access ("anonymous" or
	 * "ftp", any password) with every permission except the administrator's.
	 */
	private static final class AnonymousAcl implements IAccessControlList {
		
		/** The anonymous user: any password is accepted (RFC 959 / RFC 1635). */
		private static final class AnonymousPrincipal extends AbstractPrincipal {
			AnonymousPrincipal() {
				super("anonymous");
			}

			@Override
			public boolean authenticate(byte[] credentials) {
				return true;
			}
		}

		private final IPrincipal anonymous = new AnonymousPrincipal();

		@Override
		public void initialize(IServer server) throws IOException {
			anonymous.setParameter(FtpRequestProcessor.PARAMETER_ROOT, "/anonymous");
		}
		
		@Override
		public IPrincipal getPrincipal(String user) {
			if( !(user.equalsIgnoreCase("anonymous") || user.equalsIgnoreCase("ftp"))) {
				return null;
			}
			
			return anonymous;						
			
		}
		
		@Override
		public boolean checkPermission(IPrincipal user, IPermission action) {
			// give anonymous all but admin rights
			boolean ret = user.getName().equals("anonymous") && !FtpCommand.ADMIN_PERMISSION.equals(action);
			
			return ret;
		}
	}

	/** The roots this server has claimed while it runs: their domains and claims. */
	private final java.util.List<us.bringardner.parley.net.lock.LockDomain> lockDomains = new java.util.ArrayList<>();
	private final java.util.List<us.bringardner.parley.net.lock.StoreClaim> storeClaims = new java.util.ArrayList<>();

	/**
	 * Claims the store of the root and of every virtual host (see {@link #SHARED_STORE_PROPERTY})
	 * before accepting connections: with sharing off a second server on one is refused, shared
	 * the servers that share it are let in. Fails to start if a claim fails.
	 */
	@Override
	public synchronized void start() {
		if( lockDomains.isEmpty() ) {
			try {
				claimStores();
			} catch (IOException e) {
				releaseStores();
				logError("Can't start "+getName()+": "+e.getMessage(), e);
				throw new java.io.UncheckedIOException(e);
			} catch (RuntimeException e) {
				releaseStores();
				logError("Can't start "+getName()+": "+e.getMessage(), e);
				throw e;
			}
		}
		boolean started = false;
		try {
			super.start();
			started = true;
		} finally {
			if( !started ) {
				releaseStores();
			}
		}
	}

	@Override
	public void stop() {
		super.stop();
		releaseStores();
	}

	private void claimStores() throws IOException {
		java.util.List<FileSource> roots = new java.util.ArrayList<FileSource>();
		roots.add(getFtpRoot());
		roots.addAll(virtualHosts.values());
		us.bringardner.parley.net.lock.Sharing sharing = us.bringardner.parley.net.lock.StoreLocks.sharing(SHARED_STORE_PROPERTY);
		String id = us.bringardner.parley.net.lock.StoreLocks.serverId(SERVER_ID_PROPERTY, "ftp", getPort());
		for(FileSource root : roots) {
			us.bringardner.parley.net.lock.LockDomain d = us.bringardner.parley.net.lock.StoreLocks.get().acquire(root, sharing);
			lockDomains.add(d);
			storeClaims.add(d.claimStore(id));
		}
	}

	private synchronized void releaseStores() {
		for(us.bringardner.parley.net.lock.StoreClaim c : storeClaims) {
			c.close();
		}
		storeClaims.clear();
		for(us.bringardner.parley.net.lock.LockDomain d : lockDomains) {
			us.bringardner.parley.net.lock.StoreLocks.get().release(d);
		}
		lockDomains.clear();
	}

	public FileSource getFtpRoot() throws IOException {
		if( ftpRoot == null ) {
			// e.g. the configured root could not be created
			throw new IOException("The FTP root is not configured (see "+ROOT_PROP+")");
		}
		if( !ftpRoot.exists()){
			ftpRoot.mkdirs();
		}
		return ftpRoot;
	}
	
	/**
	 * Adds a virtual host (RFC 7151): a client that sends {@code HOST name} before logging in gets this
	 * root, in the root's file system. With no virtual hosts, HOST accepts any name and the client gets
	 * the server's root; with some, a name that isn't one of them is refused (504) and the client stays on
	 * the host it had (the server's root unless an earlier HOST chose one). A client that sends no HOST
	 * gets the server's root. Users and their permissions are the server's, whatever the host.
	 *
	 * @param name a host name (letters, digits and '-', in labels separated by '.'; IDNs as A-labels),
	 * matched without regard to case
	 * @param root the host's root directory; made if it doesn't exist
	 * @throws IllegalArgumentException if the name isn't a host name
	 * @since 1.1.0
	 */
	public void addVirtualHost(String name, FileSource root) throws IOException {
		String key = us.bringardner.parley.ftp.server.commands.Host.normalize(name);
		if( key == null || key.startsWith("[") ) {
			throw new IllegalArgumentException("Not a host name: "+name);
		}
		if( !root.exists() ){
			root.mkdirs();
		} else if( !root.isDirectory() ) {
			throw new IllegalArgumentException("Not a directory: "+root);
		}
		virtualHosts.put(key, root);
	}

	/**
	 * @return the root it had, or null if it wasn't a virtual host
	 * @since 1.1.0
	 */
	public FileSource removeVirtualHost(String name) {
		String key = us.bringardner.parley.ftp.server.commands.Host.normalize(name);
		return key == null ? null : virtualHosts.remove(key);
	}

	/**
	 * @return the virtual hosts (name in lower case to root)
	 * @since 1.1.0
	 */
	public java.util.Map<String, FileSource> getVirtualHosts() {
		return java.util.Collections.unmodifiableMap(new java.util.TreeMap<String, FileSource>(virtualHosts));
	}

	/**
	 * @return the root of the virtual host with this name, or null
	 * @since 1.1.0
	 */
	public FileSource getVirtualHostRoot(String name) {
		String key = us.bringardner.parley.ftp.server.commands.Host.normalize(name);
		return key == null ? null : virtualHosts.get(key);
	}

	public void setFtpRoot(FileSource ftpRoot) throws IOException {
		if( !ftpRoot.exists() ){
			ftpRoot.mkdirs();
		} else if( !ftpRoot.isDirectory() ) {
			throw new RuntimeException("Invalid root dir = "+ftpRoot);
		}
		this.ftpRoot = ftpRoot;
		setFileSourceFactory(ftpRoot.getFileSourceFactory());
	}
	
	/**
	 * @return size (bytes) of the buffer used for data-channel transfers.
	 */
	public int getBufferSize() {
		return bufferSize;
	}

	public void setBufferSize(int bufferSize) {
		if( bufferSize <= 0 ) {
			throw new IllegalArgumentException("bufferSize must be > 0");
		}
		this.bufferSize = bufferSize;
	}

	/**
	 * @return the data-connection inactivity timeout in milliseconds. A transfer
	 * that moves no data for this long is aborted with a 426 reply.
	 */
	public int getDataTimeout() {
		return dataTimeout;
	}

	/**
	 * Applies to processors (connections) created after this call.
	 * @param dataTimeout inactivity timeout in milliseconds (must be > 0)
	 */
	public void setDataTimeout(int dataTimeout) {
		if( dataTimeout <= 0 ) {
			throw new IllegalArgumentException("dataTimeout must be > 0");
		}
		this.dataTimeout = dataTimeout;
	}

	/**
	 * @return timeout (ms) used when connecting to the client for an active (PORT/EPRT) data connection.
	 */
	public int getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(int connectTimeout) {
		if( connectTimeout <= 0 ) {
			throw new IllegalArgumentException("connectTimeout must be > 0");
		}
		this.connectTimeout = connectTimeout;
	}

	/**
	 * @return true if data connections may use an address other than the client's
	 * control connection address (FXP). Default false.
	 */
	public boolean isAllowForeignDataAddress() {
		return allowForeignDataAddress;
	}

	/**
	 * RFC 2577: by default PORT/EPRT must name the client's own address and a port >= 1024,
	 * and a passive data connection must come from the client's address. This prevents
	 * "FTP bounce" attacks and data connection theft. Set true only for FXP.
	 */
	public void setAllowForeignDataAddress(boolean allow) {
		this.allowForeignDataAddress = allow;
	}

	/**
	 * @return how symbolic links inside a user's root are treated (default STRICT).
	 */
	public SymlinkPolicy getSymlinkPolicy() {
		return symlinkPolicy;
	}

	/**
	 * @param policy STRICT (default): links out of the root are refused;
	 * ALLOWED_TARGETS: links may lead into {@link #setAllowedLinkTargets(java.util.List)};
	 * FOLLOW: every link in the root is followed. Takes effect on the next command.
	 */
	public void setSymlinkPolicy(SymlinkPolicy policy) {
		if( policy == null ) {
			throw new IllegalArgumentException("policy must not be null");
		}
		this.symlinkPolicy = policy;
	}

	/**
	 * @return the directories links may lead into with SymlinkPolicy.ALLOWED_TARGETS.
	 */
	public java.util.List<String> getAllowedLinkTargets() {
		return allowedLinkTargets;
	}

	/**
	 * @param targets directories (paths in the server's file system) that symbolic links
	 * inside a user's root may lead into when the policy is ALLOWED_TARGETS.
	 */
	public void setAllowedLinkTargets(java.util.List<String> targets) {
		java.util.List<String> copy = new java.util.ArrayList<String>();
		if( targets != null ) {
			for(String t : targets) {
				if( t != null && !t.trim().isEmpty() ) {
					copy.add(t.trim());
				}
			}
		}
		this.allowedLinkTargets = java.util.Collections.unmodifiableList(copy);
	}

	private static java.util.List<String> parseTargets(String value) {
		java.util.List<String> ret = new java.util.ArrayList<String>();
		for(String t : value.split(",")) {
			if( !t.trim().isEmpty() ) {
				ret.add(t.trim());
			}
		}
		return java.util.Collections.unmodifiableList(ret);
	}

	public FileSourceFactory getFileSourceFactory() {
		return factory;
	}
	
	public void setFileSourceFactory(FileSourceFactory factory) {
		this.factory = factory;
	}
	
	@Override
	public void logDebug(String msg) {
		if( isSecure()) {
			msg = "(S) "+msg;
		}
		super.logDebug(msg);
	}

	@Override
	public void logDebug(String msg, Throwable error) {
		if( isSecure()) {
			msg = "(S) "+msg;
		}
		
		super.logDebug(msg, error);
	}

	@Override
	protected ILogger getLogger(String name) {
		return super.getLogger("FtpServer");
	}
}
