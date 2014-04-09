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
 * This single class is used to maintain the various constants used by the application.
 * 
 * @author Ashish Patro
 */
public class Constants {
	/**
	 * Local events. // Start with 99
	 */
	public static int EVENT_START_TX_TOTAL_BYTES = 9901;
	public static int EVENT_START_TX_APP_BYTES = 9902;
	public static int EVENT_START_TX_MOBILE_BYTES = 9903;
	
	public static int EVENT_START_RX_TOTAL_BYTES = 9904;
	public static int EVENT_START_RX_APP_BYTES = 9905;
	public static int EVENT_START_RX_MOBILE_BYTES = 9906;
	
	public static int EVENT_UPTIME = 9907;
	public static int EVENT_ELAPSED_REALTIME = 9908;
	
	/**
	 * Debug related variables.
	 */
	public static boolean DEBUG_SET = true;

	/**
	 * Version related information. Add version specific information in this comment.
	 */
	public static final double RELEASE_VERSION = 1.0;
	
	/**
	 * Application specific id. A unique id is generated per application request.
	 * Application ID = 0.
	 * Application Name = StudyBlue.
	 */
	public static final int APPLICATION_ID = 0;
	
	/**
	 * Platform related constants.
	 */
	public static final byte PLATFORM_ANDROID = 1;
	
	/**
	 *  Security Related constants.
	 */
	public static final int HASH_LENGTH = 32;
	
	/**
	 *  Server, network connection related constants.
	 */
	public static final int SERVER_PORT_PING = 11200;
	public static final int SERVER_PORT_RESOURCE = 11210;

	/**
	 * Time Related constants.
	 */
	public static final int SEC = 1000; // 1000 MSEC
	public static final int SOCKET_CONNECT_TIMEOUT = 15 * SEC;
	
	/**
	 * Timer related constants for the Ping Test.
	 */
	public static final int PING_INTERVAL = 0 * SEC; // 0 means don't repeat.
	public static final int TIMER_START_DELAY = 1 * SEC;  // Start the ping thread after this delay.
	
	/**
	 * ResourceConsumptionStats related constants.
	 */
	public static final int RESOURCE_INTERVAL = 40 * SEC;
	public static final int INIT_SLEEP_TIME = 5 * SEC;
	
	/**
	 * Location Related Constants.
	 * 
	 * Note : Default Country Information (Always will be US)
	 */
	public static final boolean HIDE_ACTUAL_LOCATION = true;
	public static final int INIT_LOCATION_SLEEP_TIME = 30 * SEC;
	public static int LOC_UPDATE_FREQUENCY = 360 * SEC;
	public static final String NOT_AVAILABLE = "NaV"; 
	public static final double MAX_INACCURACY = 300.0;

	/**
	 * Constants related to the periodic stats reporting.
	 */
	public static final int CURRENT_STATS_UPDATE_FREQUENCY = 300 * SEC;
	
	/**
	 * Session end hysterisis value.
	 */
	public static final int SESSION_END_WAIT = 10 * SEC;
	
	/**
	 * Packet Sizes
	 */
	public static int PACKET_SIZE = 512;
}
