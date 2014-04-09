/* Copyright 2013 Wisconsin Wireless and NetworkinG Systems (WiNGS) Lab, University of Wisconsin Madison.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wisc.insightlib;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.util.Log;
import com.wisc.insightlib.LocationUpdateManager;;

/**
 * The Insight library.
 * 
 * @author Ashish Patro
 *
 */
public class InsightLib {
	/**
	 * Constants
	 */
	private static final String TAG = "InsightLib";

	/**
	 * Stores the server IP.
	 */
	private static String serverHostname = "192.168.1.102";

	/**
	 * Store the application specific user ID.
	 */
	private static String applicationCharID = null;

	/**
	 * Stores the deviceIdString generated for the client. Generated using a hash of some
	 * of the key attributes from the device.
	 */
	private static String deviceID  = null;

	/**
	 * The PingClient instance pings the measurement server using UDP and TCP based connections.
	 */
	private static PingClient pingClient = null;

	/**
	 * The ResourceConsumptionStats instance pings the measurement server using UDP and TCP based connections.
	 */
	private static MainStatsManager mainStatsManager = null;
	private static Thread resourceStatsThread = null;

	/**
	 * Maintains the information about whether the session is started or not.
	 */
	private static int countActiveOnStarts = 0;

	/**
	 * The application context;
	 */
	private static Context mContext = null;

	/** 
	 * Create a sessionID string for the client.
	 */
	private static long sessionID;

	/**
	 * Timer to get obtain periodic location updates.
	 */
	//private static Timer locUpdateTimer;
	private static LocationUpdateManager locationUpdateManager = null;
	private static Thread locationUpdateThread = null;
	
	/**
	 * Reporting the statistics periodically.
	 */
	private static Thread sendEventsThread = null;
	
	/**
	 * Name of the process to be monitored.
	 */
	private static String processString;
	
	/**
	 * Stores the monitored process uid.
	 */
	private static long packageUid;

	/**
	 * Stores the networktraffic statistics (download times etc.)
	 */
	private static NetworkTrafficStats networkTrafficStats = null;
	
	/**
	 * Stores the event related statistics
	 */
	private static EventStatistics eventStats = null;

	/**
	 * Denotes the end of a session.
	 */
	private static EndSessionThreadRunner endSessionThread = null;
	
	/**
	 * Denotes the start of a new application session. This method starts running the Insight
	 * library. This method should be called within the onStart() method of the main Android activity.
	 *   
	 * @param applicationContext The application context of the main activity.
	 * @param inputProcessString The package name of the main activity.
	 */
	public static void startSession(Context mContextApp) {
		try {
			// Need to intialize this variable only once. Should never be null.
			if (InsightLib.endSessionThread == null) {
				InsightLib.endSessionThread = new InsightLib.EndSessionThreadRunner();
				Log.w(TAG, "Initializing thread...");
			}
			
			// Increment the count for each onStart/startSession call.
			InsightLib.countActiveOnStarts ++;

			if (!endSessionThread.isSessionEndComplete()) {
				endSessionThread.cancelEndSession();
				Log.w(TAG, "Continuing existing session... Active onStarts: " +
						InsightLib.countActiveOnStarts);
			} else if (InsightLib.countActiveOnStarts == 1) {
				Log.w(TAG, "Starting a new session... Active onStarts: " +
						InsightLib.countActiveOnStarts);

				InsightLib.mContext = mContextApp;
				InsightLib.processString = mContextApp.getPackageName(); 
				
				PackageManager pm = InsightLib.mContext.getPackageManager();
				packageUid = pm.getApplicationInfo(InsightLib.processString, 0).uid;
				
				if (InsightLib.deviceID == null || InsightLib.deviceID.equals("")) {
					InsightLib.deviceID = Utils.getHashDeviceIdString(mContextApp);
				}
				
				InsightLib.sessionID = Utils.getSessionIdString();

				// Reset the library state.
				resetLibrary("startSession");

				// Obtain the uid for the process. 
				//uid = Utils.getUidFromProcessName(InsightLib.processString);

				//Log.w(TAG, "UID: " + InsightLib.packageUid + " processString: " + InsightLib.processString +
				//		" deviceID: " + InsightLib.deviceID);
				
				// Initialize a EventStatistics object. Initialize only if reset.
				if (eventStats == null) {
					eventStats = new EventStatistics();
				}
				
				// Initialize a NetworkTrafficStats object. Initialize only if reset.
				if (networkTrafficStats == null) {
					networkTrafficStats = new NetworkTrafficStats(packageUid);
				}

				// Initialize the uid, networkTrafficStats object and start the ping
				// and resource measurement threads.
				startMeasurmentThreads();
			} else {
				Log.w(TAG, "Session already started... Not doing anything...\n");
			}
		} catch (Exception e) {
			Log.e(TAG, "initApplicationState" + e.toString());
		}
	}

	/**
	 * Specifies the end of the current session. This method does housekeeping stuff related to the
	 * session. Should be called within the onStop() method of the main Android activity.  
	 */
	public static void endSession() {
		Log.w(TAG, "Insight endSession called...");
		InsightLib.countActiveOnStarts--;
		
		// TODO: Finalize: Do we need to use counts or just the wait heuristic is enough.
		// If wait heuristics is enough, its better because we don't want to mess up counts and keep
		// running insight in the background.
		if (InsightLib.countActiveOnStarts > 0) {

			Log.w(TAG, "Not ending session as " + countActiveOnStarts + " onStarts left");
		} else {
			Log.w(TAG, "Starting endsessionThread... Active onStarts: " +
					InsightLib.countActiveOnStarts);
		
			InsightLib.countActiveOnStarts = 0;
		
			Log.w(TAG, "Waiting for " + Constants.SESSION_END_WAIT / 1000 + " seconds before ending session...");
			endSessionThread.endSession();
		}
	}
	
	/** 
	 * Contains the code that is executed while ending a session.
	 */
	public static void finish() {
		Log.w(TAG, "Insight finish called...");
		
		// TODO: Finalize: Do we need to use counts or just the wait heuristic is enough.
		if (InsightLib.countActiveOnStarts > 0) {
			Log.w(TAG, "Not ending session as " + countActiveOnStarts + " onStarts left");
			return;
		}
		
		try {
			networkTrafficStats.endSession();
		} catch (Exception e) {
			Log.e(TAG, " Exception while processing network stats: " + e.toString());
		}

		try {
			// This is done to try to ensure that at least one location update message is 
			// sent per session. For short sessions, location update may not have been sent
			// before the session ends.
			if (!locationUpdateManager.isFirstLocationUpdateSent()
					&& locationUpdateManager.getLocState() != null) {
				Log.i(TAG, "endSession: sending location data..");

				if (!locationUpdateManager.sendLocationData()) {
					Log.w(TAG, "endSession: Couldn't send location data..");
				} 
			}
		} catch (Exception e) {
			Log.e(TAG, " Exception while processing end session: " + e.toString());
		}

		try {
			Log.i(TAG, "Sending the endSession message..");
			mainStatsManager.sendEndSessionMessage(getOverallStatsString());
		} catch (Exception e) {
			Log.e(TAG, " Exception sending the endSession message: " + e.toString());
		}

		try {
			//Log.i(TAG, "resetting library..");
			resetLibrary(" endSession");
			
			InsightLib.countActiveOnStarts = 0;
		} catch (Exception e) {
			Log.e(TAG, " Exception while ending session: " + e.toString());
		}
		
		Log.w(TAG, "Session ended");
		
		// Reset the state as session is complete.
		endSessionThread.resetState();
	}

	/**
	 * Logs an event denoted by the eventID. It increments the counter for the
	 * input event.
	 * 
	 * @param eventID The id of the event denoted by an integer.
	 */
	public static void captureEvent(Integer eventID) {
		try {
			eventStats.captureEvent(eventID);
		} catch (Exception e) {
			Log.e(TAG, "Exception while capturing event: " + e.toString());
		}
	}

	/**
	 * Logs an event corresponding to the eventID and the value corresponding to
	 * the event instance.
	 * 
	 * @param eventID The id of the event denoted by an integer.
	 * @param value The value corresponding to the event.
	 */
	public static void captureEventValue(Integer eventID, Double value) {
		try {
			eventStats.captureEventValue(eventID, value);
		} catch (Exception e) {
			Log.e(TAG, "Exception while capturing event-value: " + e.toString());
		}
	}
	
	/**
	 * Logs an event corresponding to the eventID and the value string corresponding to
	 * the event instance.
	 * 
	 * @param eventID The id of the event denoted by an integer.
	 * @param value The value corresponding to the event.
	 */
	public static void captureEventString(Integer eventID, String value) {
		try {
			eventStats.captureEventString(eventID, value);
		} catch (Exception e) {
			Log.e(TAG, "Exception while capturing event-value string: " + e.toString());
		}
	}
	
	/**
	 * Call this method to denote the start of a new download. This method
	 * should be called before starting the download. Only one instance
	 * of a download can be captured at a time. This method should be
	 * paired with a downloadEnded() method call after the end of the download. 
	 */
	public static long downloadStarted() {
		try {
			return networkTrafficStats.downloadStarted();
		} catch (Exception e) {
			Log.e(TAG, "Exception while capturing downloadStart: " + e.toString());
		}
		
		return -1;
	}
	
	/**
	 * Call this method to denote the end of the current download. It should be
	 * called at the end of the download. The method the captures the data downloaded
	 * by the application between the previous downloadStarted() call and the current
	 * downloadEnded() call.
	 */
	public static void downloadEnded(long downloadId) {
		try {
			networkTrafficStats.downloadEnded(downloadId);
		} catch (Exception e) {
			Log.e(TAG, "Exception while capturing downloadEnd: " + e.toString());
		}
	}
	
	/**
	 * Set the deviceID for the application.
	 * 
	 * @param deviceID
	 */
	public static void setDeviceID(String deviceID) {
		InsightLib.deviceID = deviceID;
	}
	
	/**
	 * Returns the application specific userID set by the developer. This value is null by default.
	 * 
	 * @return applicationUID
	 * 
	 */
	public static String getApplicationCharID() {
		return applicationCharID;
	}

	/**
	 * Sets the application specific user ID for the session.
	 * 
	 * @param applicationUID - The application user ID.
	 */
	public static void setApplicationCharID(String applicationCharID) {
		InsightLib.applicationCharID = applicationCharID;
	}

	/**
	 * Return the hostname of the analytics server. This value should be set before calling any
	 * other method of the library.
	 * 
	 * @return serverHostname
	 */
	public static String getServerHostname() {
		return serverHostname;
	}

	/**
	 * Sets the hostname of the measurement server.
	 * 
	 * @param serverHostname The hostname denoting the location of the analytics server.
	 */
	public static void setServerHostname(String serverHostname) {
		InsightLib.serverHostname = serverHostname;
	}
	
	/**
	 * This methods starts a new thread to perform the active tests, if and only if
	 *  an active test is not running currently.
	 */
	private static void startMeasurmentThreads() {
		try {
			/**
			 *  Start Performing measurements instantly after opening up the application. 
			 */
			new Thread(new Runnable() {
				public void run() {
					Looper.prepare();
					try {
						/*
						// Obtain the uid for the process. 
						uid = Utils.getUidFromProcessName(processString);

						// Initialize a NetworkTrafficStats object. Initialize only if reset.
						if (networkTrafficStats == null) {
							networkTrafficStats = new NetworkTrafficStats(uid);
						}
						Log.i(TAG, "network traffic stats is intialized");
						
						// Initialize a EventStatistics object. Initialize only if reset.
						if (eventStats == null) {
							eventStats = new EventStatistics();
						}
						*/

						// Run the resource consumption measurements.
						mainStatsManager = new MainStatsManager(mContext,
								deviceID, sessionID, processString);
						resourceStatsThread = new Thread(mainStatsManager);
						resourceStatsThread.setDaemon(true);
						resourceStatsThread.start();

						// Run the ping test.
						pingClient = new PingClient(mContext, deviceID, sessionID);

						// Start obtaining the location updates.
						locationUpdateManager = 
							new LocationUpdateManager(mContext, mainStatsManager);

						locationUpdateThread = new Thread(locationUpdateManager, "LocationUpdateThread");
						locationUpdateThread.setDaemon(true);
						locationUpdateThread.start();
						
						// Start the periodic event reporting updates.
						sendEventsThread = new Thread(new Runnable() {
							
							public void run() {
								while (true) {
									try {
										// TODO: Move up.
										//Log.i(TAG, "Sleeping now " + Constants.CURRENT_STATS_UPDATE_FREQUENCY  + "..." );
										Thread.sleep(Constants.CURRENT_STATS_UPDATE_FREQUENCY);

										//Log.i(TAG, "Sending now...");
										mainStatsManager.sendCurrentStatsMessage(InsightLib.getCurrStatsString());
									} catch (InterruptedException e) {
										Log.e("sendEventThread", "InterruptedException: " + e);
									} catch (Exception e) {
										Log.e("sendEventThread", "Exception: " + e);
									}
								}
							}
						});
						sendEventsThread.start();
					} catch (Exception e) {
						Log.e(TAG , "startMeasurmentThreads: Exception " + e);
						return;
						//isTestRunning = false;
					}		
					Looper.loop();
				}
			}).start();
		} catch (Exception e) {
			Log.e(TAG, "startThread : " + e);
		}
	}

	/**
	 * Reset the state of the Insight library.
	 */
	private static void resetLibrary(String info) 
	{
		Log.e(TAG, "resetLibrary Called. Info: "+ info);
		
		// Reset the networkTrafficStats object by setting it to null.
		try {
			if (networkTrafficStats != null) {
				networkTrafficStats = null;
				Log.w(TAG , "Removed the existing networkTrafficStats object.");
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception while resetting networkTrafficStats object: " +
					e.toString());
		}
		
		// Reset the eventStats object by setting it to null.
		try {
			if (eventStats != null) {
				eventStats = null;
				//Log.i(TAG , "Removed the existing eventStats object.");
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception while resetting eventStats object: " +
					e.toString());
		}
		
		try {
			if (pingClient != null) {
				pingClient.stopCurrentThread("ResetLibrary");
				pingClient = null;

				//Log.i(TAG , "Stopped existing pingClient thread.");
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception while resetting pingClient: " +
					e.toString());
		}

		try {
			if (mainStatsManager != null) {
				mainStatsManager.stopStatsCollection("resetLibrary");
				mainStatsManager = null;
				
				//Log.i(TAG , "Stopped existing resouceConsumptionStats thread.");
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception while resetting resourceConsumptionStats: " +
					e.toString());
		}

		try {
			if (locationUpdateManager != null) {
				locationUpdateManager.resetLocationUpdates("resetLibrary");
				locationUpdateManager = null;
				
				//Log.i(TAG , "Stopped existing locationUpdateManager thread.");
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception while resetting locationUpdateManager: " +
					e.toString());
		}
		
		try {
			if (sendEventsThread != null) {
				sendEventsThread.interrupt();
				sendEventsThread = null;
				
				//Log.i(TAG , "Stopped existing sendEventsThread thread.");
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception while resetting sendEventsThread: " +
					e.toString());
		}
	}

	/**
	 * Generate a serialized string containing the event related statistics, 
	 * application data usage statistics, network download statistics etc.  
	 * @return statsString
	 */
	private static String getOverallStatsString() {
		StringBuilder overallStatsString = new StringBuilder();

		//Log.w(TAG, "Event: " + InsightLib.eventCountMap.size() + " " + 
		//		InsightLib.eventList.size());

		if (networkTrafficStats != null) {
			overallStatsString.append(networkTrafficStats.getNetworkStatsString());
		}
		overallStatsString.append("$");
		
		if (eventStats != null) {
			overallStatsString.append(eventStats.getEventCountStatsString());
			overallStatsString.append("$");
		
			overallStatsString.append(eventStats.getEventValueStatsString());
			overallStatsString.append("$");
		
			overallStatsString.append(eventStats.getEventStringStatsString());
			overallStatsString.append("$");
		} else {
			overallStatsString.append("$$$");
		}
		
		if (networkTrafficStats != null) {
			overallStatsString.append(networkTrafficStats.getDownloadStatsString());
		}

		Log.w(TAG, "Event: " + overallStatsString.toString());
		return overallStatsString.toString();
	}
	
	/**
	 * IMP: event counters not sent because they are counted throughout the session.
	 * Generate a serialized string containing the event related statistics, 
	 * network download statistics etc.  
	 * @return statsString
	 */
	private static String getCurrStatsString() {
		StringBuilder currStatsString = new StringBuilder();

		if (eventStats != null) {
			currStatsString.append(eventStats.getEventValueStatsString());
		}
		currStatsString.append("$");
		
		if (eventStats != null) {
			currStatsString.append(eventStats.getEventStringStatsString());
		}
		currStatsString.append("$");
		
		if (networkTrafficStats != null) {
			currStatsString.append(networkTrafficStats.getDownloadStatsString());
		}

		//Log.w(TAG, "Event: " + currStatsString.toString());
		return currStatsString.toString();
	}
	
	private static class EndSessionThreadRunner implements Runnable {
		
		private static final String TAG = "EndSessionThreadRunner";
		
		private Thread endSessionThread = null;
		private boolean sessionEndComplete = true;
		
		public boolean isSessionEndComplete() {
			return sessionEndComplete;
		}

		private void resetState() {
			endSessionThread = null;
			sessionEndComplete = true;
		}
		
		public EndSessionThreadRunner() {
			resetState();
		}
		
		public void endSession() {
			if (sessionEndComplete) {
				endSessionThread = new Thread(this);
				endSessionThread.start();
			} else {
				Log.w(TAG, "Session end already in progress");
			}
		}
		
		public void cancelEndSession() {
			if (!sessionEndComplete) {  // Means the thread is waiting to end session.
				try {
					endSessionThread.interrupt();
				} catch (Exception e) {
					Log.e(TAG + ": stopStatsCollection", "Exception while cancelling resourceGatheringInfo timer");
				}
				
				// Reset the state as session is cancelled.
				resetState();
			} else {
				Log.w(TAG, "Should not reach here. Session is already ended");
			}
		}
		
		public void run() {
			sessionEndComplete = false;
			
			try {
				Thread.sleep(Constants.SESSION_END_WAIT);
			} catch (InterruptedException e) {
				Log.e(TAG, "Interrupted while waiting: Not doing anything.. " + e.toString());
				return;
			}
			
			// If Insight.finish() is called from onDestroy(), then no need to call this again.
			if (!sessionEndComplete) {
				sessionEndComplete = true;
				InsightLib.finish();
			} else {
				Log.w(TAG, "Session already ended. Not doing anything..");
			}
		}
	}
}