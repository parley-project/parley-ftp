package us.bringardner.parley.ftp.test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the tests keep their files, so they don't depend on the working directory or on the
 * build's output folder being called target.
 */
public final class FtpTestSupport {

	private static Path scratchRoot;

	private FtpTestSupport() {
	}

	/**
	 * A local directory for this test run's files (server roots, keystores...), under the system
	 * temp directory. It is new for each run, so nothing left by an earlier run gets in the way,
	 * and kept afterwards so it can be looked at. macOS and Linux clear their temp directories
	 * over time; on Windows, clear %TEMP% yourself. The run's root is printed once.
	 * @param name the file's or directory's name in the run's root
	 * @return its absolute path (not created)
	 */
	public static synchronized String scratchDir(String name) {
		if( scratchRoot == null ) {
			try {
				scratchRoot = Files.createTempDirectory("parley-ftp-tests");
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}
			System.out.println("parley-ftp test files: "+scratchRoot);
		}
		return scratchRoot.resolve(name).toString();
	}

	/** @return the TestFiles directory (src/test/resources/TestFiles), found on the test class path */
	public static File testFilesDir() {
		URL url = FtpTestSupport.class.getResource("/TestFiles");
		if( url == null ) {
			throw new IllegalStateException("TestFiles is not on the test class path (src/test/resources)");
		}
		try {
			return new File(url.toURI());
		} catch(URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}
}
