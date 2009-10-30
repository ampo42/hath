/*

Copyright 2008-2009 E-Hentai.org
http://forums.e-hentai.org/
ehentai@gmail.com

This file is part of Hentai@Home.

Hentai@Home is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Hentai@Home is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Hentai@Home.  If not, see <http://www.gnu.org/licenses/>.

*/

package org.hath.base;

import java.io.File;
import java.util.Hashtable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.sql.*;
import org.sqlite.*;

public class CacheHandler {

	private HentaiAtHomeClient client;
	private static File importdir = null, cachedir = null, tmpdir = null;
	private Connection sqlite = null;

	private int cacheCount;
	private long cacheSize;
	
	protected PreparedStatement cacheIndexClearActive, cacheIndexCountStats;
	protected PreparedStatement queryCachedFileStrlen, queryCachedFileLasthit, queryCachedFileWithMaxSize, queryCachedFileSortOnLasthit;
	protected PreparedStatement insertCachedFile, updateCachedFileLasthit, updateCachedFileActive;
	protected PreparedStatement deleteCachedFile, deleteCachedFileInactive;
	
	protected ArrayList<CachedFile> recentlyAccessed;
	protected ArrayList<HVFile> pendingRegister;
	protected long recentlyAccessedFlush;
	
	public CacheHandler(HentaiAtHomeClient client) {
		this.client = client;
		this.recentlyAccessed = new ArrayList<CachedFile>(100);
		this.pendingRegister = new ArrayList<HVFile>(50);
		
		Out.info("CacheHandler: Initializing database engine...");
		try {
			String db = "data/hath.db";
			File dbfile = new File(db);

			if(dbfile.exists()) {
				File dbfileBackup = new File(db + ".bak-temp");
				if(dbfileBackup.exists()) {
					dbfileBackup.delete();
				}
				
				if(FileTools.copy(dbfile, dbfileBackup)) {
					Out.info("CacheHandler: Database file " + db + " backed up as " + dbfileBackup);
				}
				else {
					Out.warning("CacheHandler: Failed to create database backup copy - check free space and permissions");				
				}
			}
		
			Class.forName("org.sqlite.JDBC");
			sqlite = DriverManager.getConnection("jdbc:sqlite:" + db);
			DatabaseMetaData dma = sqlite.getMetaData();
			Out.info("CacheHandler: Using " + dma.getDatabaseProductName() + " " + dma.getDatabaseProductVersion() + " over " + dma.getDriverName() + " " + dma.getJDBCMajorVersion() + "." + dma.getJDBCMinorVersion() + " running in " + dma.getDriverVersion() + " mode");

			Out.info("CacheHandler: Initializing database tables...");
			Statement stmt = sqlite.createStatement();
			stmt.executeUpdate("CREATE TABLE IF NOT EXISTS CacheIndex (fileid VARCHAR(70)  NOT NULL, lasthit INT UNSIGNED NOT NULL, filesize INT UNSIGNED NOT NULL, strlen TINYINT UNSIGNED NOT NULL, active BOOLEAN NOT NULL, PRIMARY KEY(fileid));");
			stmt.executeUpdate("CREATE INDEX IF NOT EXISTS Lasthit ON CacheIndex (lasthit DESC);");
			//stmt.executeUpdate("UPDATE CacheIndex SET active=0;");

			Out.info("CacheHandler: Optimizing database...");
			stmt.executeUpdate("VACUUM;");
			
			cacheIndexClearActive = sqlite.prepareStatement("UPDATE CacheIndex SET active=0;");
			cacheIndexCountStats = sqlite.prepareStatement("SELECT COUNT(*), SUM(filesize) FROM CacheIndex;");
			
			queryCachedFileStrlen = sqlite.prepareStatement("SELECT COUNT(strlen), SUM(strlen) FROM CacheIndex WHERE fileid IN (SELECT fileid FROM CacheIndex WHERE filesize<=? ORDER BY lasthit DESC LIMIT ?);");
			queryCachedFileLasthit = sqlite.prepareStatement("SELECT lasthit FROM CacheIndex WHERE fileid=?;");
			queryCachedFileWithMaxSize = sqlite.prepareStatement("SELECT fileid, lasthit FROM CacheIndex WHERE filesize<=? ORDER BY lasthit DESC LIMIT ?, ?;");
			queryCachedFileSortOnLasthit = sqlite.prepareStatement("SELECT fileid, lasthit FROM CacheIndex ORDER BY lasthit LIMIT ?, ?;");
			insertCachedFile = sqlite.prepareStatement("INSERT OR REPLACE INTO CacheIndex (fileid, lasthit, filesize, strlen, active) VALUES (?, ?, ?, ?, 1);");
			updateCachedFileActive = sqlite.prepareStatement("UPDATE CacheIndex SET active=1 WHERE fileid=?;");
			updateCachedFileLasthit = sqlite.prepareStatement("UPDATE CacheIndex SET lasthit=? WHERE fileid=?;");
			deleteCachedFile = sqlite.prepareStatement("DELETE FROM CacheIndex WHERE fileid=?;");
			deleteCachedFileInactive = sqlite.prepareStatement("DELETE FROM CacheIndex WHERE active=0;");			

			Out.info("CacheHandler: Rotating database backups");
			
			(new File(db + ".bak.3")).delete();
			
			for(int i=3; i>0; i--) {
				(new File(db + ".bak." + (i-1))).renameTo(new File(db + ".bak." + i));
			}
			
			(new File(db + ".bak")).renameTo(new File(db + ".bak.0"));
			(new File(db + ".bak-temp")).renameTo(new File(db + ".bak"));
			
			Out.info("CacheHandler: Database initialized");
		} catch(Exception e) {
			Out.error("CacheHandler: Failed to initialize SQLite database engine");
			client.dieWithError(e);
		}
	}

	public void initializeCacheHandler() throws java.io.IOException {
		Out.info("CacheHandler: Initializing the cache system...");

		importdir = FileTools.checkAndCreateDir(new File("import"));
		tmpdir = FileTools.checkAndCreateDir(new File("tmp"));
		cachedir = FileTools.checkAndCreateDir(new File("cache"));

		// delete orphans from the temp dir

		File[] tmpfiles = tmpdir.listFiles();

		for(File tmpfile: tmpfiles) {
			if(tmpfile.isFile()) {
				Out.debug("Deleted orphaned temporary file " + tmpfile);
				tmpfile.delete();
			}
			else {
				Out.warning("Found a non-file " + tmpfile + " in the temp directory, won't delete.");
			}
		}
		
		if(Settings.isQuickStart()) {
			try {
				ResultSet rs = cacheIndexCountStats.executeQuery();
				if(rs.next()) {
					cacheCount = rs.getInt(1);
					cacheSize = rs.getLong(2);
				}
				rs.close();
			} catch(Exception e) {
				Out.error("CacheHandler: Failed to perform database operation");
				client.dieWithError(e);
			}
			
			updateStats();
			flushRecentlyAccessed();
		} else {
			populateInternalCacheTable();
		}

		if(!checkAndFreeDiskSpace(cachedir, true)) {
			Out.warning("ClientHandler: There is not enough space left on the disk to add more files to the cache.");
		}
	}
	
	public HVFile getHVFile(String fileid, boolean hit) {
		CachedFile cf = new CachedFile(fileid);

		if(cf.isValid()) {
			if(hit) {
				cf.hit();
			}
			
			return cf.getHVFile();
		}
		else {
			return null;
		}
	}

	// note: this will just move the file into its correct location. addFileToActiveCache MUST be called afterwards to import the file into the necessary datastructures.
	// otherwise, the file will not be available until the client is restarted, and even then not if --quickstart is used.
	public boolean moveFileToCacheDir(File file, HVFile hvFile) {
		if(hvFile.getSize() > 1024*1024) {
			Out.warning("CacheHandler: File " + file + " is too large to cache.");
		}
		else if(checkAndFreeDiskSpace(file)) {
			File toFile = hvFile.getLocalFileRef();

			try {
				FileTools.checkAndCreateDir(toFile.getParentFile());
				
				if(file.renameTo(toFile)) {
					Out.debug("CacheHandler: Imported file " + file + " to " + hvFile.getFileid());
					return true;
				}
				else {
					Out.warning("CacheHandler: Failed to move file " + file);
				}
			} catch(java.io.IOException e) {
				e.printStackTrace();
				Out.warning("CacheHandler: Encountered exception " + e + " when moving file " + file);
			}
		}
		
		return false;
	}

	public void addFileToActiveCache(HVFile hvFile) {
		addFileToActiveCache(hvFile, true);
	}
	
	private void addFileToActiveCache(HVFile hvFile, boolean newFile) {
		if(newFile) {
			CachedFile cf = new CachedFile(hvFile.getFileid(), 0);
			cf.hit();
		}
		else {
			try {
				synchronized(sqlite) {
					updateCachedFileActive.setString(1, hvFile.getFileid());
					updateCachedFileActive.executeUpdate();
				}
			} catch(Exception e) {
				Out.error("CacheHandler: Failed to perform database operation");
				client.dieWithError(e);
			}
		}
	
		++cacheCount;
		cacheSize += hvFile.getSize();
		updateStats();
	}
	
	// schedule file for being registered on the server
	public void addPendingRegisterFile(HVFile hvFile) {
		synchronized(pendingRegister) {
			Out.debug("Added " + hvFile + " to pendingRegister");
			pendingRegister.add(hvFile);
		
			if(pendingRegister.size() >= 50) {
				// this call also empties the list
				client.getServerHandler().notifyRegisterFiles(pendingRegister);
			}
		}
	}

	public void deleteFileFromCache(HVFile toRemove) {
		try {
			synchronized(sqlite) {
				deleteCachedFile.setString(1, toRemove.getFileid());
				deleteCachedFile.executeUpdate();
				--cacheCount;
				cacheSize -= toRemove.getSize();
				toRemove.getLocalFileRef().delete();
				Out.info("CacheHandler: Deleted cached file " + toRemove.getFileid());
				updateStats();
			}
		} catch(Exception e) {
			Out.error("CacheHandler: Failed to perform database operation");
			client.dieWithError(e);
		}
	}

	private void populateInternalCacheTable() {
		try {
			cacheIndexClearActive.executeUpdate();										
		
			cacheCount = 0;
			cacheSize = 0;

			int knownFiles = 0;
			int newFiles = 0;

			// loop through the import directory and import files from there

			Out.info("CacheHandler: Importing manually added files...");
			int importedFiles = processImportDirectoryRecursively(importdir);
			Out.info("CacheHandler: Finished importing files (" + importedFiles + " files added).");

			// load all the files directly from the cache directory itself and initialize the stored last access times for each file. last access times are used for the LRU-style cache.

			Out.info("CacheHandler: Loading cache.. (this could take a while)");

			File[] scdirs = cachedir.listFiles();
			java.util.Arrays.sort(scdirs);

			try {
				// we're doing some SQLite operations here without synchronizing on the SQLite connection. the program is single-threaded at this point, so it should not be a real problem.
				
				int loadedFiles = 0;
				sqlite.setAutoCommit(false);

				for(File scdir : scdirs) {
					if(scdir.isDirectory()) {
						File[] cfiles = scdir.listFiles();
						java.util.Arrays.sort(cfiles);
						
						for(File cfile : cfiles) {
							boolean newFile = false;

							synchronized(sqlite) {
								queryCachedFileLasthit.setString(1, cfile.getName());
								ResultSet rs = queryCachedFileLasthit.executeQuery();
								newFile = !rs.next();
								rs.close();
							}

							HVFile hvFile = HVFile.getHVFileFromFile(cfile, Settings.isVerifyCache() || newFile);

							if(hvFile != null) {
								addFileToActiveCache(hvFile, newFile);

								if(newFile) {
									++newFiles;
									Out.info("CacheHandler: Verified and loaded file " + cfile);
								}
								else {
									++knownFiles;
								}
								
								if(++loadedFiles % 1000 == 0) {
									Out.info("CacheHandler: Loaded " + loadedFiles + " files so far...");
								}
							}
							else {
								Out.warning("CacheHandler: The file " + cfile + " was corrupt. It is now deleted.");
								cfile.delete();
							}
						}
					}
					else {
						scdir.delete();
					}
					
					flushRecentlyAccessed(false);
				}

				sqlite.commit();
				sqlite.setAutoCommit(true);
				
				synchronized(sqlite) {
					int purged = deleteCachedFileInactive.executeUpdate();
					Out.info("CacheHandler: Purged " + purged + " nonexisting files from database.");
				}
			} catch(Exception e) {
				Out.error("CacheHandler: Failed to perform database operation");
				client.dieWithError(e);
			}
			
			Out.info("CacheHandler: Loaded " + knownFiles + " known files.");
			Out.info("CacheHandler: Loaded " + newFiles + " new files.");
			Out.info("CacheHandler: Finished initializing the cache (" + cacheCount + " files, " + cacheSize + " bytes)");
			
			updateStats();
		} catch(Exception e) {
			e.printStackTrace();
			HentaiAtHomeClient.dieWithError("Failed to initialize the cache.");
		}
	}

	private int processImportDirectoryRecursively(File dir) {
		int addedFiles = 0;
		File[] files = dir.listFiles();

		for(File file : files) {
			if(file.isDirectory()) {
				Out.debug("CacheHandler: Import is poking through " + file + "...");
				addedFiles += processImportDirectoryRecursively(file);
			}
			else {
				HVFile hvFile = HVFile.importHVFile(file);

				if(hvFile != null) {
					if(!hvFile.getLocalFileRef().exists()) {
						moveFileToCacheDir(file, hvFile);
						addedFiles++;
					}
					else {
						Out.debug("CacheHandler: File " + file + " already exists in cache");
					}
				}
				else {
					Out.warning("CacheHandler: Could not generate HVFile abstract for " + file);
				}
			}
			
			if(file.exists()) {			
				if(!file.delete()) {
					Out.warning("CacheHandler: Failed to delete file " + file);
				}
			}
		}

		return addedFiles;
	}
	
	public boolean recheckFreeDiskSpace() {
		return checkAndFreeDiskSpace(cachedir);
	}

	private boolean checkAndFreeDiskSpace(File file) {
		return checkAndFreeDiskSpace(file, false);
	}

	private synchronized boolean checkAndFreeDiskSpace(File file, boolean noServerDeleteNotify) {
		if(file == null) {
			HentaiAtHomeClient.dieWithError("CacheHandler: checkAndFreeDiskSpace needs a file handle to calculate free space");
		}

		int bytesNeeded = file.isDirectory() ? 0 : (int) file.length();
		long cacheLimit = Settings.getDiskLimitBytes();

		Out.debug("CacheHandler: Checking disk space (adding " + bytesNeeded + " bytes: cacheSize=" + cacheSize + ", cacheLimit=" + cacheLimit + ", cacheFree=" + (cacheLimit - cacheSize) + ")");

		// we'll free ten times the size of the file or 20 files, whichever is largest.
		
		long bytesToFree = 0;
		
		if(cacheSize > cacheLimit) {
			bytesToFree = cacheSize - cacheLimit;
		}
		else if(cacheSize + bytesNeeded - cacheLimit > 0) {
			bytesToFree = bytesNeeded * 10;
		}

		int filesToFree = bytesToFree > 0 ? 20 : 0;
		
		if(bytesToFree > 0 || filesToFree > 0) {
			Out.info("CacheHandler: Freeing at least " + bytesToFree + " bytes / " + filesToFree + " files...");
			List<HVFile> deletedFiles = Collections.checkedList(new ArrayList<HVFile>(), HVFile.class);

			try {			
				synchronized(sqlite) {
					queryCachedFileSortOnLasthit.setInt(1, 0);
					queryCachedFileSortOnLasthit.setInt(2, 1);
				
					while((filesToFree > 0 || bytesToFree > 0) && cacheCount > 0) {
						ResultSet rs = queryCachedFileSortOnLasthit.executeQuery();
						HVFile toRemove = null;
						
						if(rs.next()) {
							toRemove = HVFile.getHVFileFromFileid(rs.getString(1));
						}

						rs.close();
						
						if(toRemove != null) {
							bytesToFree -= toRemove.getSize();
							filesToFree -= 1;
							deletedFiles.add(toRemove);
							deleteFileFromCache(toRemove);						
						}
					}
				}
			} catch(Exception e) {
				Out.error("CacheHandler: Failed to perform database operation");
				client.dieWithError(e);
			}			

			if(!noServerDeleteNotify) {
				client.getServerHandler().notifyUncachedFiles(deletedFiles);
			}
		}

		if(Settings.isSkipFreeSpaceCheck()) {
			Out.debug("CacheHandler: Disk free space check is disabled.");
			return true;		
		}
		else {
			long diskFreeSpace = file.getFreeSpace();

			if(diskFreeSpace < Math.max(Settings.getDiskMinRemainingBytes(), 104857600)) {
				// if the disk fills up, we  stop adding files instead of starting to remove files from the cache, to avoid being unintentionally squeezed out by other programs
				Out.warning("CacheHandler: Cannot meet space constraints: Disk free space limit reached (" + diskFreeSpace + " bytes free on device)");
				return false;
			}
			else {
				Out.debug("CacheHandler: Disk space constraints met (" + diskFreeSpace + " bytes free on device)");
				return true;
			}
		}
	}
	
	public synchronized void processBlacklist(long deltatime, boolean noServerDeleteNotify) {
		Out.info("CacheHandler: Retrieving list of blacklisted files...");
		String[] blacklisted = client.getServerHandler().getBlacklist(deltatime);
		
		if(blacklisted == null) {
			Out.warning("CacheHandler: Failed to retrieve file blacklist, will try again later.");
			return;
		}
		
		Out.info("CacheHandler: Looking for and deleting blacklisted files...");

		int counter = 0;
		List<HVFile> deletedFiles = Collections.checkedList(new ArrayList<HVFile>(), HVFile.class);
		
		try {			
			synchronized(sqlite) {
				for(String fileid : blacklisted) {
					queryCachedFileLasthit.setString(1, fileid);
					ResultSet rs = queryCachedFileLasthit.executeQuery();
					HVFile toRemove = null;
					
					if(rs.next()) {
						toRemove = HVFile.getHVFileFromFileid(fileid);
					}

					rs.close();
					
					if(toRemove != null) {
						Out.info("CacheHandler: Removing blacklisted file " + fileid);
						++counter;
						deletedFiles.add(toRemove);
						deleteFileFromCache(toRemove);						
					}
				}
			}
		} catch(Exception e) {
			Out.error("CacheHandler: Failed to perform database operation");
			client.dieWithError(e);
		}


		if(!noServerDeleteNotify) {
			client.getServerHandler().notifyUncachedFiles(deletedFiles);
		}			

		Out.info("CacheHandler: " + counter + " blacklisted files were removed.");
	}
	
	private void updateStats() {
		Stats.setCacheCount(cacheCount);
		Stats.setCacheSize(cacheSize);
	}
	
	public int getCacheCount() {
		return cacheCount;
	}
	
	public int getCachedFilesStrlen(int maxsize, int maxcount) {
		int size = 0;

		try {
			synchronized(sqlite) {
				queryCachedFileStrlen.setInt(1, maxsize <= 0 ? 1048576 : maxsize);
				queryCachedFileStrlen.setInt(2, maxcount);
				ResultSet rs = queryCachedFileStrlen.executeQuery();
				
				if(rs.next()) {
					size = rs.getInt(1) + rs.getInt(2);
				}
				
				rs.close();
			}
		} catch(Exception e) {
			Out.error("CacheHandler: Failed to perform database operation");
			client.dieWithError(e);
		}
		
		return size;
	}
	
	public StringBuffer getCachedFilesRange(int maxsize, int off, int len) {		
		StringBuffer sb = new StringBuffer(len * 63);

		try {
			synchronized(sqlite) {
				queryCachedFileWithMaxSize.setInt(1, maxsize <= 0 ? 1048576 : maxsize);
				queryCachedFileWithMaxSize.setInt(2, off);
				queryCachedFileWithMaxSize.setInt(3, len);
				ResultSet rs = queryCachedFileWithMaxSize.executeQuery();
				
				while(rs.next()) {
					CachedFile cf = new CachedFile(rs.getString(1), rs.getLong(2));
					sb.append(cf.getFileid() + "\n");
				}
				
				rs.close();
			}
		} catch(Exception e) {
			Out.error("CacheHandler: Failed to perform database operation");
			client.dieWithError(e);
		}
		
		return sb;
	}

	public static File getCacheDir() {
		return cachedir;
	}

	public static File getTmpDir() {
		return tmpdir;
	}

	public void flushRecentlyAccessed() {
		flushRecentlyAccessed(true);
	}

	private void flushRecentlyAccessed(boolean disableAutocommit) {
		synchronized(recentlyAccessed) {
			recentlyAccessedFlush = System.currentTimeMillis();
	
			if(recentlyAccessed.size() > 0) {
				try {
					synchronized(sqlite) {
						if(disableAutocommit) {
							sqlite.setAutoCommit(false);
						}

						for(CachedFile cf : recentlyAccessed) {
							String fileid = cf.getFileid();
							long lasthit = cf.getLasthit();
							
							updateCachedFileLasthit.setLong(1, lasthit);
							updateCachedFileLasthit.setString(2, fileid);
							int affected = updateCachedFileLasthit.executeUpdate();
							
							if(affected == 0) {
								insertCachedFile.setString(1, fileid);
								insertCachedFile.setLong(2, lasthit);
								insertCachedFile.setInt(3, cf.getHVFile().getSize());
								insertCachedFile.setInt(4, fileid.length());
								insertCachedFile.executeUpdate();										
							}
						}

						recentlyAccessed.clear();

						if(disableAutocommit) {
							sqlite.setAutoCommit(true);
						}
					}
				} catch(Exception e) {
					Out.error("CacheHandler: Failed to perform database operation");
					client.dieWithError(e);
				}
			}
		}
	}
	
	
	private class CachedFile {
		private String fileid;
		private long lasthit;
		private boolean valid;

		public CachedFile(String fileid, long lasthit) {
			this.fileid = fileid;
			this.lasthit = lasthit;
			this.valid = true;
		}
		
		public CachedFile(String fileid) {
			this.fileid = fileid;
			this.lasthit = 0;
			this.valid = false;
			
			try {
				synchronized(sqlite) {
					queryCachedFileLasthit.setString(1, fileid);
					ResultSet rs = queryCachedFileLasthit.executeQuery();
					if(rs.next()) {
						this.lasthit = rs.getLong(1);
						this.valid = true;
					}
					rs.close();
				}
			} catch(Exception e) {
				Out.error("CacheHandler: Failed to perform database operation");
				client.dieWithError(e);
			}
		}

		public String getFileid() {
			return fileid;
		}
		
		public HVFile getHVFile() {
			return HVFile.getHVFileFromFileid(fileid);
		}

		public long getLasthit() {
			return lasthit;
		}
		
		public boolean isValid() {
			return valid;
		}

		public void hit() {
			lasthit = (long) Math.floor(System.currentTimeMillis() / 1000);

			synchronized(recentlyAccessed) {
				recentlyAccessed.add(this);
			}
		}
	}
}
