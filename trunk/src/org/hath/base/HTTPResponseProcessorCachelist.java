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

public class HTTPResponseProcessorCachelist extends HTTPResponseProcessor {
	public static final int BUFFER_FILENUM = 1000;

	private CacheHandler cacheHandler;
	private int max_filesize;
	private int max_filecount;
	
	private int cacheListStrlen;
	private StringBuffer fileidBuffer;
	private int fileidOffset;
	private int bufferOffset;

	public HTTPResponseProcessorCachelist(CacheHandler cacheHandler, int max_filesize, int max_filecount) {
		this.cacheHandler = cacheHandler;
		this.max_filesize = max_filesize;
		this.max_filecount = max_filecount;
	}
	
	public int initialize() {
		Out.info("Calculating cache file list parameters and preparing for send...");
		cacheListStrlen = cacheHandler.getCachedFilesStrlen(max_filesize, max_filecount);
		Out.debug("Calculated cacheListStrlen = " + cacheListStrlen);

		fileidBuffer = cacheHandler.getCachedFilesRange(max_filesize, 0, BUFFER_FILENUM);
		fileidOffset = BUFFER_FILENUM;
		
		Out.info("Sending cache list, and waiting for the server to register the cached files.. (this could take a while)");
		
		return 200;
	}

	public int getContentLength() {
		return cacheListStrlen;
	}
	
	public byte[] getBytes() {
		return getBytesRange(cacheListStrlen);
	}
	
	public byte[] getBytesRange(int len) {
		while(fileidBuffer.length() < len) {
			int buflen = Math.min(Math.min(cacheHandler.getCacheCount(), max_filecount) - fileidOffset, BUFFER_FILENUM);
			StringBuffer newbuf = cacheHandler.getCachedFilesRange(max_filesize, fileidOffset, buflen);
			fileidOffset += buflen;
			
			if(newbuf.length() < 1) {
				HentaiAtHomeClient.dieWithError("Failed to buffer requested data for cache list write. (fileidBuffer=" + fileidBuffer.length() +", len=" + len + ", max_filecount=" + max_filecount + ", max_filesize=" + max_filesize + ", fileidOffset=" + fileidOffset + ", buflen=" + buflen + ")");
			}
			
			fileidBuffer.append(newbuf);
		}
		
		byte[] returnBytes = fileidBuffer.substring(0, len).getBytes(java.nio.charset.Charset.forName("ISO-8859-1"));
		fileidBuffer.delete(0, len);

		if(returnBytes.length != len) {
			HentaiAtHomeClient.dieWithError("Length of cache list buffer (" + returnBytes.length + ") does not match requested length (" + len + ")! Bad program!");
		}
		
		return returnBytes;
	}
	
}
