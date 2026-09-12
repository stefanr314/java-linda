package rs.ac.bg.etf.kdp.common;

import rs.ac.bg.etf.kdp.common.exceptions.JarMisconfiguredException;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class JarUtil {

	public static String getMainClassBinaryName(File file) throws IOException {
		try (JarFile jarFile = new JarFile(file)) {
			Manifest manifest = jarFile.getManifest();
			if (manifest != null) {

				String mainClass = manifest.getMainAttributes().getValue("Main-Class");
				if (mainClass == null) {
					throw new JarMisconfiguredException("JAR does not contain Main Class attribute in MANIFEST.MF");

				}

				return mainClass;
			}
			throw new JarMisconfiguredException("JAR does not contain Main Class attribute in MANIFEST.MF");
		}
	}
}