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
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.UUID;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * A utility class for obtaining network related information.
 * 
 * @author Ashish Patro
 *
 */
public class Utils {
	/**
	 * Constants
	 */
	public static final String TAG = "Utils";

	/**
	 * Returns the string formatted IP address.
	 * 
	 * @param ipAddress
	 * 
	 * @return string formatted IP address
	 */
	public static String intToIp(int ipAddress) {
		return ( ipAddress & 0xFF) + "." + ((ipAddress >>  8 ) & 0xFF) + "." +
		((ipAddress >> 16 ) & 0xFF) + "." +
		((ipAddress >> 24 ) & 0xFF);
	}

	/**
	 * Returns the local IP addresses for the client.
	 * 
	 * @return List of IP addresses (as a String).
	 */
	public static String getLocalIpAddress() {
		String list = "";
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
					InetAddress inetAddress = enumIpAddr.nextElement();
					if (!inetAddress.isLoopbackAddress()) {
						if (!list.equals("")) {
							list += ", ";
						}
						list +=  inetAddress.getHostAddress().toString();
					}
				}
			}
		} catch (SocketException ex) {
			Log.e(TAG, ex.toString());
		}

		if (list.equals("")) {
			return "NA";
		}

		return list;
	}

	/**
	 * Generates and returns a unique hashed identifier.
	 * 
	 * @param mContext
	 * 
	 * @return unique hashed identifier for the mobile device.
	 */
	public static String getHashDeviceIdString(Context mContext) {
		TelephonyManager tm = (TelephonyManager) mContext.getApplicationContext().getSystemService(Context.TELEPHONY_SERVICE);

		final String tmDevice, tmSerial, androidId;
		tmDevice = "" + tm.getDeviceId();
		tmSerial = "" + tm.getSimSerialNumber();
		androidId = "" + android.provider.Settings.Secure.getString(mContext.getContentResolver(),
				android.provider.Settings.Secure.ANDROID_ID);

		UUID deviceUuid = new UUID(androidId.hashCode(), ((long)tmDevice.hashCode() << 32) | tmSerial.hashCode());
		String id = deviceUuid.toString();

		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] thedigest = md.digest((id + "-5MDaY7-21519ta4-1-210W-7B0F83y-23d6u-70+1KC-2I*").getBytes());

			String temp = "";
			for (int i = 0; i < thedigest.length && i < Constants.HASH_LENGTH; i++) {
				String last = Integer.toHexString(thedigest[i]); 
				if (last.length() == 0) {
					last = "00";
				} else if (last.length() == 1) {
					last = "0" + last;
				}

				temp += last.substring(last.length() - 2);
			}

			return temp;
		} catch (Exception e) {
			Log.e(TAG, "getHashDeviceIdString : " + e.toString());
		}

		return id;
	}

	/**
	 * Returns a per-device unique ID for the current session. Currently using the millisecond timestamp value as the ID.
	 * 
	 * @return session ID.
	 */
	public static long getSessionIdString() {
		java.util.Date date = new java.util.Date();

		return date.getTime();
	}
	
	/**
	 * Find and return the Android UID of the input process string. Used for the older Android platforms.
	 * Newer versions have an inbuilt API call for this purpose. 
	 * 
	 * @param processString - The name of the process, e.g., com.example
	 * 
	 * @return The Android UID of the process String.
	 */
	public static long getUidFromProcessName(String processString) {
		try {
			// Constants denoting the column names of top command's output
			String PID_STR = "PID";
			String UID_STR = "UID";
			String NAME_STR = "Name";

			// Store the index of each column type as well as the total number of columns
			int pid_col = -1;
			int uid_col = -1;
			int name_col = -1;
			int num_columns = -1;
			
			// Store the resource consumption related information for the application 
			int uid_val = -1;

			String text = "";
			Process process = Runtime.getRuntime().exec("/system/bin/top -n 1");
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream()));

			while ((text = reader.readLine()) != null) {
				String str = text.replaceAll("^[\\s]+", "");
				String [] tokens = str.split("\\s+");

				if (str.contains(PID_STR)) {
					//Log.w("Using Process String", str);
					num_columns = tokens.length;

					for (int i = 0; i < tokens.length; i++) {
						if (tokens[i].contains(PID_STR)) {
							pid_col = i;
						} else if (tokens[i].contains(UID_STR)) {
							uid_col = i;
						} else if (tokens[i].contains(NAME_STR)) {
							name_col = i;
						}
					}

					//Log.w(TAG + "Column Info:" + num_columns, pid_col + " " + cpu_col + " " + rss_col + " " + vss_col + " " + thr_col + " " + uid_col + " " + name_col);
				} else if (str.contains(processString) && name_col >= 0 && pid_col >= 0 && (tokens.length == num_columns)) {
					//Log.w(TAG + "Found String: " + total_entries, str);
					//if (pid_col >= 0) {
					//	pid_val = Integer.parseInt(tokens[pid_col]);
					//}

					if (uid_col >= 0) {
						uid_val = android.os.Process.getUidForName(tokens[uid_col]);
					}
				}
			}

			return uid_val;
		} catch (Exception e) {
			Log.e(TAG, "getUidFromProcessName: " + e.getMessage());
			return -1;
		}
	}
	
	/**
	 * Read the number inside the input file name.
	 * 
	 * @param fileName
	 * 
	 * @return number
	 */
	public static long getNumberFromFile(String fileName) {
		try {
			String text = "";
			BufferedReader reader = new BufferedReader(
					new FileReader(fileName));

			long value = -1;
			while ((text = reader.readLine()) != null) {
				value = Long.parseLong(text);
			}
			
			return value;
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());

			return 0;
		}
	}
	
	/**
	 * Returns a integed ID (1, 2 or 3) based on the current network type.
	 * 
	 * @param currentInterfaceInfo
	 * 
	 * @return interface type ID.
	 */
	public static int getCurrentActiveNetworkType(NetworkInfo currentInterfaceInfo) {
		// Get type of the active network.
		if (currentInterfaceInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
			return 3; // active network is the Cellular one.
		} else if (currentInterfaceInfo.getType() == ConnectivityManager.TYPE_WIFI) {
			return 2; // active network is the WiFi one.
		} else {
			return 1; // could not determine network type : problem.
		}
	}
	
	/**
	 * Reverse geo-codes the location co-ordinates to determine the country and related information
	 * for the client.
	 * 
	 * @return True on success, False otherwise.
	 *
	public static boolean reverseGeocodeLocation(LocationState loc) 
	{
		try {
			String getURL = "http://ws.geonames.org/countrySubdivisionJSON?lat="
				+ loc.getLatitude() + "&lng=" + loc.getLongitude() + "&username=demo";

			//Log.v(TAG, getURL);
			DefaultHttpClient httpclient = new DefaultHttpClient();
			HttpGet httpget = new HttpGet(getURL);

			//Log.i(TAG, httpget.toString());
			HttpResponse response = httpclient.execute(httpget);
			BufferedReader in = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
			String line = null;
			String locString = "";

			while((line = in.readLine()) != null) {
				locString += line;
				//Log.d(TAG, line);
			}

			return ProcessLocationJsonString(locString, loc);

		} catch (Exception e) {
			Log.e(TAG, "reverseGeocodeLocation : " + e.toString());
		}
		
		return false;
	}
	
	/**
	 * It parses the JSON string containing the reverse-geocoded location information.
	 * 
	 * @param locJsonString
	 * @return True on success, False otherwise.
	 *
	public static boolean ProcessLocationJsonString(String locJsonString, LocationState loc) {
		boolean success = false;
		try {
			JSONObject json = (JSONObject)new JSONParser().parse(locJsonString);

			loc.setCountryCode(json.get("countryCode").toString());
			loc.setCountryName(json.get("countryName").toString());

			// For a non US region, the country code and name is sufficient for now.
			if (loc.getCountryName() != null && !loc.getCountryName().equals(Constants.NOT_AVAILABLE)
					&& !loc.getCountryName().equals("US")) {
				success = true;
			}

			loc.setAdminCode(json.get("adminCode1").toString());
			loc.setAdminName(json.get("adminName1").toString());

			// For US, rev geocoding is a success if the region is available.
			success = true;
		} catch (Exception e) {
			Log.e(TAG, "ProcessLocationJsonString : " + e.toString());
		}

		return success;
	}
	*/
}

