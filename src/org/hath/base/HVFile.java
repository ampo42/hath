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

// note: this class does not necessarily represent an actual file even though it is occasionally used as such (getLocalFileRef()) - it is an abstract representation of files in the HentaiVerse System

import java.io.File;

public class HVFile {
	private String hash;
	private int size;
	private int xres;
	private int yres;
	private String type;

	private HVFile(String hash, int size, int xres, int yres, String type) {
		this.hash = hash;
		this.size = size;
		this.xres = xres;
		this.yres = yres;
		this.type = type;
	}
	
	public File getLocalFileRef() {
		return new File(CacheHandler.getCacheDir(), hash.substring(0, 2) + "/" + getFileid());
	}
	
	public boolean localFileMatches(File file) {
		// note: we only check the sha-1 hash and filesize here, to save resources and avoid dealing with the crummy image handlers
		try {
			return file.length() == size && hash.startsWith(MiscTools.getSHAString(file));
		} catch(java.io.IOException e) {
			Out.warning("Failed reading file " + file + " to determine hash.");
		}
		
		return false;
	}
	
	
	// accessors
	public String getMimeType() {
		if(type.equals("jpg")) {
			return Settings.CONTENT_TYPE_JPG;
		} else if(type.equals("png")) {
			return Settings.CONTENT_TYPE_PNG;
		} else if(type.equals("gif")) {
			return Settings.CONTENT_TYPE_GIF;
		} else {
			return Settings.CONTENT_TYPE_OCTET;
		}
	}

	public String getFileid() {
		return hash + "-" + size + "-" + xres + "-" + yres + "-" + type;
	}
	
	public String getHash() {
		return hash;
	}
	
	public int getSize() {
		return size;
	}
	
	public String getType() {
		return type;
	}
	

	// static stuff
	public static boolean isValidHVFileid(String fileid) {
		return java.util.regex.Pattern.matches("^[a-z0-9]{40}-[0-9]{1,8}-[0-9]{1,5}-[0-9]{1,5}-((jpg)|(png)|(gif))$", fileid);
	}

	public static HVFile importHVFile(File file) {
		try {
			String hash = MiscTools.getSHAString(file);
			int size = (int) file.length();
			
			String type = FileTools.getFileExtension(file);
			
			if(type != null) {
				// <uglyuglyhax>
				java.awt.Container dummy = new java.awt.Container();
				java.awt.MediaTracker mt = new java.awt.MediaTracker(dummy);
				java.awt.Image img = java.awt.Toolkit.getDefaultToolkit().getImage(file.getAbsolutePath());
				mt.addImage(img, 1);
				mt.waitForAll();
				int xres = img.getWidth(dummy);
				int yres = img.getHeight(dummy);
				mt.removeImage(img);
				img.flush();
				img = null;
				mt = null;
				dummy = null;
				System.gc();
				
				// wait for up to five microfortnights for the image handler to release the file. usually it should release within a few hundred millis. note that canWrite returns true even if there's a lock, so we have to do this FUBAR test on the file instead.
				int waittimer = 0;
				File test = new File(file.getCanonicalPath() + ".test");
				
				while(!file.renameTo(test) && ++waittimer < 500) {
					java.lang.Thread.currentThread().sleep(10);
				}
				
				test.renameTo(file);
				// </uglyuglyhax>
				
				type = type.toLowerCase();
				if(type.equals("jpeg") || type.equals("jpe")) {
					type = "jpg";
				}
				
				String fileid = hash + "-" + size + "-" + xres + "-" + yres + "-" + type;
				
				if(isValidHVFileid(fileid)) {
					return new HVFile(hash, size, xres, yres, type);
				}
				else {
					Out.warning("Failed to form a valid fileid when importing " + file);
				}			
			}
			else {
				Out.warning("Could not determine extension of " + file);
			}
		} catch(Exception e) {
			e.printStackTrace();
			Out.warning("Failed gathering necessary data when importing " + file);
		}

		return null;
	}
	
	public static HVFile getHVFileFromFile(File file, boolean verify) {
		if(file.exists()) {
			String fileid = file.getName();

			try {
				if(verify) {
					if(!fileid.startsWith(MiscTools.getSHAString(file))) {
						return null;
					}
				}

				return getHVFileFromFileid(fileid);
			} catch(java.io.IOException e) {
				e.printStackTrace();
				Out.warning("Warning: Encountered IO error computing the hash value of " + file);
			}
		}
		
		return null;
	}
	
	public static HVFile getHVFileFromFileid(String fileid) {
		if(isValidHVFileid(fileid)) {
			try {
				String[] fileidParts = fileid.split("-");
				String hash = fileidParts[0];
				int size = Integer.parseInt(fileidParts[1]);
				int xres = Integer.parseInt(fileidParts[2]);
				int yres = Integer.parseInt(fileidParts[3]);
				String type = fileidParts[4];
				return new HVFile(hash, size, xres, yres, type);
			} catch(Exception e) {
				Out.warning("Failed to parse fileid \"" + fileid + "\" : " + e);
			}
		}
		else {
			Out.warning("Invalid fileid \"" + fileid + "\"");
		}
		
		return null;
	}
}
