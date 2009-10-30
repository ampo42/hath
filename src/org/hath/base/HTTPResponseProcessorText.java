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

public class HTTPResponseProcessorText extends HTTPResponseProcessor {

	private HVFile requestedHVFile;
	private byte[] responseBytes;
	private int off;
	
	public HTTPResponseProcessorText(String responseBody) {
		int strlen = responseBody.length();
		
		if(strlen > 0) {
			Out.debug("Response Written:");

			if(strlen < 10000) {
				Out.debug(responseBody);
			}
			else {
				Out.debug("tl;dw");		
			}
		}

		this.responseBytes = responseBody.getBytes(java.nio.charset.Charset.forName("ISO-8859-1"));
		off = 0;
	}
	
	public int getContentLength() {
		if(responseBytes != null) {
			return responseBytes.length;
		}
		else {
			return 0;
		}
	}
	
	public byte[] getBytes() {
		return responseBytes;
	}
	
	public byte[] getBytesRange(int len) {
		byte[] range = Arrays.copyOfRange(responseBytes, off, Math.min(responseBytes.length, off + len));
		off += len;
		return range;
	}
}
