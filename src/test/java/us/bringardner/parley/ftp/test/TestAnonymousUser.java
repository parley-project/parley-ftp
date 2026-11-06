package us.bringardner.parley.ftp.test;

import java.io.IOException;
import us.bringardner.parley.ftp.server.AnonymousUserRegistry;
import us.bringardner.parley.ftp.server.FtpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.net.server.IServer;

public class TestAnonymousUser extends TestFtpBaseTestClass {

	@BeforeAll
	public  static void startFtpServer() throws IOException  {
		TestFtpBaseTestClass.useFileBasedAcl=false;
		// Other test classes configure a file based registry through system properties; this
		// class uses anonymous FTP's registry regardless of test execution order.
		System.setProperty(FtpServer.FTP_PREFIX+"."+IServer.AUTHENTICATION_PROVIDER_PROPERTY, AnonymousUserRegistry.class.getName());
		TestFtpBaseTestClass.implicitSecure = false;
		
		TestFtpBaseTestClass._client = null;
		TestFtpBaseTestClass.ftpPort2 = 8022;
		TestFtpBaseTestClass.user = "ftp";
		TestFtpBaseTestClass.password = "ftp";
		TestFtpBaseTestClass.startFtpServer();
		
	}
	
	@AfterAll
	public static  void stopFtpServer() throws IOException {
		TestFtpBaseTestClass.stopFtpServer();
		
	}

}
