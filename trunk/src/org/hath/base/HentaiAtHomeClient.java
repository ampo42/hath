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

/*

1.0.1 Release Notes

- The client will now occasionally register files downloaded through the H@H Downloader and H@H Proxy.

Prior to this release, the client would not actually register files downloaded through these functions before the next restart. Only the server-initiated distribution was updated "live".

This was more of a design decicion than a bug, but if you used those functions extensively without restarting, the client could get stuck with very few files registered on the server. This was a problem for a few people, so I've now addressed this problem.

- The time you've been online as well as the rate you're actually sending out files is now taken into account by the H@H Downloader. The client will request up to 20 tokens a minute, depending on those two factors everything from 1 to 20 can be returned.





[b]Download from the [url=http://g.e-hentai.org/hentaiathome.php]usual place[/url].[/b]

[b]For information on how to join Hentai@Home, check out [url=http://forums.e-hentai.org/index.php?showtopic=19795]The Hentai@Home Project FAQ[/url].[/b]

*/

package org.hath.base;

import java.io.File;
import java.net.URL;
import java.lang.Thread;

public class HentaiAtHomeClient implements Runnable {
	private InputQueryHandler iqh;
	private Out out;
	private ShutdownHook shutdownHook;
	private boolean shutdown, reportShutdown;
	private HTTPServer httpServer;
	private ClientAPI clientAPI;
	private CacheHandler cacheHandler;
	private ServerHandler serverHandler;
	private GalleryDownloadManager galleryDownloadManager;
	private Thread myThread;
	private int threadSkipCounter;
	private long suspendedUntil;
	private String[] args;
	
	public HentaiAtHomeClient(InputQueryHandler iqh, String[] args) {
		this.iqh = iqh;
		this.args = args;
		shutdown = false;
		reportShutdown = false;
		
		myThread = new Thread(this);
		myThread.start();
	}

	// master thread for all regularly scheduled tasks
	// note that this function also does most of the program initialization, so that the GUI thread doesn't get locked up doing this when the program is launched through the GUI extension.
	public void run() {
		out = new Out();
		out.overrideDefaultOutput();
		Out.info("Hentai@Home " + Settings.CLIENT_VERSION + " starting up");
		
		String sqlitejdbc = "sqlitejdbc-v056.jar";
		if(! (new File(sqlitejdbc)).canRead()) {
			Out.error("Required library file " + sqlitejdbc + " could not be found. Please re-download Hentai@Home.");
			System.exit(-1);
		}

		Settings.setActiveClient(this);
		Settings.parseArgs(args);
		
		Stats.resetStats();
		Stats.programStarted();

		Stats.setProgramStatus("Logging in to main server...");

		// processes commands from the server and interfacing code (like a GUI layer)
		clientAPI = new ClientAPI(this);

		if(Settings.loadClientLoginFromFile()) {
			Out.info("Loaded login settings from " + Settings.DATA_FILENAME_CLIENT_LOGIN);
		}

		if(!Settings.loginCredentialsAreSyntaxValid()) {
			Settings.promptForIDAndKey(iqh);
		}

		// handles notifications other communication with the hentai@home server
		serverHandler = new ServerHandler(this);
		serverHandler.loadClientSettingsFromServer();

		Stats.setProgramStatus("Initializing cache handler...");

		// manages the files in the cache
		cacheHandler = new CacheHandler(this);
		try {
			cacheHandler.initializeCacheHandler();
			cacheHandler.flushRecentlyAccessed();
		} catch(java.io.IOException ioe) {
			dieWithError(ioe);
			return;
		}

		Stats.setProgramStatus("Starting HTTP server...");

		// handles HTTP connections used to request images and receive commands from the server
		httpServer = new HTTPServer(this);
		if(!httpServer.startConnectionListener(Settings.getClientPort())) {
			dieWithError("Failed to initialize HTTPServer");
			return;
		}
		
		if(Settings.isFalseStart()) {
			Out.warning("False start set, initialization halted. You may run applicable tests on your client now.");
			return;
		}

		Stats.setProgramStatus("Sending startup notification...");

		Out.info("Notifying the server that we have finished starting up the client...");
		if(!serverHandler.notifyStart()) {
			dieWithError("Startup notification failed. Most likely you have not opened and forwarded the necessary port in your router and/or firewall.");
			return;
		}
		
		reportShutdown = true;
		shutdownHook = new ShutdownHook();
		java.lang.Runtime.getRuntime().addShutdownHook(shutdownHook);

		if(Settings.isWarnNewClient()) {
			String newClientWarning = "A new client version is available. Please download it from http://hentaiathome.net/ at your convenience.";
			Out.warning(newClientWarning);
			
			if(Settings.getActiveGUI() != null) {
				Settings.getActiveGUI().notifyWarning("New Version Available", newClientWarning);
			}
		}
		
		if(cacheHandler.getCacheCount() < 1) {
			Out.info("Important: Your cache does not yet contain any files. Because of this, you won't receive much traffic until the client has downloaded some files. This should usually happen within a few minutes. The longer you run the client, the more files it will download to your cache, which directly translates into higher utilization.");
		}
		
		// check if we're in an active schedule
		serverHandler.refreshServerSettings();

		Out.info("Activated.");
		Stats.resetBytesSentHistory();
		Stats.setProgramStatus("Running");

		cacheHandler.processBlacklist(259200, false);
		
		galleryDownloadManager = new GalleryDownloadManager(this);

		suspendedUntil = 0;
		threadSkipCounter = 1;
		
		long lastThreadTime = 0;

		while(!shutdown) {
			try {
				myThread.sleep(Math.max(1000, 10000 - lastThreadTime));
			} catch(java.lang.InterruptedException e) {
				Out.debug("Master thread sleep interrupted");
			}

			long startTime = System.currentTimeMillis();
		
			if(!shutdown && suspendedUntil < System.currentTimeMillis()) {
				Stats.setProgramStatus("Running");

				if(suspendedUntil > 0) {
					resumeMasterThread();
				}
				
				if(threadSkipCounter % 30 == 0) {
					serverHandler.stillAliveTest();
				}

				if(threadSkipCounter % 6 == 3) {
					httpServer.pruneFloodControlTable();
				}
				
				if(threadSkipCounter % 2160 == 2159) {
					cacheHandler.processBlacklist(43200, false);
				}
				
				cacheHandler.flushRecentlyAccessed();
				httpServer.nukeOldConnections(false);				
				Stats.shiftBytesSentHistory();

				++threadSkipCounter;
			}
			
			lastThreadTime = System.currentTimeMillis() - startTime;
		}
	}
	
	public boolean isSuspended() {
		return suspendedUntil > System.currentTimeMillis();
	}

	public boolean suspendMasterThread(int suspendTime) {
		if(suspendTime > 0 && suspendTime <= 86400 && suspendedUntil < System.currentTimeMillis()) {
			Stats.programSuspended();
			long suspendTimeMillis = suspendTime * 1000;
			suspendedUntil = System.currentTimeMillis() + suspendTimeMillis;
			Out.debug("Master thread suppressed for " + (suspendTimeMillis / 1000) + " seconds.");
			return serverHandler.notifySuspend();
		}
		else {
			return false;
		}
	}
	
	public boolean resumeMasterThread() {
		suspendedUntil = 0;
		threadSkipCounter = 0;
		Stats.programResumed();
		return serverHandler.notifyResume();
	}

	public InputQueryHandler getInputQueryHandler() {
		return iqh;
	}

	public HTTPServer getHTTPServer() {
		return httpServer;
	}

	public CacheHandler getCacheHandler() {
		return cacheHandler;
	}

	public ServerHandler getServerHandler() {
		return serverHandler;
	}

	public ClientAPI getClientAPI() {
		return clientAPI;
	}

	// static crap

	public static void dieWithError(Exception e) {
		e.printStackTrace();
		dieWithError(e.toString());
	}

	public static void dieWithError(String error) {
		Out.error("Critical Error: " + error);
		Stats.setProgramStatus("Died");
		Settings.getActiveClient().shutdown(error);
	}
	
	public void shutdown() {
		shutdown(false, null);
	}
	
	public void shutdown(String error) {
		shutdown(false, error);
	}

	private void shutdown(boolean fromShutdownHook) {
		shutdown(fromShutdownHook, null);
	}

	private void shutdown(boolean fromShutdownHook, String shutdownErrorMessage) {
		if(!shutdown) {
			shutdown = true;
			Out.info("Shutting down...");

			if(reportShutdown && serverHandler != null) {
				serverHandler.notifyShutdown();
			}
			
			if(httpServer != null) {
				httpServer.stopConnectionListener();
				Out.info("Shutdown in progress - please wait 25 seconds");

				try {
					Thread.currentThread().sleep(25000);
				} catch(java.lang.InterruptedException e) {}
				
				if(Stats.getOpenConnections() > 0) {
					httpServer.nukeOldConnections(true);		
					Out.info("All connections cleared.");			
				}			
			}
			
			if(cacheHandler != null) {
				cacheHandler.flushRecentlyAccessed();
			}
			
			if(myThread != null) {
				myThread.interrupt();
			}

			if(Math.random() > 0.99) {
				Out.info(
"                             .,---.\n" +
"                           ,/XM#MMMX;,\n" +
"                         -%##########M%,\n" +
"                        -@######%  $###@=\n" +
"         .,--,         -H#######$   $###M:\n" +
"      ,;$M###MMX;     .;##########$;HM###X=\n" +
"    ,/@##########H=      ;################+\n" +
"   -+#############M/,      %##############+\n" +
"   %M###############=      /##############:\n" +
"   H################      .M#############;.\n" +
"   @###############M      ,@###########M:.\n" +
"   X################,      -$=X#######@:\n" +
"   /@##################%-     +######$-\n" +
"   .;##################X     .X#####+,\n" +
"    .;H################/     -X####+.\n" +
"      ,;X##############,       .MM/\n" +
"         ,:+$H@M#######M#$-    .$$=\n" +
"              .,-=;+$@###X:    ;/=.\n" +
"                     .,/X$;   .::,\n" +
"                         .,    ..    \n"   
);
			}
			else {
				String[] sd = {"I don't hate you", "Whyyyyyyyy...", "No hard feelings", "Your business is appreciated", "Good-night"};
				Out.info(sd[(int) Math.floor(Math.random() * sd.length)]);
			}
			
			if(shutdownErrorMessage != null) {
				if(Settings.getActiveGUI() != null) {
					Settings.getActiveGUI().notifyError(shutdownErrorMessage);
				}			
			}
		}
		
		if(!fromShutdownHook) {
			System.exit(0);
		}
	}
	
	public boolean isShuttingDown() {
		return shutdown;
	}

	public static void main(String[] args) {
		InputQueryHandler iqh = null;

		try {
			iqh = InputQueryHandlerCLI.getIQHCLI();
			HentaiAtHomeClient hc = new HentaiAtHomeClient(iqh, args);
		} catch(Exception e) {
			Out.error("Failed to initialize InputQueryHandler");
		}
	}

	private class ShutdownHook extends Thread {
		public void run() {
			shutdown(true);
		}
	}
}