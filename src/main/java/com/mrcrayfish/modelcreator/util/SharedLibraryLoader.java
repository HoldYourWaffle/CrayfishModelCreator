package com.mrcrayfish.modelcreator.util;

/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads shared libraries from JAR files. Call {@link SharedLibraryLoader#load()
 * to load the required LWJGL 3 native shared libraries.
 * 
 * @author mzechner
 * @author Nathan Sweet
 */
public class SharedLibraryLoader {
	
	public static final boolean isWindows = System.getProperty("os.name").contains("Windows");
	public static final boolean isLinux = System.getProperty("os.name").contains("Linux");
	public static final boolean isMac = System.getProperty("os.name").contains("Mac");
	public static final boolean is64Bit = System.getProperty("os.arch").equals("amd64")
			|| System.getProperty("os.arch").equals("x86_64");
	
	private static final boolean usingJws;
	
	static {
		if (!isWindows && !isLinux && !isMac) {
			throw new UnsupportedOperationException("Unknown platform: " + System.getProperty("os.name"));
		}
		
		// Don't extract natives if using JWS.
		boolean failed = false;
		try {
			Method method = Class.forName("javax.jnlp.ServiceManager").getDeclaredMethod("lookup",
					new Class[] { String.class });
			method.invoke(null, "javax.jnlp.PersistenceService");
		} catch (Throwable ex) {
			failed = true;
		}
		usingJws = !failed;
	}
	
	/**
	 * Extracts the LWJGL native libraries from the classpath and sets the
	 * "org.lwjgl.librarypath" system property.
	 */
	public static synchronized void load(boolean disableOpenAL) {
		if (usingJws)
			return;
		
		SharedLibraryLoader loader = new SharedLibraryLoader();
		File nativesDir = null;
		try {
			if (SharedLibraryLoader.isWindows) {
				
				nativesDir = loader.extractFile(SharedLibraryLoader.is64Bit ? "lwjgl64.dll" : "lwjgl.dll", null)
						.getParentFile();
				
				if (!disableOpenAL)
					loader.extractFile(SharedLibraryLoader.is64Bit ? "OpenAL64.dll" : "OpenAL32.dll",
							nativesDir.getName());
				
			} else if (SharedLibraryLoader.isMac) {
				
				nativesDir = loader.extractFile("liblwjgl.dylib", null).getParentFile();
				
				if (!disableOpenAL)
					loader.extractFile("openal.dylib", nativesDir.getName());
				
			} else if (SharedLibraryLoader.isLinux) {
				
				nativesDir = loader.extractFile(SharedLibraryLoader.is64Bit ? "liblwjgl64.so" : "liblwjgl.so", null)
						.getParentFile();
				
				if (!disableOpenAL)
					loader.extractFile(SharedLibraryLoader.is64Bit ? "libopenal64.so" : "libopenal.so",
							nativesDir.getName());
				
			} else {
				throw new UnsupportedOperationException("Unknown platform: " + System.getProperty("os.name"));
			}
		} catch (Throwable ex) {
			throw new Error("Unable to extract LWJGL natives.", ex);
		}
		
		System.setProperty("org.lwjgl.librarypath", nativesDir.getAbsolutePath());
	}
	
	private String nativesJar;
	
	private SharedLibraryLoader() {
	}
	
	/** Returns a CRC of the remaining bytes in the stream. */
	private String crc(InputStream input) {
		if (input == null)
			throw new IllegalArgumentException("input cannot be null.");
		
		CRC32 crc = new CRC32();
		byte[] buffer = new byte[4096];
		try (input) {
			while (true) {
				int length = input.read(buffer);
				if (length == -1)
					break;
				crc.update(buffer, 0, length);
			}
		} catch (Exception ex) {
		}
		return Long.toString(crc.getValue(), 16);
	}
	
	private InputStream readFile(String path) {
		if (nativesJar == null) {
			InputStream input = SharedLibraryLoader.class.getResourceAsStream("/" + path);
			if (input == null)
				throw new RuntimeException("Unable to read file for extraction: " + path);
			return input;
		}
		
		// Read from JAR.
		try (ZipFile file = new ZipFile(nativesJar)) {
			
			ZipEntry entry = file.getEntry(path);
			if (entry == null)
				throw new RuntimeException("Couldn't find '" + path + "' in JAR: " + nativesJar);
			return file.getInputStream(entry);
			
		} catch (IOException ex) {
			throw new RuntimeException("Error reading '" + path + "' in JAR: " + nativesJar, ex);
		}
	}
	
	/**
	 * Extracts the specified file into the temp directory if it does not already
	 * exist or the CRC does not match. If file extraction fails and the file exists
	 * at java.library.path, that file is returned.
	 * 
	 * @param sourcePath The file to extract from the classpath or JAR.
	 * @param dirName    The name of the subdirectory where the file will be
	 *                   extracted. If null, the file's CRC will be used.
	 * @return The extracted file.
	 */
	private File extractFile(String sourcePath, String dirName) {
		try {
			String sourceCrc = crc(readFile(sourcePath));
			if (dirName == null)
				dirName = sourceCrc;
			
			File extractedFile = getExtractedFile(dirName, new File(sourcePath).getName());
			return extractFile(sourcePath, sourceCrc, extractedFile);
		} catch (RuntimeException ex) {
			// Fallback to file at java.library.path location, eg for applets.
			File file = new File(System.getProperty("java.library.path"), sourcePath);
			if (file.exists())
				return file;
			throw ex;
		}
	}
	
	/**
	 * Returns a path to a file that can be written. Tries multiple locations and
	 * verifies writing succeeds.
	 */
	private File getExtractedFile(String dirName, String fileName) {
		// Temp directory with username in path.
		File idealFile = new File(
				System.getProperty("java.io.tmpdir") + "/lwjgl" + System.getProperty("user.name") + "/" + dirName,
				fileName);
		if (canWrite(idealFile))
			return idealFile;
		
		// System provided temp directory.
		try {
			File file = File.createTempFile(dirName, null);
			if (file.delete()) {
				file = new File(file, fileName);
				if (canWrite(file))
					return file;
			}
		} catch (IOException ignored) {
		}
		
		// User home.
		File file = new File(System.getProperty("user.home") + "/.lwjgl/" + dirName, fileName);
		if (canWrite(file))
			return file;
		
		// Relative directory.
		file = new File(".temp/" + dirName, fileName);
		if (canWrite(file))
			return file;
		
		return idealFile; // Will likely fail, but we did our best.
	}
	
	/**
	 * Returns true if the parent directories of the file can be created and the
	 * file can be written.
	 */
	@SuppressWarnings("resource")
	private boolean canWrite(File file) {
		File parent = file.getParentFile();
		File testFile;
		if (file.exists()) {
			if (!file.canWrite() || !canExecute(file))
				return false;
			// Don't overwrite existing file just to check if we can write to directory.
			testFile = new File(parent, UUID.randomUUID().toString());
		} else {
			parent.mkdirs();
			if (!parent.isDirectory())
				return false;
			testFile = file;
		}
		try {
			new FileOutputStream(testFile).close();
			if (!canExecute(testFile))
				return false;
			return true;
		} catch (Throwable ex) {
			return false;
		} finally {
			testFile.delete();
		}
	}
	
	private boolean canExecute(File file) {
		try {
			if (file.canExecute())
				return true;
			
			file.setExecutable(true, false);
			return file.canExecute();
		} catch (Exception ignored) {
		}
		
		return false;
	}
	
	private File extractFile(String sourcePath, String sourceCrc, File extractedFile) {
		String extractedCrc = null;
		if (extractedFile.exists()) {
			try {
				extractedCrc = crc(new FileInputStream(extractedFile));
			} catch (FileNotFoundException ignored) {
			}
		}
		extractedFile.getParentFile().mkdirs();
		
		// If file doesn't exist or the CRC doesn't match, extract it to the temp dir.
		if (extractedCrc == null || !extractedCrc.equals(sourceCrc)) {
			try (InputStream input = readFile(sourcePath);
				 FileOutputStream output = new FileOutputStream(extractedFile)) {
				byte[] buffer = new byte[4096];
				while (true) {
					int length = input.read(buffer);
					if (length == -1)
						break;
					output.write(buffer, 0, length);
				}
			} catch (IOException ex) {
				throw new RuntimeException(
						"Error extracting file: " + sourcePath + "\nTo: " + extractedFile.getAbsolutePath(), ex);
			}
		}
		
		return extractedFile;
	}
	
}
