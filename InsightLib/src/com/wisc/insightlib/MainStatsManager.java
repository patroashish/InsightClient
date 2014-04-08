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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;


import com.wisc.insightlib.json.JSONObject;
import com.wisc.insightlib.json.parser.JSONParser;

import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * The class manages the collection of resource consumption statistics.
 * 
 * @author Ashish Patro
 */
public class MainStatsManager implements Runnable{
	
	// Constants
	private static final String TAG = "MainManager";
	private static final String JOIN_DELIM = "\n";//"#@#@#@";
	
	// Used to synchronize the sending of messages over the single TCP connection
	private static final Lock sendMessageLock = new ReentrantLock();

	// Message related constants
	private static final String MEESAGE_PREAMBLE = "re08h4089y";
	private static final String MESSAGE_POSTAMBLE = "dsf9u0932j";

	private static int READ_BUFFER_SIZE = 100; 

	// Identifier for the message types
	private static final int BATTERY_INFO = 0;
	private static final int RESOURCE_INFO = 1;
	private static final int SYSTEM_INFO = 2;
	private static final int NEW_SESSION = 3;
	private static final int REMOVE_SESSION = 4;
	private static final int LOCATION_INFO = 8;
	private static final int APPUID_INFO = 11;
	private static final int EVENT_UPDATE_INFO = 12;

	// Application variables
	private Context context;
	private long sessionID;
	private String deviceID;
	private int sequenceNum;
	//private boolean runResourceMeasurement;

	// Measure the resource usage on the device
	//private Timer resourceInfoGatheringTimer;
	private Thread resourceThread;

	// Monitor the battery status
	private ParseBatteryStats batteryReceiver;

	// Monitor the screen status
	private ScreenReceiver screenReceiver;

	// Maintaining previous state
	private long prev_idle = -1;
	private long prev_cpu = -1;

	// Connection variables
	private InetAddress serverAddr;
	private Socket tcpSocket;

	// Session state related variables
	private boolean sessionRunning;

	// The process String information
	private String processString;
	
	// Stores whether the applicationUID information has been sent to the server or not
	private boolean isApplicationUidSent = false; 

	public MainStatsManager(Context context, String deviceID,
			long sessionId, String processString) { //, boolean runResourceMeasurement) {
		this.context = context;
		this.deviceID = deviceID;
		this.sessionID = sessionId;
		this.processString = processString;
		//this.runResourceMeasurement = runResourceMeasurement;

		this.sessionRunning = true;
		this.sequenceNum = 0;
	}
	
	/**
	 * Start the main thread.
	 */
	public void run() {
		Random randomGenerator = new Random();
		
		int resourceMeasurementInterval = Constants.RESOURCE_INTERVAL;
		double receiveProb = 1.0;
		double randomProb = randomGenerator.nextDouble();

		// Sleep initially for a short period of time and then start the activity.
		try {
			Thread.sleep(Constants.INIT_SLEEP_TIME);
		} catch (InterruptedException e) {
			Log.e(TAG, "Exception while sleeping initially: " + e.getMessage());
		}

		if (deviceID == null) {
			Log.i(TAG, "deviceID is null! Doing nothing...");
			return;
		}

		// Obtains the IP address for the server.
		try	{
			serverAddr = InetAddress.getByName(InsightLib.getServerHostname());
		} catch (UnknownHostException e) {
			Log.e(TAG, "Don't know about host: " + InsightLib.getServerHostname());
			return;
		}

		// Creates a TCP connection to the server.
		if(createTCP(serverAddr) < 0) {
			Log.i(TAG, "createTCP Failed! Doing nothing...");
			return;
		}

		// Initiate a session by a contacting the server.
		try {
			if (!sendStartSessionMessage()) {
				return;
			}	
		} catch (Exception e) {
			Log.e(TAG, "Exception while sending start session message: " + e.getMessage());
			return;
		}

		// Read the configuration information received from the server.
		try {
			String readString = new String(readDataFromServer());
			readString = readString.substring(0, readString.lastIndexOf('}') + 1);

			JSONObject json = (JSONObject)new JSONParser().parse(readString);
			resourceMeasurementInterval = 
				Integer.parseInt(json.get("resourceInterval").toString());

			receiveProb = 
				Double.parseDouble(json.get("measurmentProbability").toString());
			//Log.i(TAG, "Resource measurement interval set to " + 
			//		resourceMeasurementInterval + " milliseconds");

		} catch (Exception e) {
			Log.e(TAG, "Exception while processing config information." +
					" Using default parameter values." + e.toString());
			//return;

			// Important: In case of failure to read config message,
			resourceMeasurementInterval = Constants.RESOURCE_INTERVAL;
		}
		
		// After this point we should just receive keep TCP Alive messages.
		try { 
			//new Thread(new ReceiveTcpKeepAlive(this)).start();
		} catch (Exception e) {
			Log.e(TAG, "While starting keep alive timer: ", e);
		}

		// From here onwards, if any module fails, don't do anything
		// We just won't obtain that portion of the information

		// Obtain the device, system related information and directly send it to the server
		try {
			String systemInfo = getSystemInformation();
			sendMessageToServer(systemInfo, SYSTEM_INFO, false, -1);		
		} catch (Exception e) {
			Log.e(TAG, "Exception while sending system related information: " + e.getMessage());
		}

		try {
			Thread.sleep(Constants.INIT_SLEEP_TIME);
		} catch (InterruptedException e) {
			Log.e(TAG, "Exception while sleeping initially: " + e.getMessage());
		}

		// Initialize the battery information gathering receiver
		try {
			// TODO: Not used right now, the probabilities are used by the server
			// to randomly collect statistics from only a subset of client devices.
			if (randomProb <= receiveProb) { 
				initBroadcastReceivers();
			} else {
				//Log.w(TAG, "Skipping initBroadcastReceivers");
			}
		} catch (Exception e) {
			Log.e(TAG, "Excepion while setting up : " + e.getMessage());
		}

		// Initilize the memory, CPU consumption stats gathering threads
		try { 
			//if (runResourceMeasurement) 
			{
			    
				/*
				resourceInfoGatheringTimer = new Timer(true);
				resourceInfoGatheringTimer.schedule(new TimerTask() {
					@Override
					public void run() {
						try {
							Log.e(TAG, "Calling getResourceUsageStats");
							Pair<Integer, String> resourceInfo = getResourceUsageStats();

							if (resourceInfo != null) {
								sendMessageToServer(resourceInfo.second,
										RESOURCE_INFO, true, resourceInfo.first);
							}
						} catch (Exception e) {
							Log.e(TAG, "Exception while getting resource info: " + e.getMessage());						
						}
					}
				}, Constants.INIT_SLEEP_TIME, resourceMeasurementInterval);
				*/
			
				if (randomProb <= receiveProb) {
					resourceThread = new Thread(new ResourceThread(resourceMeasurementInterval), 
						"ResourceStats");
					resourceThread.setDaemon(true);
					resourceThread.start();
				} else {
					Log.w(TAG, "Skipping resourceThread");
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception in resource gathering timer: " + e.getMessage());
		}
	}

	/**
	 * Initialize the broadcast receiver for receiving the battery related statistics.
	 */
	private void initBroadcastReceivers() {
		batteryReceiver = new ParseBatteryStats();
		IntentFilter filterBattery = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		this.context.registerReceiver(batteryReceiver, filterBattery);

		screenReceiver = new ScreenReceiver();
		IntentFilter filterScreen = new IntentFilter(Intent.ACTION_SCREEN_ON);
		filterScreen.addAction(Intent.ACTION_SCREEN_OFF);
		this.context.registerReceiver(screenReceiver, filterScreen);
	}
	
	/**
	 * Returns whether Internet connectivity is available or not. 
	 * @return status
	 */
	/*
	private boolean isOnline() {
	    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
	    NetworkInfo netInfo = cm.getActiveNetworkInfo();
	    if (netInfo != null && netInfo.isConnectedOrConnecting()) {
	        return true;
	    }
	    return false;
	}
	*/

	/**
	 * Initiates a TCP connection with the Insight server for collecting statistics.
	 * 
	 * @param serverAddr
	 * @return 0 on success, -1 on failure
	 */
	private int createTCP(InetAddress serverAddr) {
		try	{
			//Log.v(TAG +": createTCP", "Opening a socket");

			tcpSocket = new Socket();
			if (tcpSocket == null) {
				Log.e(TAG + ": createTCP", "tcpSocket == null");
				return -1;
			}

			// timeout of CONNECTION_TIMEOUT secs.
			tcpSocket.connect(new InetSocketAddress(InetAddress.getByName(InsightLib.getServerHostname()),
					Constants.SERVER_PORT_RESOURCE), Constants.SOCKET_CONNECT_TIMEOUT);
			// make the read blocking indefinitely
			tcpSocket.setSoTimeout(0);

		} catch (IOException e)	{
			Log.e(TAG + ": createTCP", "Couldn't get I/O for the TCP connection to: " + InsightLib.getServerHostname());
			return -1;
		}

		return 0;
	}
	
	/**
	 * Read the server input over the established TCP connection.
	 * 
	 * @param message
	 */
	private synchronized byte[] readDataFromServer() {
		// TODO: Try the lock based approach instead of the synchronized method to stop
		// concurrent access.
		try	{
			byte[] readBuffer = new byte[READ_BUFFER_SIZE];

			tcpSocket.getInputStream().read(readBuffer);
			//Log.v(TAG, "readDataFromServer: readCount: " + 
			//		readCount + ". Received: " + readString);

			return readBuffer;
		} catch (Exception e) {
			Log.e(TAG + ": readDataBytesFromServer", "tcpSocket.getOutputStream.read failed. Stopping stats collection.");

			stopStatsCollection("readDataFromServer");
			return null;
		}
	}
	
	/**
	 * Sending the input message stream to the serve over the established TCP connection.
	 * The access to this method is serialized so avoid concurrency issues.
	 * 
	 * @param message
	 */
	private synchronized boolean sendDataBytesToServer(byte[] message) {
		boolean returnStatus = false;
		
		if (!this.sessionRunning) {
			return returnStatus;
		}
		
		try	{
			sendMessageLock.lock();

			//Log.i(TAG + ": sendDataBytesToServer", "Sending Data to server....");
			tcpSocket.getOutputStream().write(message);
			//Log.i(TAG + ": sendDataBytesToServer", "Data to server sent....");
			
			returnStatus = true;
			
		} catch (Exception e) {
			Log.e(TAG, "sendDataBytesToServer failed...."); //, e);
			
			returnStatus = false;
		}
		
		try {
			sendMessageLock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in sendDataBytesToServer: ", e);
			
			returnStatus = false;
		}
		
		return returnStatus;
	}

	/**
	 * Generates a header for the input message and sends it to the server. Returns true if
	 * the send message operation is successful, else returns false.
	 *  
	 * @param message
	 * @param messageType
	 * @param isSeqPresent
	 * @param sequenceNumber
	 * 
	 * @return success
	 */
	private boolean sendMessageToServer(String message, int messageType,
			boolean isSeqPresent, int sequenceNumber) {
		String messageString;

		if (isSeqPresent) {
			messageString = MEESAGE_PREAMBLE + JOIN_DELIM + messageType + JOIN_DELIM +
			this.deviceID + JOIN_DELIM + Constants.APPLICATION_ID + JOIN_DELIM + sessionID +
			JOIN_DELIM + this.sequenceNum + JOIN_DELIM + message + JOIN_DELIM + MESSAGE_POSTAMBLE;
		} else {
			messageString = MEESAGE_PREAMBLE + JOIN_DELIM + messageType + JOIN_DELIM +
			this.deviceID + JOIN_DELIM + Constants.APPLICATION_ID + JOIN_DELIM + sessionID +
			JOIN_DELIM + message + JOIN_DELIM + MESSAGE_POSTAMBLE;
		}

		//Log.i(TAG, "sendMessageToServer: " + messageString);
		boolean isSent = sendDataBytesToServer(messageString.getBytes());
		if (!isSent) {
			stopStatsCollection("sendMessageToServer: Stats collection stopped while" +
					" sending message: " + message);
		}

		return isSent;
	}

	/**
	 * Send the end of session message to the server.
	 * 
	 * @return systemInfoString
	 */
	private boolean sendStartSessionMessage() {
		try {
			//String msgString = MEESAGE_PREAMBLE + JOIN_DELIM + NEW_SESSION + JOIN_DELIM +
			//	this.deviceID + JOIN_DELIM + sessionID;
			return sendMessageToServer("" + Constants.PLATFORM_ANDROID,
					NEW_SESSION, false, -1);
		} catch (Exception e) {
			Log.e(TAG, "Error while sending endSession message: " + e.getMessage());
		}

		return false;
	}

	/**
	 * Send the end of session message to the server.
	 * 
	 * @return systemInfoString
	 */
	public boolean sendEndSessionMessage(String message) {
		try {
			//String msgString = MEESAGE_PREAMBLE + JOIN_DELIM + REMOVE_SESSION + JOIN_DELIM +
			//	this.deviceID + JOIN_DELIM + sessionID + JOIN_DELIM + message;
			//Log.i(TAG, "SendEndSessionMessage: " + message);
			return sendMessageToServer(message, REMOVE_SESSION, false, -1);
		} catch (Exception e) {
			Log.e(TAG, "Error while sending endSession message: " + e.getMessage());
		}

		return false;
	}

	/**
	 * Send the location update to the server.
	 * 
	 * @return systemInfoString
	 */
	public boolean sendLocationMessage(String message) {
		try {
			return sendMessageToServer(message, LOCATION_INFO, false, -1);
		} catch (Exception e) {
			Log.e(TAG, "Error while sending location message: " + e.getMessage());
		}

		return false;
	}
	
	/**
	 * Send the current event statistics update to the server.
	 * 
	 * @return systemInfoString
	 */
	public boolean sendCurrentStatsMessage(String message) {
		try {
			return sendMessageToServer(message, EVENT_UPDATE_INFO, false, -1);
		} catch (Exception e) {
			Log.e(TAG, "Error while sending current stats message: " + e.getMessage());
		}

		return false;
	}

	/**
	 * Call this method to stop all the activity from this class.
	 *
	 * @param message - Debug message for this call.
	 */
	public void stopStatsCollection(String message) {
		this.sessionRunning = false;
		//Log.e(TAG, "stopping stats collection! From: " + message);

		// Stop the resource consumption collector periodic timer.
		try {
			/*
			if(resourceInfoGatheringTimer != null)	{
				resourceInfoGatheringTimer.cancel();
			}
			*/
			resourceThread.interrupt();
		} catch (Exception e) {
			Log.e(TAG + ": stopStatsCollection", "Exception while cancelling resourceGatheringInfo timer");
		}

		// Stop the battery broadcast receiver.
		try	{
			if (batteryReceiver != null) {
				this.context.unregisterReceiver(batteryReceiver);
				batteryReceiver = null;
			}
		} catch (Exception e)	{
			Log.e(TAG + ": stopStatsCollection", "Exception while unregistering battery receiver: " + e.getMessage());
		}

		// Stop the screen status broadcast receiver.
		try	{
			if (screenReceiver != null) {
				this.context.unregisterReceiver(screenReceiver);
				screenReceiver = null;
			}
		} catch (Exception e)	{
			Log.e(TAG + ": stopStatsCollection", "Exception while unregistering battery receiver: " + e.getMessage());
		}

		// Stop the TCP connection to the server.
		try	{
			if(tcpSocket != null)	{
				tcpSocket.close();
			}
		} catch (IOException e)	{
			Log.e(TAG + ": stopStatsCollection", "close failed");
		}

		//Log.v(TAG + ": stopStatsCollection", "stopped....");
	}

	/**
	 * Return the information about the device such as OS info, manufacturer info and model info.
	 * 
	 * @return systemInfoString - A formatted string containing the device related information.
	 */
	private String getSystemInformation() {
		String systemInfoString = "";

		try {
			String carrier = ((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE)).getNetworkOperatorName();
			if (carrier == null || carrier.equals("null") || carrier.equals("")) {
				carrier = "N_A";
			}

			systemInfoString += //tcpSocket.getLocalAddress().toString().replaceAll("[/]", "") + JOIN_DELIM +
				carrier + JOIN_DELIM +
				System.getProperty("os.version") + JOIN_DELIM +
				android.os.Build.VERSION.INCREMENTAL + JOIN_DELIM +
				android.os.Build.VERSION.SDK + JOIN_DELIM +
				android.os.Build.DEVICE  + JOIN_DELIM +
				android.os.Build.MODEL + JOIN_DELIM +
				android.os.Build.PRODUCT + JOIN_DELIM +
				android.os.Build.PRODUCT + JOIN_DELIM +
				//android.os.Build.MANUFACTURER  + JOIN_DELIM +  // Replaced after going to 1.5 from 1.6 API.
				android.os.Build.BOARD + JOIN_DELIM +
				android.os.Build.BRAND;

			// Processor related information.
			String processor = "NULL";
			String bogomips = "NULL";
			String hardware = "NULL";

			String memTotal = "";

			// Process the contents of the /proc/cpuinfo file.
			try {
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream("/proc/cpuinfo")));

				String text = "";
				while ((text = reader.readLine()) != null) {
					String[] toks = text.split("\\s*:\\s*");
					//Log.w(TAG + " cpuinfo", text + " " + toks[0]);

					if (toks[0].toLowerCase().contains("processor")) {
						processor = toks[1];
					} else if (toks[0].toLowerCase().contains("bogomips")) {
						bogomips = toks[1];
					} else if (toks[0].toLowerCase().contains("hardware")) {
						hardware = toks[1];
					}
				}

				//Log.w(TAG + " cpuinfo_output", processor + " " + bogomips + " " + hardware);
				systemInfoString += JOIN_DELIM + processor + JOIN_DELIM + bogomips + JOIN_DELIM + hardware;
			} catch (Exception e) {
				Log.e(TAG, "getStaticProcInfo. Error while reading /proc/cpuinfo file: " + e.getMessage());
			}

			// Process the contents of the /proc/meminfo file.
			try {
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream("/proc/meminfo")));

				String text = "";
				while ((text = reader.readLine()) != null) {
					String[] toks = text.split("\\s+");
					//Log.w(TAG + " meminfo", text + " " + toks[0]);

					if (toks[0].toLowerCase().contains("memtotal")) {
						memTotal = toks[1];
					}
				}

				//Log.w(TAG + " meminfo_output", memTotal);
				systemInfoString += JOIN_DELIM + memTotal; 
			} catch (Exception e) {
				Log.e(TAG, "getStaticProcInfo. Error while reading /proc/cpuinfo file: " + e.getMessage());
			}

			// Get Screen related information.
			Display display = ((WindowManager) (context.getSystemService(Context.WINDOW_SERVICE))).getDefaultDisplay();
			DisplayMetrics outMetrics = new DisplayMetrics();
			display.getMetrics(outMetrics);

			int densityDpi = 0; // outMetrics.densityDpi; // Replaced after going to 1.5 from 1.6 API.
			int width = outMetrics.widthPixels;
			int height = outMetrics.heightPixels;

			int xDpi = (int) outMetrics.xdpi;

			int yDpi = (int) outMetrics.ydpi;

			/*
			if (height > width) {
				width += height;
				height = width - height;
				width -= height;
			}
			 */

			systemInfoString += JOIN_DELIM + width;
			systemInfoString += JOIN_DELIM + height;
			systemInfoString += JOIN_DELIM + densityDpi;
			systemInfoString += JOIN_DELIM + xDpi;
			systemInfoString += JOIN_DELIM + yDpi;

			// Get location related information.
			LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
			if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
				systemInfoString += JOIN_DELIM + "1";
			} else {
				systemInfoString += JOIN_DELIM + "0";
			}

			if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
				systemInfoString += JOIN_DELIM + "1";
			} else {
				systemInfoString += JOIN_DELIM + "0";
			}	
	
			int currentActiveNetwork = 0;
			int currentActiveSubType = 0;
			
			try {
				ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
				NetworkInfo currentNetworkInfo = connectivityManager.getActiveNetworkInfo();
				
				currentActiveNetwork = Utils.getCurrentActiveNetworkType(currentNetworkInfo);
				currentActiveSubType = currentNetworkInfo.getSubtype();
			} catch (Exception e) {
				e.printStackTrace();
				Log.e(TAG, "getNetworkInfo " + e);
			}
			
			systemInfoString += JOIN_DELIM + currentActiveNetwork;
			systemInfoString += JOIN_DELIM + currentActiveSubType;
		} catch (Exception e) {
			e.printStackTrace();
			Log.e(TAG, "getSystemInformation: " + e);
			//return JOIN_DELIM;
			return "";
		}

		return systemInfoString;
	}

	/**
	 * Returns the information about the CPU and memory utilization for the application.
	 * 
	 * @return CPU + memory resource utilization statistics
	 */
	private Pair<Integer, String> getResourceUsageStats() {
		try {
			//Log.e(TAG, "Entering getResourceUsageStats");
			// Constants denoting the column names of top command's output
			String PID_STR = "PID";
			String CPU_STR = "CPU";
			String RSS_STR = "RSS";
			String VSS_STR = "VSS";
			String THR_STR = "THR";
			//String UID_STR = "UID";
			String NAME_STR = "Name";

			int MAX_CPU = 100;
			int MAX_RUNNING_PROCS = 200;
			int MAX_TOTAL_PROCS = 30000;
			int ZERO_VAL = 0;

			// Store the index of each column type as well as the total number of columns
			int pidCol = -1;
			int cpuCol = -1;
			int rssCol = -1;
			int vssCol = -1;
			int thrCol = -1;
			//int uid_col = -1;
			int nameCol = -1;
			int numColumns = -1;

			// Store the resource consumption related information for the application 
			int totalEntries = 0;
			int pidVal = -1;
			int cpuVal = 0;
			double rssVal = 0.0;
			double vssVal = 0.0;
			int thrVal = 0;
			//int uid_val = -1;

			int totalCpuVal = 0;

			// Processor related information.
			String bogomips = "NULL";

			// Memory related information.
			String memTotalAvail = "";
			long availMem = 0;
			long threshold = 0;

			// Load related information.
			String avgLoadOneMin = "";
			String avgLoadFiveMin = "";
			String avgLoadFifteenMin = "";
			int runningProcs = 0;
			int totalProcs = 0;
			float totalCpuIdleRatio = (float) -1.0;

			// Screen related information.,
			int currBrightnessValue = 0;
			boolean isScreenOn;

			// Audio related information.
			AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
			boolean isSpeakerOn, isWiredHeadsetOn;
			int audioLevel = 0, audioMaxLevel = 0;

			String text = "";
			// Process the contents of the /proc/cpuinfo file.
			try {
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream("/proc/cpuinfo")));

				while ((text = reader.readLine()) != null) {
					String[] toks = text.split("\\s*:\\s*");

					if (toks[0].toLowerCase().contains("bogomips")) {
						bogomips = toks[1];
					}
				}
			} catch (Exception e) {
				Log.e(TAG, "getResourceUsageStats. Error while reading /proc/cpuinfo file: " + e.getMessage());
			}

			// Process the contents of the /proc/stat file.
			try {
				RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
				String load = reader.readLine();

				//Log.w(TAG + " stat", load);
				String[] toks = load.split("\\s+");

				long curr_idle = Long.parseLong(toks[4]);
				long curr_cpu = Long.parseLong(toks[1]) + Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + Long.parseLong(toks[4])
				+ Long.parseLong(toks[6]) + Long.parseLong(toks[7]);

				reader.close();

				if (prev_cpu > 0) {
					totalCpuIdleRatio = ((float)(curr_cpu - prev_cpu)) / ((curr_cpu + curr_idle) - (prev_cpu + prev_idle));
				}
				prev_cpu = curr_cpu;
				prev_idle = curr_idle;
				//Log.w(TAG + " stat_ouput", "" + totalCpuIdleRatio + " " + toks[0] + " " + toks[1] + " " + toks[4]);
			} catch (Exception e) {
				Log.e(TAG, "getResourceUsageStats. Error while reading /proc/stat file: " + e.getMessage());
			}

			// Process the contents of the /proc/meminfo file.
			try {
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream("/proc/meminfo")));

				while ((text = reader.readLine()) != null) {
					String[] toks = text.split("\\s+");
					//Log.w(TAG + " meminfo", text + " " + toks[0]);

					if (toks[0].toLowerCase().contains("memfree")) {
						memTotalAvail = toks[1];
					}
				}
				//Log.w(TAG + " meminfo_output", memTotalAvail);
			} catch (Exception e) {
				Log.e(TAG, "getResourceUsageStats. Error while reading /proc/meminfo file: " + e.getMessage());
			}

			// Process the contents of the /proc/loadavg file.
			try {
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(new FileInputStream("/proc/loadavg")));

				while ((text = reader.readLine()) != null) {
					//Log.w(TAG + " loadavg", text);
					String[] toks = text.split("\\s+");

					avgLoadOneMin = toks[0];
					avgLoadFiveMin = toks[1];
					avgLoadFifteenMin = toks[2];

					String[] processInfo = toks[3].split("/");
					runningProcs = Integer.parseInt(processInfo[0]);
					totalProcs = Integer.parseInt(processInfo[1]);

				}
				//Log.w(TAG + " loadavg_ouput", avgLoadOneMin + " " + avgLoadFiveMin + " " +
				//		avgLoadFifteenMin + " " + runningProcs + " " + totalProcs);
			} catch (Exception e) {
				Log.e(TAG, "getResourceUsageStats. Error while reading /proc/loadavg file: " + e.getMessage());
			}

			// Calculate the resource utilization related information.
			ActivityManager actvityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
			MemoryInfo mi = new MemoryInfo();
			actvityManager.getMemoryInfo(mi);

			availMem = mi.availMem / 1048576L;
			threshold = mi.threshold / 1048576L;
			//Log.w("MemoryInfo", " " + availMem  + " " + threshold);


			Process process = Runtime.getRuntime().exec("/system/bin/top -n 2"); // -s cpu -m 20
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream()));

			while ((text = reader.readLine()) != null) {
				String str = text.replaceAll("^[\\s]+", "");
				String [] tokens = str.split("\\s+");
				//Log.w("Top Output: ", str);

				//for (int i = 0; i < tokens.length; i++) {
				//	Log.i("Contents", tokens[i]);
				//}

				if (str.contains(PID_STR)) {
					//Log.w("Using Process String", str);
					numColumns = tokens.length;

					for (int i = 0; i < tokens.length; i++) {
						if (tokens[i].contains(PID_STR)) {
							pidCol = i;
						} else if (tokens[i].contains(CPU_STR)) {
							cpuCol = i;
						} else if (tokens[i].contains(RSS_STR)) {
							rssCol = i;
						} else if (tokens[i].contains(VSS_STR)) {
							vssCol = i;
						} else if (tokens[i].contains(THR_STR)) {
							thrCol = i;
						}
						//else if (tokens[i].contains(UID_STR)) {
						//	uid_col = i;
						//} 
						else if (tokens[i].contains(NAME_STR)) {
							nameCol = i;
						}
					}

					//Log.w(TAG + "Column Info:" + num_columns, pid_col + " " + cpu_col + " " + rss_col + " " + vss_col + " " + thr_col + " " + uid_col + " " + name_col);
				} else if (str.contains(processString) && nameCol >= 0 && pidCol >= 0 && (tokens.length == numColumns)) {
					totalEntries++;
					//Log.w(TAG + "Found String: " + total_entries, str);
					if (pidCol >= 0) {
						pidVal = Integer.parseInt(tokens[pidCol]);
					}

					if (cpuCol >= 0) {
						cpuVal += Integer.parseInt(tokens[cpuCol].replaceAll("%", ""));
					}

					if (rssCol >= 0) {
						if (tokens[rssCol].contains("M")) {
							rssVal += Double.parseDouble(tokens[rssCol].replaceAll("M", ""));
						} else if (tokens[rssCol].contains("K")) {
							rssVal += (Double.parseDouble(tokens[rssCol].replaceAll("K", "")) / 1024.0);
						}
					}

					if (vssCol >= 0) {
						if (tokens[vssCol].contains("M")) {
							vssVal += Double.parseDouble(tokens[vssCol].replaceAll("M", ""));
						} else if (tokens[vssCol].contains("K")) {
							vssVal += (Double.parseDouble(tokens[vssCol].replaceAll("K", "")) / 1024.0);
						}
					}

					if (thrCol >= 0) {
						thrVal += Double.parseDouble(tokens[thrCol].replaceAll("%", ""));
					}
				}

				if (!str.contains(PID_STR) && nameCol >= 0 && pidCol >= 0 && tokens.length == numColumns) {
					totalCpuVal += Integer.parseInt(tokens[cpuCol].replaceAll("%", ""));
				}
			} 

			if (totalEntries > 0) {
				cpuVal /= totalEntries;
				rssVal /= totalEntries;
				vssVal /= totalEntries;
				thrVal /= totalEntries;
			}

			//Log.w("Output:", PROCESS_STRING + " " + total_entries + " " + pid_val + " " + cpu_val +
			//		" " + total_cpu_val + " " + rss_val + " " + vss_val + " " + thr_val);

			// TODO: Commented it out as is was giving warnings.
			//process.destroy();
			reader.close();

			// Screen related information.
			currBrightnessValue = android.provider.Settings.System.getInt(
					context.getContentResolver(), android.provider.Settings.System.SCREEN_BRIGHTNESS);
			isScreenOn = screenReceiver.isScreenOn();

			// Audio related information.
			isSpeakerOn = audioManager.isSpeakerphoneOn();
			// Removed for API level 4 support
			isWiredHeadsetOn = false; //audioManager.isWiredHeadsetOn();
			audioLevel = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM);
			audioMaxLevel = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM);

			/*
			Log.i(TAG, "alarmLevel: " + audioManager.getStreamVolume(AudioManager.STREAM_ALARM));
			Log.i(TAG, "alarmMaxLevel: " + audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM));

			Log.i(TAG, "dtmfLevel: " + audioManager.getStreamVolume(AudioManager.STREAM_DTMF));
			Log.i(TAG, "dtmfMaxLevel: " + audioManager.getStreamMaxVolume(AudioManager.STREAM_DTMF));

			Log.i(TAG, "musicLevel: " + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
			Log.i(TAG, "musicMaxLevel: " + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));

			Log.i(TAG, "notifLevel: " + audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION));
			Log.i(TAG, "notifMaxLevel: " + audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION));

			Log.i(TAG, "ringLevel: " + audioManager.getStreamVolume(AudioManager.STREAM_RING));
			Log.i(TAG, "ringMaxLevel: " + audioManager.getStreamMaxVolume(AudioManager.STREAM_RING));

			Log.i(TAG, "systemLevel: " + audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM));
			Log.i(TAG, "systemMaxLevel: " + audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM));

			Log.i(TAG, "voiceLevel: " + audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL));
			Log.i(TAG, "voiceMaxLevel: " + audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL));
			 */

			// Setting values to defaults in case of out of range values.
			cpuVal = (cpuVal < ZERO_VAL) ? ZERO_VAL : cpuVal;
			cpuVal = (cpuVal > MAX_CPU) ? MAX_CPU : cpuVal;

			totalCpuVal = (totalCpuVal < ZERO_VAL) ? ZERO_VAL : totalCpuVal;
			totalCpuVal = (totalCpuVal > MAX_CPU) ? MAX_CPU : totalCpuVal;

			totalProcs = (totalProcs < ZERO_VAL) ? ZERO_VAL : totalProcs;
			totalProcs = (totalProcs > MAX_TOTAL_PROCS) ? MAX_TOTAL_PROCS : totalProcs;

			runningProcs = (runningProcs < ZERO_VAL) ? ZERO_VAL : runningProcs;
			runningProcs = (runningProcs > MAX_RUNNING_PROCS) ? MAX_RUNNING_PROCS : runningProcs;

			// Create the return object.
			Pair<Integer, String> resourceInfo = new Pair<Integer, String>(this.sequenceNum, "");
			resourceInfo.second = totalEntries + JOIN_DELIM
			+ pidVal + JOIN_DELIM
			+ cpuVal + JOIN_DELIM
			+ rssVal + JOIN_DELIM
			+ vssVal + JOIN_DELIM
			+ thrVal + JOIN_DELIM
			+ bogomips + JOIN_DELIM
			+ totalCpuIdleRatio + JOIN_DELIM
			+ avgLoadOneMin + JOIN_DELIM
			+ avgLoadFiveMin + JOIN_DELIM
			+ avgLoadFifteenMin + JOIN_DELIM
			+ runningProcs + JOIN_DELIM
			+ totalProcs + JOIN_DELIM
			+ memTotalAvail + JOIN_DELIM
			+ availMem + JOIN_DELIM
			+ threshold + JOIN_DELIM
			+ totalCpuVal + JOIN_DELIM
			+ currBrightnessValue + JOIN_DELIM;

			if (isScreenOn) {
				resourceInfo.second += "1" + JOIN_DELIM;
			} else {
				resourceInfo.second += "0" + JOIN_DELIM;
			}

			if (isSpeakerOn) {
				resourceInfo.second += "1" + JOIN_DELIM;
			} else {
				resourceInfo.second += "0" + JOIN_DELIM;
			}

			if (isWiredHeadsetOn) {
				resourceInfo.second += "1" + JOIN_DELIM;
			} else {
				resourceInfo.second += "0" + JOIN_DELIM;
			}

			resourceInfo.second += audioLevel + JOIN_DELIM;
			resourceInfo.second += audioMaxLevel;

			// Increase the sequence number.
			this.sequenceNum ++;

			//Log.i(TAG, "audioMaxLevel: " + audioMaxLevel);
			//resourceUsageStats = MEESAGE_PREAMBLE + JOIN_DELIM + RESOURCE_INFO + JOIN_DELIM +
			//this.deviceID + JOIN_DELIM +  sessionID + JOIN_DELIM + this.sequenceNum + JOIN_DELIM + resourceUsageStats;

			//Log.e(TAG, "Returning from getResourceUsageStats");
			return resourceInfo;
		} catch (Exception e) {
			Log.e(TAG, "getResourceUsageStats: " + e.toString());
			return null;
		}
	}
	
	/**
	 * Run a thread to obtain the resource consumption statistics periodically.
	 * 
	 * @author Ashish Patro
	 */
	private class ResourceThread implements Runnable {

		private final int resourceMeasurementInterval; 

		public ResourceThread(int resourceMeasurementInterval) {
			this.resourceMeasurementInterval = resourceMeasurementInterval; 
		}

		public void run() {
			try {
				Thread.sleep(Constants.INIT_SLEEP_TIME);

				while (true) {
					//Log.e(TAG, "Calling getResourceUsageStats");
					Pair<Integer, String> resourceInfo = getResourceUsageStats();

					if (resourceInfo != null) {
						sendMessageToServer(resourceInfo.second,
								RESOURCE_INFO, true, resourceInfo.first);
					}
					
					try {
						if (!isApplicationUidSent && InsightLib.getApplicationCharID() != null) {
							Thread.sleep(Constants.INIT_SLEEP_TIME);
							
							sendMessageToServer(InsightLib.getApplicationCharID(),
									APPUID_INFO, false, -1);
							isApplicationUidSent = true;
						}
					} catch (Exception e) {
						Log.e(TAG, "Exception while sending application UID.. "); // + e);						
					}

					Thread.sleep(resourceMeasurementInterval);
				}
			} catch (Exception e) {
				Log.e(TAG, "Exception in resource info thread: " + e.getMessage());						
			}
		}
	}

	/**
	 * Uses a broadcast receiver to passively hear for battery status changed broadcasts.
	 * 
	 * @author Ashish Patro
	 */
	private class ParseBatteryStats extends BroadcastReceiver {
		public static final String TAG = "ParseBatteryStats";

		private int sequenceNum;
		private int prevLevel;
		private int prevPlugged;

		public ParseBatteryStats() {
			this.sequenceNum = 0;
			this.prevLevel = -100;
			this.prevPlugged = -100;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			try {
				/*
				plugged = intent.getStringExtra(BatteryManager.EXTRA_PLUGGED);
				present = intent.getStringExtra(BatteryManager.EXTRA_PRESENT);
				status = intent.getStringExtra(BatteryManager.EXTRA_STATUS);
				 */

				int currLevel = intent.getIntExtra("level", -1);
				int currPlugged = intent.getIntExtra("plugged", -1);

				this.sequenceNum ++;

				/**
				 *  Only send the information to the server if the battery level has
				 *  changed or the devicePlug status is changed.
				 *  This has been done to reduce the message overhead.
				 */
				if (currLevel != this.prevLevel || currPlugged != this.prevPlugged) {
					String batteryInfo = intent.getIntExtra("level", -1) + JOIN_DELIM
					+ intent.getIntExtra("scale", -1) + JOIN_DELIM
					+ intent.getIntExtra("temperature", -1) + JOIN_DELIM
					+ intent.getIntExtra("voltage", -1) + JOIN_DELIM
					+ intent.getIntExtra("health", -1) + JOIN_DELIM
					+ intent.getStringExtra("technology") + JOIN_DELIM
					+ intent.getIntExtra("plugged", -1);

					//InsightLib.setBatteryTechnology(intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY));

					//batteryInfo = MEESAGE_PREAMBLE + JOIN_DELIM + BATTERY_INFO + JOIN_DELIM + this.deviceID + JOIN_DELIM +
					//this.sessionId + JOIN_DELIM + this.sequenceNum + JOIN_DELIM + batteryInfo; 

					sendMessageToServer(batteryInfo, BATTERY_INFO, true, this.sequenceNum);
					
					this.prevLevel = currLevel;
					this.prevPlugged = currPlugged;
				} else {
					//Log.i(TAG, "Skipping battery update to server...");
				}
			} catch (Exception e) {
				Log.e(TAG, "Exception while processing battery information: " + e.getMessage());
			}
		}
	}

	/**
	 * Used to obtain the screen status, whether the screen is on/off.
	 * 
	 * @author Ashish Patro
	 */
	private class ScreenReceiver extends BroadcastReceiver {

		private boolean isScreenOn = true;

		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
				isScreenOn = false;
			} else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
				isScreenOn = true;
			}
		}

		public boolean isScreenOn() {
			return isScreenOn;
		}
	}

	/**
	 * This class is used to run a thread the receives the TCP keep alive messages. 
	 * 
	 * @author Ashish Patro
	/*
	class ReceiveTcpKeepAlive implements Runnable {
		public static final String TAG = "ReceiveTcpKeepAlive";
		private ResourceConsumptionStats resourceConsumptionStats;

		// Constructor
		public ReceiveTcpKeepAlive(ResourceConsumptionStats resourceConsumptionStats) {
			this.resourceConsumptionStats = resourceConsumptionStats;
		}

		public void run()	{
			try {
				byte[] msg = null;
				
				while (true) {
					Thread.sleep(Constants.KEEP_ALIVE_READ_INTERVAL);
					msg = resourceConsumptionStats.readDataFromServer();
					if (msg == null) {
						Log.e(TAG, "receive failed.");
						break;
					}
					
					Log.i(TAG, "Received Keep Alive: " + new String(msg));
				}
			} catch (Exception e) {
				Log.e(TAG, "Exception: ", e);
			}
		}
	}
	*/
}
