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

public abstract class HTTPResponseProcessor {

	public String getContentType() {
		return Settings.CONTENT_TYPE_DEFAULT;
	}

	public int getContentLength() {
		return 0;
	}
	
	public int initialize() {
		return 0;
	}
	
	public void cleanup() {}

	public abstract byte[] getBytes();
	public abstract byte[] getBytesRange(int len);
}
