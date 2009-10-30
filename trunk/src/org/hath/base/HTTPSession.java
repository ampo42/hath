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

import java.util.Date;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import java.net.Socket;
import java.net.InetAddress;
import java.lang.Thread;
import java.io.*;
import java.util.regex.*;
import java.text.DecimalFormat;

public class HTTPSession implements Runnable {

	public static final String CRLF = "\r\n";

	private Socket mySocket;
	private HTTPServer httpServer;
	private int connId;
	private Thread myThread;
	private boolean localNetworkAccess;
	private long sessionStartTime, lastPacketSend;
	private HTTPResponse hr;

	public HTTPSession(Socket s, int connId, boolean localNetworkAccess, HTTPServer httpServer) {
		sessionStartTime = System.currentTimeMillis();
		this.mySocket = s;
		this.connId = connId;
		this.httpServer = httpServer;
		this.localNetworkAccess = localNetworkAccess;
	}

	public void handleSession() {
		myThread = new Thread(this);
		myThread.start();
	}

	private void connectionFinished() {
		httpServer.removeHTTPSession(this);
	}

	public void run() {
		InputStreamReader isr = null;
		BufferedReader br = null;
		DataOutputStream dos = null;
		HTTPResponseProcessor hpc = null;

		try {
			Pattern getheadPattern = Pattern.compile("^((GET)|(HEAD)).*", Pattern.CASE_INSENSITIVE);

			isr = new InputStreamReader(mySocket.getInputStream());
			br = new BufferedReader(isr);
			dos = new DataOutputStream(mySocket.getOutputStream());

			// http "parser" follows... might wanna replace this with a more compliant one eventually ;-)

			String read = null;
			String request = null;
			int rcvdBytes = 0;

			// utterly ignore every single line except for the request one.
			do {
				read = br.readLine();

				if(read != null) {
					rcvdBytes += read.length();

					if(getheadPattern.matcher(read).matches()) {
						request = read.substring(0, Math.min(Settings.MAX_REQUEST_LENGTH, read.length()));
						Out.info(this + ": " + request);
					}
					else if(read.isEmpty()) {
						break;
					}
				}
				else {
					break;
				}
			} while(true);

			hr = new HTTPResponse(this);

			if(request != null) {
				hr.parseRequest(request, localNetworkAccess);

				if(!hr.isValidRequest()) {
					// error condition reported by HTTPResponse
					dos.writeBytes(getHTTPStatusHeader(400));
				}
			}
			else {
				//Out.warning(this + ": Method not allowed (not GET/HEAD).");
				dos.writeBytes(getHTTPStatusHeader(405));
				dos.writeBytes("Allow: GET,HEAD" + CRLF);
			}

			// this will also update the response code
			hpc = hr.getHTTPResponseProcessor();

			if((request != null) && hr.isValidRequest()) {
				// if the request was invalid, the response header is written above
				dos.writeBytes(getHTTPStatusHeader(hr.getResponseStatusCode()));
			}

			// we'll create a new one for each session instead of synchronizing on a shared formatter. (sdf is not thread-safe)
			SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", java.util.Locale.US);
			sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

			dos.writeBytes("Date: " + sdf.format(new Date()) + " GMT" + CRLF);
			dos.writeBytes("Server: Genetic Lifeform and Distributed Open Server " + Settings.CLIENT_VERSION + CRLF);
			dos.writeBytes("Connection: close" + CRLF);
			dos.writeBytes("Content-Type: " + hpc.getContentType() + CRLF);

			int contentLength = hpc.getContentLength();

			if(contentLength > 0) {
				dos.writeBytes("Cache-Control: public, max-age=31536000" + CRLF);
				dos.writeBytes("Content-Length: " + contentLength + CRLF);
			}

			if(hr.isValidRequest()) {
				if(hr.isRequestHeadOnly()) {
					Out.info(this + ": Writing httpResponse for HEAD: code=" + hr.getResponseStatusCode());
				}
				else {
					Out.info(this + ": Writing httpResponse for GET: code=" + hr.getResponseStatusCode() + " bytes=" + contentLength);
					dos.writeBytes(CRLF);

					if(hr.getResponseStatusCode() == 200) {
						long startTime = System.currentTimeMillis();

						if(localNetworkAccess && (hpc instanceof HTTPResponseProcessorFile || hpc instanceof HTTPResponseProcessorRequestProxy)) {
							Out.debug(this + ": Local network access detected, skipping throttle.");
							
							if(hpc instanceof HTTPResponseProcessorRequestProxy) {
								// we have to do it this way, otherwise the system will stall waiting for the proxy to serve the request fully before any data at all is returned
								int writtenBytes = 0;
								while(writtenBytes < contentLength) {
									int writeLen = Math.min(Settings.TCP_PACKET_SIZE_HIGH, contentLength - writtenBytes);
									dos.write(hpc.getBytesRange(writeLen), 0, writeLen);
									dos.flush();
									writtenBytes += writeLen;
								}
							}
							else {
								dos.write(hpc.getBytes(), 0, contentLength);							
							}
						}
						else {
							// bytes written to the local network do not count against the bandwidth stats. these do, however.
							Stats.bytesRcvd(rcvdBytes);

							HTTPBandwidthMonitor bwm = httpServer.getBandwidthMonitor();
							int packetSize = bwm.getActualPacketSize();
							int writtenBytes = 0;

							while(writtenBytes < contentLength) {
								lastPacketSend = System.currentTimeMillis();
								int writeLen = Math.min(packetSize, contentLength - writtenBytes);
								dos.write(hpc.getBytesRange(writeLen), 0, writeLen);
								dos.flush();
								writtenBytes += writeLen;
								Stats.bytesSent(writeLen);
								bwm.synchronizedWait(myThread);
							}
						}

						long sendTime = System.currentTimeMillis() - startTime;
						DecimalFormat df = new DecimalFormat("0.000");
						Out.info(this + ": Wrote " + contentLength + " bytes in " + df.format(sendTime / 1000.0) + " seconds (" + (sendTime > 0 ? df.format(contentLength / (float) sendTime) : "-.--") + " KB/s)");
					}
					else {
						dos.writeBytes("An error has occurred. (" + hr.getResponseStatusCode() + ")");
					}
				}
			}
		} catch(Exception e) {
			Out.info(this + ": The connection was interrupted or closed by the remote host.");
		} finally {
			if(hpc != null) {
				hpc.cleanup();
			}
			
			try { br.close(); isr.close(); dos.close(); } catch(Exception e) {}
			try { mySocket.close(); } catch(Exception e) {}
		}

		connectionFinished();
	}

	private String getHTTPStatusHeader(int statuscode) {
		switch(statuscode) {
			case 200: return "HTTP/1.1 200 OK" + CRLF;
			case 400: return "HTTP/1.1 400 Bad Request" + CRLF;
			case 403: return "HTTP/1.1 403 Permission Denied" + CRLF;
			case 404: return "HTTP/1.1 404 Not Found" + CRLF;
			case 405: return "HTTP/1.1 405 Method Not Allowed" + CRLF;
			case 501: return "HTTP/1.1 501 Not Implemented" + CRLF;
			case 502: return "HTTP/1.1 502 Bad Gateway" + CRLF;
			default: return "HTTP/1.1 500 Internal Server Error" + CRLF;
		}
	}

	public boolean doTimeoutCheck(boolean forceKill) {
		if(mySocket.isClosed()) {
			//  the connecion was already closed and should be removed by the HTTPServer instance.
			return true;
		}
		else {
			long nowtime = System.currentTimeMillis();
			int startTimeout = hr != null ? (hr.isServercmd() ? 1800000 : 180000) : 30000;

			if(forceKill || (sessionStartTime > 0 && sessionStartTime < nowtime - startTimeout) || (lastPacketSend > 0 && lastPacketSend < nowtime - 30000)) {
				// DIE DIE DIE
				Out.info(this + ": The connection has exceeded its time limits: timing out.");
				try {
					mySocket.close();
				} catch(Exception e) {
					Out.debug(e.toString());
				}
			}
		}

		return false;
	}

	// accessors

	public HTTPServer getHTTPServer() {
		return httpServer;
	}

	public InetAddress getSocketInetAddress() {
		return mySocket.getInetAddress();
	}

	public boolean isLocalNetworkAccess() {
		return localNetworkAccess;
	}

	public String toString() {
		return "{connId=" + connId + ", rhost=" + String.format("%1$-17s", getSocketInetAddress().toString() + "}") ;
	}

}
