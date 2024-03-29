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

import java.util.Arrays;
import java.io.File;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.lang.Thread;
import java.net.URL;
import java.net.URLConnection;

public class GalleryFileDownloader implements Runnable {
	public static final int DOWNLOAD_PENDING = 0;
	public static final int DOWNLOAD_COMPLETE = 1;
	public static final int DOWNLOAD_COMPLETE_UNCACHED = 2;
	public static final int DOWNLOAD_FAILED_INIT = -1;
	public static final int DOWNLOAD_FAILED_CONN = -2;

	private HentaiAtHomeClient client;

	private HVFile requestedHVFile;
	private File tmpFile;
	private String fileid;
	private String token;
	private int gid;
	private int page;
	private String filename;	

	private byte[] databuffer;
	private int writeoff;

	private int contentLength;
	private Thread myThread;
	private URLConnection connection;
	
	private int downloadState;

	public GalleryFileDownloader(HentaiAtHomeClient client, String fileid, String token, int gid, int page, String filename) {
		this.client = client;
		this.fileid = fileid;
		this.token = token;
		this.gid = gid;
		this.page = page;
		this.filename = filename;

		this.requestedHVFile = HVFile.getHVFileFromFileid(fileid);
		writeoff = 0;
		downloadState = DOWNLOAD_PENDING;
		myThread = new Thread(this);
	}
	
	public int initialize() {
		// we'll need to run this in a private thread so we can push data to the originating client at the same time we download it (pass-through), so we'll use a specialized version of the stuff found in URLConnectionTools
		// this also handles negotiating file browse limits with the server
		Out.info("Gallery File Download Request initializing for " + fileid + "...");
		
		try {
			boolean retry = false;
			int retval = 0;
			int tempLength = 0;
			
			do {
				retry = false;
			
				URL source = new URL("http", Settings.getRequestServer(), "/r/" + fileid + "/" + token + "/" + gid + "-" + page + "/" + filename);	
				connection = source.openConnection();
				connection.setConnectTimeout(10000);
				connection.setReadTimeout(30000);	// this doesn't always seem to work however, so we'll do it somewhat differently..
				connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows; U; Windows NT 5.1; en-US; rv:1.8.1.12) Gecko/20080201 Firefox/2.0.0.12");
				connection.connect();
				
				tempLength = connection.getContentLength();

				if(tempLength < 0) {
					Out.warning("Request host did not send Content-Length, aborting transfer." + " (" + connection + ")");
					retval = 502;
				}
				else if(tempLength > 10485760) {
					Out.warning("Content-Length is larger than 10 MB, aborting transfer." + " (" + connection + ")");
					retval = 502;
				}
				else if(tempLength != requestedHVFile.getSize()) {
					Out.warning("Reported contentLength " + contentLength + " does not match expected length of file " + fileid + " (" + connection + ")");
					
					// this could be more solid, but it's not important. this will only be tested if there is a fail, and even if the fail somehow matches the size of the error images, the server won't actually increase the limit unless we're close to it.
					if(retval == 0 && (tempLength == 28658 || tempLength == 1009)) {
						Out.warning("We appear to have reached the image limit. Attempting to contact the server to ask for a limit increase...");
						client.getServerHandler().notifyMoreFiles();
						retry = true;
						retval = 502;
					}
				} else {
					retval = 0;
				}
			} while(retry);
			
			if(retval > 0) {
				// file could not be retrieved from upstream server
				downloadState = DOWNLOAD_FAILED_INIT;
				return retval;
			}
			
			contentLength = tempLength;
			databuffer = new byte[contentLength];
			
			// at this point, everything is ready to receive data from the server and pass it to the client. in order to do this, we'll fork off a new thread to handle the reading, while this thread returns.
			// control will thus pass to the HTTPSession where this HRP's read functions will be called, and data will be written to the connection this proxy request originated from.
			
			myThread.start();
		
			return 200;
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		downloadState = DOWNLOAD_FAILED_INIT;
		return 500;
	}

	public void run() {
		int trycounter = 3;
		boolean complete = false;
		boolean success = false;

		do {
			InputStream is = null;
			BufferedInputStream bis = null;
		
			try {
				is = connection.getInputStream();
				bis = new BufferedInputStream(is);
				
				long downloadStart = System.currentTimeMillis();
				int time = 0; // counts the approximate time (in nanofortnights) since last byte was received
				int bytestatcounter = 0;

				// note: this may seen unnecessarily hackjob-ish, but because the built-in timeouts were unreliable at best (at the time of testing), this was a way to deal with the uncertainties of the interwebs. not exactly C10K stuff, but it works.
				while(writeoff < contentLength) {
					if(bis.available() > 0) {
						// read-data loop..
						
						time = 0;
						int b = bis.read();

						if(b >= 0) {
							databuffer[writeoff++] = (byte) b;
							
							if(++bytestatcounter > 1000) {
								Stats.bytesRcvd(bytestatcounter);
								bytestatcounter -= 1000;
							}
						}
						else {
							// b == -1 => EOF
							Out.warning("\nServer sent premature EOF, aborting.. (" + writeoff + " of " + contentLength + " bytes received)");
							throw new java.net.SocketException("Unexpected end of file from server");
						}
					}
					else {
						// wait-for-data loop...
					
						if(System.currentTimeMillis() - downloadStart > 300000) {
							Out.warning("\nDownload time limit has expired, aborting...");
							throw new java.net.SocketTimeoutException("Download timed out");							
						}
						else if(time > 30000) {
							Out.warning("\nTimeout detected waiting for byte " + writeoff + ", aborting..");
							throw new java.net.SocketTimeoutException("Read timed out");
						}

						time += 5;
						Thread.currentThread().sleep(5);
					}
				}
				
				Stats.bytesRcvd(bytestatcounter);
				Stats.fileRcvd();
				complete = true;
			} catch(Exception e) {
				writeoff = 0;
				Arrays.fill(databuffer, (byte) 0);
				Out.debug("Retrying.. (" + trycounter + " tries left)");
			} finally {
				try { bis.close(); } catch(Exception e2) {}
				try { is.close(); } catch(Exception e3) {}		
			}
		} while(!complete && --trycounter > 0);

		
		if(writeoff != getContentLength()) {
			Out.debug("Requested file " + fileid + " is incomplete, and was not stored.");				
		} else if(! MiscTools.getSHAString(databuffer).equals(requestedHVFile.getHash())) {
			Out.debug("Requested file " + fileid + " is corrupt, and was not stored.");				
		} else {
			try {
				CacheHandler cacheHandler = client.getCacheHandler();
				tmpFile = File.createTempFile("hathproxy_", "", cacheHandler.getTmpDir());
				FileTools.putFileContents(tmpFile, databuffer);
				
				if(cacheHandler.moveFileToCacheDir(tmpFile, requestedHVFile)) {
					cacheHandler.addFileToActiveCache(requestedHVFile);
					// During server-initiated file distributes and proxy tests against other clients, the file is automatically registered for this client by the server, but this doesn't happen during client-initiated H@H Downloader or H@H Proxy downloads.
					// So we'll instead send regular updates to the server about downloaded files, whenever a file is added this way.
					cacheHandler.addPendingRegisterFile(requestedHVFile);
					Out.debug("Requested file " + fileid + " was successfully stored in cache.");
					success = true;
				}
				else {
					// uncached because of size limit, we don't delete it yet
					if(requestedHVFile.getSize()>Settings.CACHE_MAX_FILESIZE){
						downloadState = DOWNLOAD_COMPLETE_UNCACHED;
						return;
					}else{
						tmpFile.delete();
						Out.debug("Requested file " + fileid + " somehow exists, and was dropped.");
					}
				}
			} catch(Exception e) {
				e.printStackTrace();
			}
		}
		
		if(success) {
			downloadState = DOWNLOAD_COMPLETE;
		} else {
			downloadState = DOWNLOAD_FAILED_CONN;
		}
	}
	
	public String getContentType() {
		return requestedHVFile.getMimeType();
	}

	public int getContentLength() {
		return requestedHVFile.getSize();
	}

	public int getCurrentWriteoff() {
		return writeoff;
	}
	
	public int getDownloadState() {
		return downloadState;
	}
	
	public byte[] getDownloadBufferRange(int readoff, int endoff) {
		return Arrays.copyOfRange(databuffer, readoff, endoff);
	}
	
	public File getTmpFile(){
		return tmpFile;
	}
}
