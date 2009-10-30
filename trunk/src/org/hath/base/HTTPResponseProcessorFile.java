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
import java.io.FileInputStream;

// this class provides two strategies for reading files. 
// the default is to read the entire file into memory, check that it is correct, then send it. this is is intended for most clients. (<200 KB/s)
// the second, used with --skip-live-hash is to use a fileinputstream to read a file as it goes along, skipping the hash check. this is intended for high-traffic clients.
public class HTTPResponseProcessorFile extends HTTPResponseProcessor {

	private HTTPSession session;
	private HVFile requestedHVFile;
	private byte[] responseBytes;
	private FileInputStream fis;
	private int off;

	public HTTPResponseProcessorFile(HTTPSession session, HVFile requestedHVFile) {
		this.session = session;
		this.requestedHVFile = requestedHVFile;
		off = 0;
	}

	public int initialize() {
		int responseStatusCode = 0;

		File file = requestedHVFile.getLocalFileRef();

		if(file.exists()) {
			try {
				if(Settings.isSkipLiveHash()) {
					fis = new FileInputStream(file);
				}
				else {
					responseBytes = FileTools.getFileContents(file);

					// since we have it loaded to memory anyway, we'll do a cheap-ish SHA1 test on it. as well as preventing corruption, this should somewhat deter funny people from replacing files with LOLcats and goatse.
					// this would be picked up by the proxy checks sooner or later even if it wasn't done here, but sooner is better.
					if(!requestedHVFile.getHash().startsWith(MiscTools.getSHAString(responseBytes))) {
						Out.warning("The file " + file + " has been corrupted!");
						responseStatusCode = 500;
						responseBytes = null;
						session.getHTTPServer().getHentaiAtHomeClient().getCacheHandler().deleteFileFromCache(requestedHVFile);
					}
				}
				
				if(responseStatusCode == 0) {
					responseStatusCode = 200;
					Stats.fileSent();
				}
			} catch(java.io.IOException e) {
				Out.warning("Failed reading content from " + file);
				responseStatusCode = 500;
			}
		}
		else {
			responseStatusCode = 404;
		}

		if(responseStatusCode == 500) {
			session.getHTTPServer().getHentaiAtHomeClient().getServerHandler().notifyCorruptedFile(requestedHVFile);			
		}

		return responseStatusCode;
	}
	
	public void cleanup() {
		if(fis != null) {
			try {
				fis.close();
			} catch(Exception e) {
				e.printStackTrace();
			}
		}
	}

	public String getContentType() {
		return requestedHVFile.getMimeType();
	}

	public int getContentLength() {
		if(responseBytes != null || fis != null) {
			return requestedHVFile.getSize();
		}
		else {
			return 0;
		}
	}

	public byte[] getBytes() {
		if(Settings.isSkipLiveHash()) {
			return getBytesRange(requestedHVFile.getSize());
		}
		else {
			return responseBytes;
		}
	}

	public byte[] getBytesRange(int len) {
		byte[] range = null;
		
		if(fis != null) {
			try {
				range = new byte[len];
				fis.read(range);
			} catch(Exception e) {
				e.printStackTrace();
				range = null;
			}
		}
		else {
			range = Arrays.copyOfRange(responseBytes, off, off + len);
		}

		off += len;
		return range;
	}
}
