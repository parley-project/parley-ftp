package us.bringardner.parley.ftp.server;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.mail.server.AbstractMailServer;
import us.bringardner.parley.mail.server.MailServerProvider;
import us.bringardner.parley.mail.store.MailLocks;
import us.bringardner.parley.mail.store.MaildropRoot;
import us.bringardner.parley.net.server.FileBasedAcl;
import us.bringardner.parley.net.server.Server;
import us.bringardner.parley.net.server.ServerContext;
import us.bringardner.parley.net.server.ServerMain;

/**
 * The FTP servers a launcher can run beside the mail servers ({@code parley.servers=...,ftp,ftps}):
 * FTP uses the mail suite's domains, users and store. Each is made from its own configuration,
 * read from {@code parley-mail.properties}, then {@code parley-mail-ftp.properties} (or the file
 * {@code parley.mail.ftp.configFile} names).
 * <ul>
 * <li>The users are the mail users ({@code parley.mail.users}, the list the mail servers share):
 * a login is {@code name@domain}, the domain must be one of the suite's local domains
 * ({@code domains} in the mail queue's {@code queue.properties}), and the user's READ, WRITE and
 * ADMIN permissions apply.</li>
 * <li>The files are in the mail store ({@code parley.mail.root}, on whatever file system
 * {@code parley.mail.fileSource} names), in the folder {@code <prefix>.root} (default
 * {@code .ftp}) under the store's root: each user's own folder is {@code <domain>/<name>} there, and
 * is all they see. The store's sharing ({@code .parley-store.properties}) lets the FTP servers in.</li>
 * <li>{@code requireTls} (default true): no login before AUTH TLS (or on implicit TLS), so the mail
 * passwords never cross the network in the clear. Needs {@code parley.mail.tls.keyStore}.</li>
 * </ul>
 * Settings (after {@code parley.mail.ftp} or {@code parley.mail.ftps}): port (21; 0 is off),
 * root, requireTls, serverId, externalAddress (what passive replies name, behind address
 * translation), bufferSize, dataTimeout, connectTimeout, uploadMode, and passivePort,
 * passiveMinPort, passiveMaxPort (shared by the FTP servers of the process).
 *
 * @since 2.0.0
 */
public final class FtpProviders {

	/** The prefix of the settings of the FTP server (explicit TLS) */
	public static final String FTP_PREFIX = "parley.mail.ftp";
	/** The prefix of the settings of the FTP server with implicit TLS */
	public static final String FTPS_PREFIX = "parley.mail.ftps";
	/** The settings file of both */
	public static final String SETTINGS_FILE = "parley-mail-ftp.properties";
	/** The setting naming a file to read instead of {@link #SETTINGS_FILE} */
	public static final String CONFIG_PROP = FTP_PREFIX + ".configFile";
	/** The folder in the store under which every user's folder is (setting after the prefix) */
	public static final String ROOT = "root";
	/** The default of {@link #ROOT}: hidden, so it is no maildrop and no mailbox */
	public static final String DEFAULT_ROOT = ".ftp";
	/** The standard port of FTP */
	public static final int FTP_PORT = 21;
	/** The standard port of FTP with implicit TLS */
	public static final int FTPS_PORT = 990;

	private FtpProviders() {
	}

	/** FTP (port 21, AUTH TLS): {@code ftp}, on unless parley.mail.ftp.port is 0 */
	public static final class Ftp extends FtpProvider {
		public Ftp() {
			super("ftp", "FTP", FTP_PREFIX, false, FTP_PORT);
		}
	}

	/** FTP with implicit TLS (port 990): {@code ftps}, on when parley.mail.ftps.port is set (and not 0) */
	public static final class Ftps extends FtpProvider {
		public Ftps() {
			super("ftps", "FTP with implicit TLS", FTPS_PREFIX, true, 0);
		}
	}

	/** An FTP server of the suite: the store's sharing is read when it starts, as the mail servers read it */
	private static final class SuiteFtpServer extends FtpServer {

		private final FileSource mailRoot;

		SuiteFtpServer(int port, boolean secure, String prefix, FileSource folder, FileSource mailRoot) {
			super(port, FTP_NAME, secure, prefix, folder);
			this.mailRoot = mailRoot;
		}

		@Override
		public synchronized void start() {
			try {
				setStoreSharing(MailLocks.sharing(mailRoot));
			} catch (IOException e) {
				logError("Can't start " + getName() + ": " + e.getMessage(), e);
				throw new java.io.UncheckedIOException(e);
			}
			super.start();
		}
	}

	/** One FTP server of the suite */
	abstract static class FtpProvider extends MailServerProvider {

		private final String prefix;
		private final boolean secure;
		private final int defaultPort;

		FtpProvider(String id, String description, String prefix, boolean secure, int defaultPort) {
			super(id, description, SETTINGS_FILE, CONFIG_PROP);
			this.prefix = prefix;
			this.secure = secure;
			this.defaultPort = defaultPort;
		}

		@Override
		public int port(Properties configuration) {
			return intProperty(configuration, prefix + ".port", defaultPort);
		}

		@Override
		public ServerMain.Service create(ServerContext context, Properties c) throws Exception {
			String root = required(c, AbstractMailServer.MAIL_ROOT_PROP);
			// like the mail servers: without a users file no one can log in
			String users = c.getProperty(AbstractMailServer.MAIL_USERS_PROP);
			String source = c.getProperty(AbstractMailServer.MAIL_FILE_SOURCE_PROP);
			Properties connection = MaildropRoot.isUrl(root) ? null
					: MaildropRoot.connectionSettings(c, AbstractMailServer.MAIL_FILE_SOURCE_PROP + ".");
			FileSource mailRoot = MaildropRoot.open(root, source, connection, AbstractMailServer.MAIL_FILE_SOURCE_PROP);
			String folderName = c.getProperty(prefix + "." + ROOT, DEFAULT_ROOT).trim();
			FileSource folder = MaildropRoot.child(mailRoot, folderName.isEmpty() ? DEFAULT_ROOT : folderName);
			if (folder.getAbsolutePath().equals(mailRoot.getAbsolutePath())) {
				throw new IOException(prefix + "." + ROOT + " must be a folder under the mail root, not the root itself");
			}
			String queue = c.getProperty("parley.mail.smtp.queue", "").trim();
			StoreDomains domains = new StoreDomains(MaildropRoot.child(mailRoot, queue.isEmpty() ? ".smtp-queue" : queue));
			FileBasedAcl list = users == null || users.trim().isEmpty() ? null : sharedUsers(context, new File(users.trim()).getCanonicalFile());

			int port = port(c);
			FtpServer server = BaseObject.makeWith(c, () -> new SuiteFtpServer(port, secure, prefix, folder, mailRoot));
			server.setAccessControl(new SuiteAcl(list, domains));
			configure(server, c);
			if (list == null) {
				server.logInfo("No users file (" + AbstractMailServer.MAIL_USERS_PROP + "): no one can log in to " + server.getName());
			}
			return ServerMain.service(server, secure ? "FTP server (implicit TLS)" : "FTP server");
		}

		private void configure(FtpServer server, Properties c) {
			server.setRequireTls(server.getBooleanProperty(server.propertyName("requireTls"), true));
			server.setServerId(server.getProperty(server.propertyName("serverId")));
			String keyStore = c.getProperty(AbstractMailServer.MAIL_TLS_KEY_STORE_PROP);
			if (keyStore != null && !keyStore.trim().isEmpty() && server.getProperty(server.propertyName(Server.PROPERTY_KEY_STORE_NAME)) == null) {
				server.setKeyStoreFileName(keyStore.trim());
				String password = c.getProperty(AbstractMailServer.MAIL_TLS_KEY_STORE_PASSWORD_PROP);
				if (password != null) {
					server.setKeyStorePassword(password);
				}
				server.setKeyStoreType(c.getProperty(AbstractMailServer.MAIL_TLS_KEY_STORE_TYPE_PROP, "PKCS12"));
			}
			if (server.isRequireTls() && !secure && !server.isTlsAvailable()) {
				server.logInfo("No TLS key store (" + AbstractMailServer.MAIL_TLS_KEY_STORE_PROP + "): no one can log in to " + server.getName()
						+ " while " + server.propertyName("requireTls") + " is true");
			}
			server.setBufferSize(intProperty(c, server.propertyName("bufferSize"), FtpServer.DEFAULT_BUFFER_SIZE));
			server.setDataTimeout(intProperty(c, server.propertyName("dataTimeout"), FtpServer.DEFAULT_DATA_TIMEOUT));
			server.setConnectTimeout(intProperty(c, server.propertyName("connectTimeout"), FtpServer.DEFAULT_CONNECT_TIMEOUT));
			String mode = c.getProperty(server.propertyName("uploadMode"));
			if (mode != null && !mode.trim().isEmpty()) {
				server.setUploadMode(FtpServer.UploadMode.parse(mode));
			}
			int passive = intProperty(c, server.propertyName("passivePort"), 0);
			if (passive > 0) {
				PassiveSocket.setControlPort(passive);
			}
			int min = intProperty(c, server.propertyName("passiveMinPort"), 0);
			int max = intProperty(c, server.propertyName("passiveMaxPort"), 0);
			if (min > 0) {
				PassiveSocket.setMinControlPort(min);
			}
			if (max > 0) {
				PassiveSocket.setMaxControlPort(max);
			}
		}

		private static String required(Properties c, String name) throws IOException {
			String ret = c.getProperty(name);
			if (ret == null || ret.trim().isEmpty()) {
				throw new IOException("The setting " + name + " is required");
			}
			return ret.trim();
		}

		/** The mail servers' user list of this file (the one they share, made here if none has yet) */
		private static FileBasedAcl sharedUsers(ServerContext context, File file) throws Exception {
			return context.shared(AbstractMailServer.SHARED_USERS_KEY + file.getPath(), FileBasedAcl.class, () -> {
				FileBasedAcl acl = new FileBasedAcl();
				acl.initialize(file);
				return acl;
			});
		}
	}
}
