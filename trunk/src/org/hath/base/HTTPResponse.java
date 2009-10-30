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

public class HTTPResponse {
	private HTTPSession session;

	private boolean requestHeadOnly, validRequest;
	private boolean servercmd;
	private int responseStatusCode;

	private HTTPResponseProcessor hpc;

	public HTTPResponse(HTTPSession session) {
		this.session = session;

		validRequest = false;
		servercmd = false;
		requestHeadOnly = false;

		responseStatusCode = 500;	// if nothing alters this, there's a bug somewhere
	}

	private HTTPResponseProcessor processRemoteAPICommand(String command, String additional) {
		Hashtable<String,String> addTable = MiscTools.parseAdditional(additional);
		HentaiAtHomeClient client = session.getHTTPServer().getHentaiAtHomeClient();

		try {
			if(command.equalsIgnoreCase("still_alive")) {
				return new HTTPResponseProcessorText("I feel FANTASTIC and I'm still alive");
			} else if(command.equalsIgnoreCase("cache_list")) {
				String max_filesize = addTable.get("max_filesize");
				String max_filecount = addTable.get("max_filecount");
				return new HTTPResponseProcessorCachelist(client.getCacheHandler(), max_filesize != null ? Integer.parseInt(max_filesize) : 0, max_filecount != null ? Integer.parseInt(max_filecount) : 0);
			} else if(command.equalsIgnoreCase("cache_files")) {
				return new HTTPResponseProcessorText(client.getServerHandler().downloadFilesFromServer(addTable));
			} else if(command.equalsIgnoreCase("proxy_test")) {
				String ipaddr = addTable.get("ipaddr");
				int port = Integer.parseInt(addTable.get("port"));
				String fileid = addTable.get("fileid");
				String keystamp = addTable.get("keystamp");
				return new HTTPResponseProcessorText(client.getServerHandler().doProxyTest(ipaddr, port, fileid, keystamp));
			} else if(command.equalsIgnoreCase("speed_test")) {
				String testsize = addTable.get("testsize");
				return new HTTPResponseProcessorSpeedtest(testsize != null ? Integer.parseInt(testsize) : 1000000);
			} else if(command.equalsIgnoreCase("refresh_settings")) {
				return new HTTPResponseProcessorText(client.getServerHandler().refreshServerSettings()+"");
			}
		} catch(Exception e) {
			e.printStackTrace();
			Out.warning(session + ": Failed to process command");
		}

		return new HTTPResponseProcessorText("INVALID_COMMAND");
	}

	public void parseRequest(String request, boolean localNetworkAccess) {
		String[] requestParts = request.trim().split(" ", 3);

		if(requestParts.length == 3) {
			if((requestParts[0].equalsIgnoreCase("GET") || requestParts[0].equalsIgnoreCase("HEAD")) && requestParts[2].startsWith("HTTP/")) {
				validRequest = true;
				requestHeadOnly = requestParts[0].equalsIgnoreCase("HEAD");
				String[] urlparts = requestParts[1].split("/");

				if(urlparts.length >= 2) {
					if(urlparts[0].equals("")) {
						if(urlparts[1].equals("i") || urlparts[1].equals("x")) {
							// this kind of request can be on two forms: /x/ab/cd/keystamp/abcd.*?-size-xres-yres-filetype.filetype .. the /ab/cd and additional extension is for dumber clients that do not invoke any custom programming to serve a file, so we'll strip that out in the process.
							// the client and certain other systems uses /i/abcd.*?-size-xres-yres-filetype to request files, so the client must dont-care whether the superfluous parts are present
							// seeing as this code will only serve files with a valid fileid, this is fairly simple. we just use everything after the last "/" (slash), and cut off the filename request after the first "." (dot), since that's never a valid part of a fileid.

							String[] fileparts = urlparts[urlparts.length - 1].split("\\."); // regex, so just "." would be any-char
							HVFile requestedHVFile = session.getHTTPServer().getHentaiAtHomeClient().getCacheHandler().getHVFile(fileparts[0], !localNetworkAccess);

							boolean keystampRejected = true;
						
							String keystamp = urlparts[urlparts.length > 2 ? urlparts.length - 2 : 0];
							String[] keystampParts = keystamp.split("-");
							
							// after 0.4.2 was mandatory we could start doing this for all types of requests
							if(keystampParts.length == 2) {
								try {
									long keystampTime = Integer.parseInt(keystampParts[0]);
									
									if(Math.abs(Settings.getServerTime() - keystampTime) < 900) { 
										String expectedKey = MiscTools.getSHAString(keystampTime + "-" + fileparts[0] + "-" + Settings.getClientKey() + "-hotlinkthis").substring(0, 10);
										if(keystampParts[1].equalsIgnoreCase(expectedKey)) {
											keystampRejected = false;
										}
										else {
											//Out.debug(session + ": Invalid keystamp");
										}
									}
									else {
										//Out.debug(session + ": Keystamp expired");
									}
								} catch(Exception e) {
									//Out.debug(session + ": Invalid keystamp time");
								}
							}
							else {
								// Out.debug(session + ": Invalid keystamp format");
							}
							
							if(keystampRejected) {
								//Out.warning(session + ": Keystamp was invalid or not present.");
								responseStatusCode = 403;
								return;							
							}
							else if(requestedHVFile == null) {
								Out.warning(session + ": The requested file was invalid or not found in cache.");
								responseStatusCode = 404;
								return;
							}
							else {
								hpc = new HTTPResponseProcessorFile(session, requestedHVFile);
								return;
							}
						}
						else if(urlparts[1].equals("servercmd")) {
							// form: /servercmd/$command/$additional/$time/$key

							if(!Settings.isValidRPCServer(session.getSocketInetAddress())) {
								Out.warning(session + ": Got a servercmd from an unauthorized IP address: Denied");
								responseStatusCode = 403;
								return;
							}
							else if(urlparts.length < 6) {
								Out.warning(session + ": Got a malformed servercmd: Denied");
								responseStatusCode = 403;
								return;
							}
							else {
								String command = urlparts[2];
								String additional = urlparts[3];
								int commandTime = Integer.parseInt(urlparts[4]);
								String key = urlparts[5];

								int correctedTime = Settings.getServerTime();

								if((Math.abs(commandTime - correctedTime) < Settings.MAX_KEY_TIME_DRIFT) && MiscTools.getSHAString("hentai@home-servercmd-" + command + "-" + additional + "-" + Settings.getClientID() + "-" + commandTime + "-" + Settings.getClientKey()).equals(key)) {
									responseStatusCode = 200;
									servercmd = true;
									hpc = processRemoteAPICommand(command, additional);
									return;
								}
								else {
									Out.warning(session + ": Got a servercmd with expired or incorrect key: Denied");
									responseStatusCode = 403;
									return;
								}
							}
						}
						else if(urlparts[1].equals("r")) {
							// form: /r/fileid/token/gid-page/filename
							// at some point in time, we may support using ACLs for proxy access, but for now we'll restrict this to local network and fully open only
							// users could optionally restrict the open proxy version with a firewall (e.g. iptables).
							// we currently allow access depending on the proxy mode retrieved from the server when the client is first started.
							// 0 = disabled
							// 1 = local networks only
							// 2 = open proxy (future addition: with ACL)
							
							int proxymode = Settings.getRequestProxyMode();

							if(proxymode == 2 || (proxymode == 1 && session.isLocalNetworkAccess())) {
								String fileid = urlparts[2];
								String token = urlparts[3];
								String gidpage = urlparts[4];
								String filename = urlparts[5];

								if(HVFile.isValidHVFileid(fileid) && token.matches("^\\d+-[a-z0-9]{40}$") && gidpage.matches("^\\d+-\\d+$") && filename.matches("^(([a-zA-Z0-9])|(\\.)|(_))*$")) {
									String[] s = gidpage.split("-");
									
									try {
										int gid = Integer.parseInt(s[0]);
										int page = Integer.parseInt(s[1]);

										if(gid > 0 && page > 0) {
											HVFile requestedHVFile = session.getHTTPServer().getHentaiAtHomeClient().getCacheHandler().getHVFile(fileid, true);

											if(requestedHVFile != null) {
												hpc = new HTTPResponseProcessorFile(session, requestedHVFile);
												return;
											}
											else {
												hpc = new HTTPResponseProcessorRequestProxy(session, fileid, token, gid, page, filename);
												return;
											}
										}
										else {
											Out.warning(session + ": gid and/or page are <= 0");
										}
									} catch(Exception e) {
										Out.warning(session + ": gid and/or page are not valid integers");									
									}
								}
								else {
									Out.warning(session + ": Failed validation " + token.matches("^\\d+-[a-z0-9]{40}$") + " " + gidpage.matches("^\\d+-\\d+$") + " " + filename.matches("^(([a-zA-Z0-9])|(\\.)|(_))*$"));
								}
							}
							else {
								Out.warning(session + ": Proxy request denied for remote client.");
							}
						}
						else {
							Out.warning(session + ": Invalid request type '" + urlparts[1] + "'.");
						}
					}
					else {
						Out.warning(session + ": The requested URL is invalid or not supported.");
					}
				}
				else {
					Out.warning(session + ": The requested URL is invalid or not supported.");
				}

				responseStatusCode = 404;
				return;
			}
		}

		Out.warning(session + ": Invalid HTTP request.");
		responseStatusCode = 400;
	}

	public HTTPResponseProcessor getHTTPResponseProcessor() {
		if(hpc == null) {
			hpc = new HTTPResponseProcessorText("");
			Out.info(session + ": The remote host made an invalid request that could not be serviced.");
		}
		else if(hpc instanceof HTTPResponseProcessorFile) {
			responseStatusCode = hpc.initialize();
		}
		else if(hpc instanceof HTTPResponseProcessorRequestProxy) {
			responseStatusCode = hpc.initialize();
		}
		else if(hpc instanceof HTTPResponseProcessorText) {
			// do nothing
		}
		else if(hpc instanceof HTTPResponseProcessorSpeedtest) {
			Stats.setProgramStatus("Running speed tests...");
		}
		else if(hpc instanceof HTTPResponseProcessorCachelist) {
			Stats.setProgramStatus("Building and sending cache list to server...");
			hpc.initialize();
		}

		return hpc;
	}


	// accessors

	public int getResponseStatusCode() {
		return responseStatusCode;
	}

	public boolean isValidRequest() {
		return validRequest;
	}

	public boolean isRequestHeadOnly() {
		return requestHeadOnly;
	}

	public boolean isServercmd() {
		return servercmd;
	}
}
