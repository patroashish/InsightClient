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

/**
 * This file contains the main ping measurement code. The central class here is the PingClient class
 * that. A few other classes are present in this file that are used by the PingClientClass :
 * 
 * - PingTimerTask
 * - ReceiveUDP
 * - ReceiveTCP
 * - MyPhoneStateListener (not used currently)
 */

import java.io.IOException;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;

import com.wisc.insightlib.json.JSONObject;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * This class is responsible for performing the ping measurements and contains the logic 
 * for pinging the measurement server using TCP and UDP based connections. This class
 * performs the ping measurements for a pre-determined duration of time and after the
 * expiration of this duration, it closes the connection with the measurement server.
 *    
 * @author Ashish Patro
 *
 */
public class PingClient implements Runnable {
	public static final String TAG = "PingClient";

	private static final boolean SHOW_DBG = false; 

	public static final int LOCAL_TCP = 1;
	public static final int LOCAL_UDP = 2;

	public static final long MAX_DATA_SIZE = 65527;
	public static final int CURRENT_DATA_SIZE = 512;

	public static final int DEVICEID_LENGTH = 40;

	private String serverIP = InsightLib.getServerHostname();
	private int serverPort = Constants.SERVER_PORT_PING;

	private long pingInterval;

	// The TCP and UDP sockets used to perform ping measurments to the Insight server
	private Socket tcpSocket = null;
	private DatagramSocket udpSocket = null;

	private InetAddress serverAddr = null;

	// Threads used to perform the ping measurments with the server.s
	private Timer pingTimer = null;
	private PingTimerTask pingTimerThread = null;
	private ReceiveUDP receiveUDPThread = null;
	private Thread udpThread = null;
	private ReceiveTCP receiveTCPThread = null;
	private Thread tcpThread = null;

	byte[] tcpPacket = null;
	DatagramPacket udpPacket = null;

	boolean isTcpAlive = false;
	boolean isNewTCPSession = true;

	private TelephonyManager SignalManager;
	private MyPhoneStateListener signalListener;        
	private WifiManager wifiManager;      

	/* Statistics Information */
	private String deviceIdString;
	private long sessionID;
	private int signalStrength;

	/* Network interface information */
	private ConnectivityManager connectivityManager;
	private NetworkInfo currentInterfaceInfo;
	private NetworkInfo cellInterfaceInfo;
	private NetworkInfo wifiInterfaceInfo;

	private Thread clientThread;

	public DatagramSocket getUdpSocket() {
		return udpSocket;
	}
	
	/**
	 * Initialize the instance and start a new thread to perform the ping measurements.
	 * 
	 * @param context
	 * @param mContext
	 */
	public PingClient(Context context, String deviceIdString, long sessionID) {
		this.deviceIdString = deviceIdString;
		this.sessionID = sessionID;

		if (serverPort != 0) {
			try {
				// Reference for cellular information.
				SignalManager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);

				signalListener = new MyPhoneStateListener();        
				SignalManager.listen(signalListener,PhoneStateListener.LISTEN_SIGNAL_STRENGTH);

				// Reference for WiFi information.
				wifiManager = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);

				// Get refernce to interface information.
				connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
				currentInterfaceInfo = connectivityManager.getActiveNetworkInfo();
				cellInterfaceInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
				wifiInterfaceInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

			} catch (Exception e){
				Log.e(TAG , "Exception" + e.toString());
			}

			try {
				// TODO
				clientThread = new Thread(this,TAG); //"MobileStats");
				clientThread.setDaemon(true);
				clientThread.start();

			} catch (Exception e) {
				Log.e(TAG , "PingClient Exception " + e.toString());
			}
		}
	}

	/**
	 * This is the main thread for the ping measurements. It initiates a connection with
	 * the measurement server and establishes a TCP/UDP connection with the server. After
	 * connecting to the server via UDP and TCP, it spawns off two more threads : one to 
	 * receive the TCP packets from the measurement server and the second one to receive
	 * UDP packets from the measurement server.
	 * 
	 * (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	public void run(){

		try {
			// Known Issue. Takes 10 seconds to timeout for error.
			try	{
				serverAddr = InetAddress.getByName(serverIP);
			}	catch (UnknownHostException e) {
				Log.e(TAG, "Don't know about host: " + serverIP);
			}

			if (SHOW_DBG) {
				Log.v(TAG, "Calling createTCP");
			}

			if (createTCP(serverAddr) < 0) {
				Log.v(TAG, "createTCP failed");
				stopCurrentThread("PingClient main thread...");
			}

			if (SHOW_DBG) {
				Log.v(TAG, "Calling createUDP");
			}

			if (createUDP(serverAddr) < 0) {
				Log.v(TAG, "createUDP failed");
			}

			/* Initialize the statistics variables */
			initializeStats();

			/* Allocate initial DatagramPacket */
			udpPacket = new DatagramPacket(new byte[CURRENT_DATA_SIZE], CURRENT_DATA_SIZE);

			/* pingInternal initialized */
			pingInterval = Constants.PING_INTERVAL;

			if (pingInterval > 0) {
				//Log.v(TAG, "Create ping timer, interval = " + pingInterval);
				pingTimer = new Timer(true);
				pingTimerThread = new PingTimerTask(this);
				pingTimer.schedule(pingTimerThread, Constants.TIMER_START_DELAY, pingInterval);
			}

			// Send initial UDP ping (for punch-through)
			sendUDPPing(); 

			// Start the TCP measurement thread.
			receiveTCPThread = new ReceiveTCP(this);
			tcpThread = new Thread(receiveTCPThread);
			tcpThread.setDaemon(true);
			tcpThread.start();

			// Start the UDP measurement thread.
			receiveUDPThread = new ReceiveUDP(this);
			udpThread = new Thread(receiveUDPThread);
			udpThread.setDaemon(true);
			udpThread.start();

		} catch(Exception e) {
			Log.e(TAG, "Exception in main PingClient thread: ", e);
		}
	}

	/**
	 * Receive a TCP packet from the measurement server. 
	 * 
	 * @param receive
	 * @param buffer
	 * @return boolean indicating whether a TCP packet was received successfully or not.
	 */
	boolean receiveTcpPacket(byte receive[], byte buffer[]) {
		int tcpCount = 0;
		
		if (isTcpAlive) {
			try	{
				if (SHOW_DBG) {
					Log.v(TAG, "readTcpPacket: Will now try to read from tcpSocket");
				}

				tcpCount = tcpSocket.getInputStream().read(receive);

				if (SHOW_DBG) {
					Log.v(TAG, "readTcpPacket: Read from tcpSocket");
				}

				if (tcpCount < 0) {
					if (SHOW_DBG) {
						Log.v(TAG, "readTcpPacket: Read returned < 0");
					}
					isTcpAlive = false;
				}

				if (SHOW_DBG) {
					Log.v(TAG, "readTcpPacket: Done.");
				}
			}	catch (IOException e) {
				Log.e(TAG, "readTcpPacket: getInputStream IOException");
				isTcpAlive = false;
				// Relying on ping to restart TCP session
			}

			if (SHOW_DBG) {
				Log.v(TAG, "readTcpPacket: tcpcount : " + tcpCount + " tcpAlive : " + isTcpAlive);
			}

			if (isNewTCPSession && (tcpCount > 0))	{
				if (SHOW_DBG) {
					Log.v(TAG, "readTcpPacket: Parsing initial packet from server");
				}
				pingInterval = (FloatByteArrayUtil.byteArrayToShort(receive) * Constants.SEC);

				if (SHOW_DBG) {
					Log.v(TAG, "readTcpPacket: Reading ping interval as " + pingInterval);
				}

				if (pingInterval != Constants.PING_INTERVAL)	{
					if (pingTimerThread != null) {
						pingTimerThread.cancel();
					}

					if (pingTimerThread != null) {
						pingTimer.cancel();
					}

					if (pingInterval > 0) {
						if (SHOW_DBG) {
							Log.v(TAG, "readTcpPacket: Recreate ping timer, interval = " + pingInterval);
						}
						pingTimer = new Timer();
						pingTimerThread = new PingTimerTask(this);
						pingTimer.schedule(pingTimerThread, Constants.TIMER_START_DELAY, pingInterval);
					}
				}
				
				isNewTCPSession = false;
			} else if(isTcpAlive && (tcpCount > 0))	{
				try	{
					if (SHOW_DBG) {
						Log.v(TAG, "readTcpPacket: Echoing TCP packet");
					}
					
					buffer[0] = receive[0];
					buffer[1] = receive[1];
					
					tcpSocket.getOutputStream().write(buffer, 0, tcpCount);
				}	catch (IOException e)	{
					Log.e(TAG, "getOutputStream IOException");
					isTcpAlive = false;
				}
			}
		}
		return isTcpAlive;
	}

	/**
	 * Make sure that the UDP/TCP connections to the measurement server are closed. 
	 * 
	 * (non-Javadoc)
	 * @see java.lang.Object#finalize()
	 */
	@Override
	protected void finalize() throws Throwable {
		try {
			stopCurrentThread("Finalize");
		} catch (Exception e) {
			Log.e(TAG, "finalize : Stop Exception...");
		} finally {
			Log.v(TAG, "onStop : Stopped.");			
			super.finalize();
		}
	}

	/**
	 * Stop the current ping measurements and close all connections with the server.
	 */
	/**
	 * @param caller
	 */
	public void stopCurrentThread(String caller)
	{
		if (SHOW_DBG) {
			Log.w(TAG, "stopCurrentThread: called by " + caller);
		}

		try {
			if (pingTimerThread != null) {
				pingTimerThread.cancel();
			}
		} catch (Exception e)	{
			Log.e(TAG, "stopCurrentThread: Exception on canceling pingTimerThread: " +
					e.toString());
		}

		try {
			if (pingTimer != null) {
				pingTimer.cancel();
			}
		} catch (Exception e)	{
			Log.e(TAG, "stopCurrentThread: Exception on canceling pingTimer: " + 
					e.toString());
		}

		try	{
			if (udpThread != null) {	
				udpThread.interrupt();
				receiveUDPThread.running = false;
			}
		} catch (Exception e)	{
			Log.e(TAG, "stopCurrentThread: Exception occurred on canceling udpThread: " +
					e.toString());
		}

		try	{
			if (tcpThread != null)	{	
				tcpThread.interrupt();
				receiveTCPThread.running = false;
			}
		} catch (SecurityException e)	{
			Log.e(TAG, "stopCurrentThread: Exception occurred on canceling tcpThread: " +
					e.toString());
		}

		try	{
			if (tcpSocket != null)	{
				tcpSocket.close();
			}

			if (udpSocket != null)	{
				udpSocket.close();
			}
		} catch (IOException e)	{
			Log.e(TAG, "stopCurrentThread: udpSocker close failed: " + e.toString());
		}

		// Stop the phone state listener.
		try	{
			if (signalListener != null) {
				SignalManager.listen(signalListener,PhoneStateListener.LISTEN_NONE);
			}
		} catch (Exception e)	{
			Log.e(TAG, "stopCurrentThread: Exception while unregistering" +
					" signalstrength receiver: " + e.getMessage());
		}

		if (SHOW_DBG) {
			Log.v(TAG, "stopThread : stopped");
		}
	}

	/**
	 * Send a TCP ping packet to the server.
	 */
	void sendTCPPing() {
		byte[] pack = new byte[CURRENT_DATA_SIZE];
		constructTCPPing(pack);

		try	{
			if (tcpSocket == null) {
				Log.e(TAG, "sendTCP : tcpSocket is null");
			}
			
			tcpSocket.getOutputStream().write(pack);
		}	catch (IOException e)	{
			Log.e(TAG, "sendTCP : tcpSocket.getOutputStream.write failed");

			try	{
				if (tcpSocket != null) {
					tcpSocket.close();
				}
			}	catch (IOException o)	{
				Log.e(TAG, "sendTCPPing : close failed");
			}

			/* Try establishing TCP session again */
			createTCP(serverAddr);
		}	catch (NullPointerException e)	{
			Log.e(TAG, "sendTCP : Null pointer exception");
		}
	}

	/**
	 * Send a UDP ping packet to the measurement server.
	 */
	void sendUDPPing() {
		DatagramPacket packet = null;
		byte[] pack = new byte[CURRENT_DATA_SIZE];
		constructUDPPing(pack);

		try	{
			packet = new DatagramPacket(pack, CURRENT_DATA_SIZE);

			if (udpSocket == null) {
				Log.e(TAG, "sendUDP : udpSocket is null");
			}
			
			udpSocket.send(packet);
		}	catch (IOException e) {
			Log.e(TAG, "sendUDP : Couldn't get I/O for the connection to: " + serverIP);
		}	catch (NullPointerException e) {
			Log.e(TAG, "sendUDP : Null pointer exception");
		}
	}

	/**
	 * Get a unique identifier for the client.
	 */
	private void initializeStats() {
		if (deviceIdString != null) {
			deviceIdString.getBytes();
		}
	}
	
	/**
	 * Creates a packet for the initial TCP ping.
	 * 
	 * @param packet buffer 
	 */
	private void constructTCPPing(byte[] pack) {
		constructBasicInfo(pack, 1);
		
		if (SHOW_DBG) {
			Log.v(TAG, "constructBasicInfo constructTCPping");
		}
		
		return;
	}

	/**
	 * Creates a packet for the initial UDP ping.
	 * 
	 * @param packet buffer
	 */
	private void constructUDPPing(byte[] pack)	{
		constructBasicInfo(pack, 1);
		
		if (SHOW_DBG) {
			Log.v(TAG, "constructBasicInfo constructUDPping!");
		}
		
		return;
	}

	/**
	 * Create a TCP connection to the measurement server.
	 * 
	 * @param serverAddr
	 * 
	 * @return Error/Success Code
	 */
	private int createTCP(InetAddress serverAddr) {
		try	{

			tcpSocket = new Socket();
			if (tcpSocket == null) {
				Log.e(TAG, "createTCP : tcpSocket == null");
				stopCurrentThread("CreateTCP 1");
				return -1;
			}

			// Connect using a timeout of 5 seconds
			tcpSocket.connect(new InetSocketAddress(serverAddr, serverPort),
					Constants.SOCKET_CONNECT_TIMEOUT);

			// Make the read blocking indefinitely
			tcpSocket.setSoTimeout(0); //Constants.SOCKET_READ_TIMEOUT);
			
		} catch (Exception e) {
			Log.e(TAG, "createTCP : Couldn't get I/O for the TCP connection to: " + serverIP + 
					" "  + e.toString());

			stopCurrentThread("CreateTCP 2");
			return -1;
		}

		isTcpAlive = true;
		isNewTCPSession = true;
		return 0;
	}

	/**
	 * Create a UDP connection to the measurement server.
	 * 
	 * @param serverAddr
	 * @return Error/Success Code
	 */
	private int createUDP(InetAddress serverAddr) {
		try	{
			udpSocket = new DatagramSocket();

			udpSocket.connect(serverAddr, serverPort);

			// setting the ToS field to see if this can reduce the delay
			// udpSocket.setTrafficClass(0x10);

		}	catch (SocketException e) {
			Log.e(TAG, "createUDP : Error occurred while creating or binding the UDP socket");
			return -1;
		}	catch (IllegalArgumentException e)		{
			Log.e(TAG, "createUDP : UDP connect() argument exception");
			return -1;
		}	catch (NullPointerException e) {
			Log.e(TAG, "createUDP : Null pointer exception");
			return -1;
		}

		return 0;
	}

	/**
	 * This is a monster method. It is used to construct a packet for the ping measurement. The
	 * "pack" variable is used to reference the memory buffer for the packet. The offset is used
	 * to determine the starting index to insert contents into the packet. The offset is 1 in
	 * case of the first packet and 2 in the case of following packets. Client specific information
	 * is added to the packet using this method. This data is then stored at the measurement server.
	 * The various attributed written to the packet are the parameter values related to the
	 * client's WiFi/Cellular connection. The indices are hard-coded and the same are used by the
	 * server, so any new information added to the packet should go towards the end of the packet. 
	 * 
	 * @param packet buffer
	 * 
	 * @param offset
	 */
	@SuppressWarnings("unchecked")
	void constructBasicInfo(byte[] packet, int offset) {
		if (SHOW_DBG) {
			Log.i(TAG, "constructBasicInfo: Constructing Packet with offiset " + offset);
		}

		JSONObject temp = new JSONObject();

		/* Populate Device and Session ID */
		temp.put("appID", Constants.APPLICATION_ID);
		temp.put("deviceID", deviceIdString);
		temp.put("sessionID", sessionID);

		/* Populate signal strength */
		// TODO : get the bar strength too.
		signalStrength = signalListener.getSigStrength();
		temp.put("cellSignal", signalStrength);

		// Cell or Wifi
		int wifiRssi = 0, wifiSpeed = 0;
		if (wifiManager.isWifiEnabled()) {
			wifiRssi = wifiManager.getConnectionInfo().getRssi();
			wifiSpeed =  wifiManager.getConnectionInfo().getLinkSpeed();
		}
		else {
			//Log.w(TAG, "constructBasicInfo: WiFi is disabled");
		}

		temp.put("wifiRssi", wifiRssi);
		temp.put("wifiSpeed", wifiSpeed);

		// New fields added here.
		currentInterfaceInfo = connectivityManager.getActiveNetworkInfo();
		cellInterfaceInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
		wifiInterfaceInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

		// Platform type: Android
		temp.put("platform", Constants.PLATFORM_ANDROID);

		// WiFi related information.
		byte wifiAvailable = 0, wifiConnected = 0, wifiFailover = 0;
		int wifiState = 0, wifiSubtype = 0;
		String wifiSubtypeName = "NA", wifiExtraInfo = "NA", wifiReason = "NA"; 

		if (wifiInterfaceInfo != null) {
			// Is WiFi available ? 2 = yes, 1 = no, 0 = WiFi not active.
			wifiAvailable = wifiInterfaceInfo.isAvailable() ? (byte) 2 : (byte) 1;

			// Is WiFi connected ?
			wifiConnected = wifiInterfaceInfo.isConnected() ? (byte) 1 : (byte) 0;

			// Is WiFi the fallover network ?
			wifiFailover = wifiInterfaceInfo.isFailover() ? (byte) 1 : (byte) 0;

			// Get the state of the WiFi network Connection.
			wifiState = wifiInterfaceInfo.getState().ordinal();

			// Get the Subtype, if available for the WiFi network. 
			wifiSubtype = wifiInterfaceInfo.getSubtype();

			// Get the name of the Subtype of the WiFi network.
			wifiSubtypeName = wifiInterfaceInfo.getSubtypeName(); 
			wifiSubtypeName = ((wifiSubtypeName == null || wifiSubtypeName.equals("")) ? "NA" : wifiSubtypeName);

			wifiExtraInfo = wifiInterfaceInfo.getExtraInfo();
			wifiExtraInfo = ((wifiExtraInfo == null || wifiExtraInfo.equals("")) ? "NA" : wifiExtraInfo);

			wifiReason = wifiInterfaceInfo.getReason();
			wifiReason = ((wifiReason == null || wifiReason.equals("")) ? "NA" : wifiReason);
		}

		//String wifiInformation = wifiSubtypeName + "@" + wifiExtraInfo + "@" + wifiReason;
		temp.put("wifiAvail", wifiAvailable);
		temp.put("wifiCon", wifiConnected);
		temp.put("wifiFO", wifiFailover);
		temp.put("wifiState", wifiState);
		temp.put("wifiSubtype", wifiSubtype);
		//temp.put("wifiInfo", wifiInformation);

		// Cellular related information.
		byte cellAvailable = 0, cellConnected = 0, cellFailover = 0;
		int cellState = 0, cellSubtype = 0;
		String cellSubtypeName = "NA", cellReason = "NA";

		if (cellInterfaceInfo != null) {
			// Is Cellular network available ? 2 = yes, 1 = no, 0 = WiFi not active.
			cellAvailable = cellInterfaceInfo.isAvailable() ? (byte) 2 : (byte) 1;

			// Is Cellular network connected ?
			cellConnected =  cellInterfaceInfo.isConnected() ? (byte) 1 : (byte) 0;

			// Is Cellular the failover network ?
			cellFailover = cellInterfaceInfo.isFailover() ? (byte) 1 : (byte) 0;

			// Get the state of the Cellular network Connection.
			cellState = cellInterfaceInfo.getState().ordinal();

			// Get the Subtype, if available for the Cellular network. 
			cellSubtype = cellInterfaceInfo.getSubtype();

			// Get the name of the Subtype of the Cellular network.
			cellSubtypeName = cellInterfaceInfo.getSubtypeName();
			cellSubtypeName = (cellSubtypeName == null || cellSubtypeName.equals("")) ? "NA" : cellSubtypeName;

			cellReason = cellInterfaceInfo.getReason();
			cellReason = (cellReason == null || cellReason.equals("")) ? "NA" : cellReason;
		}

		//String cellInformation = cellSubtypeName + "@" + cellExtraInfo + "@" + cellReason;

		temp.put("cellAvail", cellAvailable);
		temp.put("cellConn", cellConnected);
		temp.put("cellFO", cellFailover);
		temp.put("cellState", cellState);
		temp.put("cellSubtype", cellSubtype);
		//temp.put("cellInfo", cellInformation);	

		// Parse contents to find out the characteristics of the active network.
		int activeType = 0, activeSub = 0;
		String activeSubName =  "NA", activeExtraInfo = "NA", activeReason = "NA";

		if (currentInterfaceInfo != null) {
			// Get subtype of the active network.
			activeSub = currentInterfaceInfo.getSubtype();

			activeType = Utils.getCurrentActiveNetworkType(currentInterfaceInfo);

			activeSubName = currentInterfaceInfo.getSubtypeName();
			activeSubName = (activeSubName == null || activeSubName.equals("")) ? "NA" : activeSubName;

			activeExtraInfo = currentInterfaceInfo.getExtraInfo();
			activeExtraInfo = (activeExtraInfo == null || activeExtraInfo.equals("")) ? "NA" : activeExtraInfo;

			activeReason = currentInterfaceInfo.getReason();
			activeReason = (activeReason == null || activeReason.equals("")) ? "NA" : activeReason;			
		} 

		//String activeInformation = activeSubName + "@" + activeExtraInfo + "@" + activeReason;
		temp.put("activeType", activeType);
		temp.put("activeSub", activeSub);
		//temp.put("activeInfo", activeInformation);

		String retString = temp.toJSONString();

		//Log.v(TAG, "constructPacket : " + retString);
		byte temp_bytes[] = retString.getBytes();

		//Log.v(TAG, "constructPacket with length " + (offset + temp_bytes.length) +
		//		" : " + packet.length);

		for (int i = offset; i < offset + temp_bytes.length; i++) {
			packet[i] = temp_bytes[i - offset];
			//Log.v(TAG, "index : " + i + " " + (char) packet[i] +  " " + (char) temp_bytes[i - offset]);
		}

		/*
		boolean isStateChanged = clientState.stateChanged(pack, offset + 18, offset + 19);

		byte abc[] = clientState.getClientState();
		// Update State.
		clientState.updateState(pack, offset + 18, 100); // Not using more than 100 for now.

		clientState.counter ++;

		if (isStateChanged) {
			clientState.isRestartingMainThread = true;
			Log.v(TAG, "state changed : Step1 --Restarting network Connection--: " + "New " + pack[offset + 18] +
					" " + pack[offset +19]);
			clientState.stopMobileStats();
			Log.v(TAG, "state changed : Step2 --Stopped main MobileStats Thread--: " + "Old " + abc[offset + 18] +
					" " + abc[offset + 19]);
			clientState.startMobileStats();
			Log.v(TAG, "state changed : Step3 --Started main MobileStats Thread--: " + retString);
			clientState.isRestartingMainThread = false;
		} else {
			Log.v(TAG, "State not changed : " + retString);
		}
		 */
	}
}

/**
 * Used to send a TCP and UDP to the measurement server.
 * 
 * @author Ashish Patro
 *
 */
class PingTimerTask extends TimerTask {
	public static final String TAG = "PingTimerTask";

	PingClient mobileStatsCaller = null;

	public PingTimerTask(PingClient mobileStats)	{
		mobileStatsCaller = mobileStats;
	}

	@Override
	public final void run()	{
		try {
			sendPing();
		} catch(Exception e) {
			Log.e(TAG, "PingTimerTask: Exception which sending Ping", e);
		}
	}

	private void sendPing()	{
		//Log.i(TAG, "In PingTimerTask!");
		mobileStatsCaller.sendUDPPing();
		mobileStatsCaller.sendTCPPing();
	}
}

/**
 * This class is used to run a thread the receives the UDP based packets from the measurement
 * server during the ping test.
 * 
 * @author Ashish Patro
 *
 */
class ReceiveUDP implements Runnable {
	public static final String TAG = "ReceiveUDP";

	PingClient mobileStatsCaller = null;
	private DatagramSocket udpSocket;

	public volatile boolean running = true;

	// Constructor
	public ReceiveUDP(PingClient mobileStats)	{
		this.mobileStatsCaller = mobileStats;
		this.udpSocket = mobileStats.getUdpSocket(); 
	}

	/**
	 * This thread receives the TCP packets from the server. After the a TCP packet from
	 * the server, it creates the next packet to be sent with the latest information.
	 * (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	public void run()	{
		byte temp[] = new byte[Constants.PACKET_SIZE];
		byte receive[] = new byte[Constants.PACKET_SIZE];

		try	{
			//Log.v(TAG, "receiveUDP Starting Thread!");
			mobileStatsCaller.constructBasicInfo(temp, 2);

			while(running){
				if(!receiveUdpPacket(receive, temp)) {
					Log.e(TAG, "receiveUDP returned FALSE!");
					return;
				}
				temp = new byte[512];
				receive = new byte[512];
				mobileStatsCaller.constructBasicInfo(temp, 2);
				//Log.v(TAG, "constructBasicInfo receiveUDP second!");
				//if(!mobileStatsCaller.doStep()) return;
			}

			// If it ever reaches here. We want to make sure that runing variable is false.
			running = true;
		} catch(Exception e) {
			Log.e(TAG, "ReceiveUDP: Exception", e);
			running = false;
		}
	}

	/**
	 * This method is used receive a single UDP packet from the measurement server.
	 * 
	 * @param receive
	 * @param buffer
	 * @return boolean value indicating whether a packet was received successfully of not.
	 */
	boolean receiveUdpPacket(byte receive[], byte buffer[]) {
		//byte[] temp = new byte[CURRENT_DATA_SIZE];
		DatagramPacket tempPacket = new DatagramPacket(receive, PingClient.CURRENT_DATA_SIZE);

		try	{
			//Log.v(TAG, "receiveUDP : Trying to receive");
			udpSocket.receive(tempPacket);
		}	catch (IOException e)	{
			Log.e(TAG, "receiveUDP : IOException " + e);
			return false;
		}

		//Log.i(TAG, "Got a UDP packet ..");
		if (tempPacket.getData().length > 0) {
			try	{
				//Log.i(TAG, "receiveUDP : Returning packet of length " + tempPacket.getData().length);
				buffer[0] = receive[0];
				buffer[1] = receive[1];

				//constructBasicInfo(temp, 2);
				if(udpSocket == null)	{
					Log.e(TAG, "receiveUDP : udpSocket is null");
				}

				/*
				Log.v(TAG, "cellsig: data at 42:" + buffer[42]);
				Log.v(TAG, "cellorwifi: data at 46:" + buffer[46]);
				Log.v(TAG, "wifisig: data at 47:" + buffer[47]);
				Log.v(TAG, "wifispeed: data at 48:" + buffer[48]);
				 */

				tempPacket = new DatagramPacket(buffer, PingClient.CURRENT_DATA_SIZE);
				udpSocket.send(tempPacket);
			}	catch (IOException e)	{
				Log.e(TAG, "receiveUDP : Couldn't get I/O for the connection...");
			}	catch (NullPointerException e)	{
				Log.e(TAG, "receiveUDP : Null pointer exception");
			}
		}
		return true;
	}
}

/**
 * This class is used to run a thread the receives the TCP based packets from the measurement
 * server during the ping test.
 * 
 * @author Ashish Patro
 *
 */
class ReceiveTCP implements Runnable {
	public static final String TAG = "ReceiveTCP";
	PingClient mobileStatsCaller = null;
	public volatile boolean running = true;

	// Constructor
	public ReceiveTCP(PingClient mobileStats) {
		mobileStatsCaller = mobileStats;
	}

	/**
	 * This thread receives the TCP packets from the server. After the a TCP packet from
	 * the server, it creates the next packet to be sent with the latest information. 
	 * (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	public void run()	{
		byte temp[] = new byte[512];
		byte receive[] = new byte[512];

		try {
			//Log.v(TAG, "receiveTCP Starting Thread!");
			mobileStatsCaller.constructBasicInfo(temp, 2);


			while (running) {
				//if(!mobileStatsCaller.receiveUDP()) return;

				/**
				 *  We can instantly determine if a connection was established successfully. So, we
				 *  can use this information to schedule a measurement termination in the future
				 *  or terminate the measurements instantly.
				 */

				if (!mobileStatsCaller.receiveTcpPacket(receive, temp)) {
					Log.e(TAG, "receiveTCP doStep returned FALSE!");
					mobileStatsCaller.stopCurrentThread("ReceiveTCP");
					return;
				}

				temp = new byte[512];
				receive = new byte[512];
				mobileStatsCaller.constructBasicInfo(temp, 2);
				//Log.v(TAG, "constructBasicInfo receiveTCP second!");
			}

			// If it ever reaches here. We want to make sure that runing variable is false.
			running = true;
		} catch (Exception e) {
			Log.e(TAG, "receivedTCP: Exception", e);
			running = false;
		}
	}
}

/**
 * This class is used to determine the signal strength for the cellular connection.
 *  
 * @author Ashish Patro
 */
class MyPhoneStateListener extends PhoneStateListener{
	private int sigStrength;

	public MyPhoneStateListener() {
		sigStrength = 0;
	}

	@Override
	public void onSignalStrengthChanged(int asu) {
		//sigStrength = (-113 + (2*asu));
		sigStrength = asu;
		//Log.e(TAG, sigStrength + " dbm");
	}

	public int getSigStrength()	{
		return sigStrength;
	}
}