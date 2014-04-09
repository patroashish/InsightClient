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
 * This file contains two classes
 * 
 * - LocationState
 * - ObtainLocation 
 */

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.util.Iterator;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import com.wisc.insightlib.json.JSONObject;
import com.wisc.insightlib.json.JSONArray;
import com.wisc.insightlib.json.parser.JSONParser;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 
 * This is the main class that stores the location related State for the application. It finds out
 * the best effort location for the client from various location providers and tries to reverse
 * geo-code this location. It also contains the logic to send this information back to the
 * measurement server.
 * 
 * @author Ashish Patro
 */
public class LocationState {
	/**
	 * Constants
	 */
	private static final String TAG = "LocationState";
	private final long MIN_TIME = 1000;
	
	/**
	 * The application context.
	 */
	private Context context;

	/**
	 * Variables storing the location information
	 */
	private Location loc = null;
	private String countryCode = Constants.NOT_AVAILABLE;
	private String adminCode =  Constants.NOT_AVAILABLE;
	private String countryName =  Constants.NOT_AVAILABLE;
	private String adminName =  Constants.NOT_AVAILABLE;
	private String debugString =  Constants.NOT_AVAILABLE;

	/**
	 * Informs whether the location and the reverse geo-coded information is
	 * available or not.
	 */
	private boolean isRevGeocoded;

	/**
	 *  Used for synchronization and preventing race conditions when
	 *  multiple threads try to obtain/update location related information.
	 */
	private boolean isDoingRevGeocoding;

	/**
	 * Contains an instance of the locationManage.
	 */
	private LocationManager locationManager;

	/**
	 * A LocationListener instance for each location provider type. 
	 */
	private ObtainLocation gpsListener = null, networkListener = null;

	/**
	 * The constructor just initializes the context.
	 * 
	 */
	public LocationState(Context context) {
			this.context = context;
	}

	/**
	 * Start the process of obtaining the best effort location and reverse geocoding it.
	 * This methods starts listening to location updates from each of the candidate location
	 * providers such (GPS, network).
	 */
	public void initLocationState() {
		this.isRevGeocoded = false;
		this.isDoingRevGeocoding = false;
		this.loc = null;

		locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		
		try {
			boolean gps_enabled = false, network_enabled = false;
			try {
				gps_enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
			} catch(Exception ex){
				//Log.e(TAG, "gps exception : " + ex.toString());
			}

			try{
				network_enabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
			} catch(Exception ex) {
				//Log.e(TAG, "network exception : " + ex.toString());
			}

			//Log.e(TAG, "requestLocation status : " + gps_enabled + " " + network_enabled);
			if (gps_enabled) {
				gpsListener = new ObtainLocation(this);
				locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
						MIN_TIME, 0, gpsListener);
				//Log.i(TAG, "initLocationState: " + "gps");
			}
			
			if (network_enabled) {
				networkListener = new ObtainLocation(this);
				locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
						MIN_TIME, 0, networkListener);
				//Log.i(TAG, "initLocationState: " + "network");
			}
		} catch (Exception e){
			Log.e(TAG, "initLocationState: " + e.toString());
		}
	}

	/**
	 * Reverse geo-codes the location co-ordinates to determine the country and related information
	 * for the client.
	 * 
	 * @return True on success, False otherwise.
	 */
	public boolean reverseGeocodeLocation() {
		try {
			if (loc == null) {
				// Log.w(TAG, "Couldn't RevGeo No Location....");
				return false;
			}

			if (isDoingRevGeocoding) {
				// Log.w(TAG, "Already performing reverse geocoding");
				return false;
			}

			isDoingRevGeocoding = true;

			//String getURL = "http://maps.google.com/maps/geo?ll=" + loc.getLatitude() + "," + loc.getLongitude();
			//String getURL = "https://maps.googleapis.com/maps/api/geocode/json?latlng=" + loc.getLatitude() + "," + loc.getLongitude() + 
			//		"&sensor=true&key=AIzaSyDaHSD3DRzm9xzaFoIQIl865KTHZCbKQb4";
			String getURL = "http://api.geonames.org/countrySubdivisionJSON?lat=" + loc.getLatitude() + "&lng=" + loc.getLongitude() + "&username=patroashish";
					
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

			isRevGeocoded = ProcessLocationJsonString(locString);
			isDoingRevGeocoding = false;

			stopListening("reverseGeocodeLocation done!");

			return isRevGeocoded;			
		} catch (Exception e) {
			Log.e(TAG, "reverseGeocodeLocation : " + e.toString());
		}
		//Log.w(TAG, "revGeo : Rev geo failed");
		
		isDoingRevGeocoding = false;
		isRevGeocoded = false;
		return isRevGeocoded;
	}

	/**
	 * It parses the JSON string containing the reverse-geocoded location information.
	 * 
	 * @param locJsonString
	 * @return True on success, False otherwise.
	 */
	public boolean ProcessLocationJsonString(String locJsonString) {
		boolean success = false;
		
		try {
			//Log.i(TAG, locJsonString);
			
			JSONObject json = (JSONObject)new JSONParser().parse(locJsonString);

			countryName = json.get("countryName").toString();
			countryCode = json.get("countryCode").toString();
			
			// For a non US region, the country code and name is sufficient for now.
			if (countryName != null && !countryName.equals(Constants.NOT_AVAILABLE)) { // && !countryName.equals("US")) {
				success = true;
			}
			
			adminCode = json.get("adminName1").toString();
			adminName = json.get("adminCode1").toString();;

		} catch (Exception e) {
			Log.e(TAG, "ProcessLocationJsonString : " + e.toString());
		}

		return success;
	}
	
	/**
	 * It parses the JSON string containing the reverse-geocoded location information from the Google API.
	 * 
	 * @param locJsonString
	 * @return True on success, False otherwise.
	 */
	public boolean ProcessGoogleAPILocationJsonString(String locJsonString) {
		boolean success = false;
		try {
			Log.e(TAG, locJsonString);
			
			JSONObject json = (JSONObject)new JSONParser().parse(locJsonString);
			JSONArray jsonArray = (JSONArray) json.get("Placemark");
			@SuppressWarnings("unchecked")
			Iterator<JSONObject> iterator = jsonArray.iterator();

			while (iterator.hasNext()) {
				JSONObject temp = iterator.next();
				
				JSONObject jsonCountryInfo = (JSONObject) ((JSONObject) temp.get("AddressDetails")).get("Country");
				countryName = jsonCountryInfo.get("CountryName").toString();
				countryCode = jsonCountryInfo.get("CountryNameCode").toString();
				
				// For a non US region, the country code and name is sufficient for now.
				if (countryName != null && !countryName.equals(Constants.NOT_AVAILABLE)) { // && !countryName.equals("US")) {
					success = true;
				}
				
				try {
					JSONObject jsonAdminAreaInfo = (JSONObject) jsonCountryInfo.get("AdministrativeArea");
					
					adminCode = jsonAdminAreaInfo.get("AdministrativeAreaName").toString();
					adminName = adminCode;
		
					//Log.i(TAG, "Process Loc Result Done " + countryCode + " " +
					//		countryName + " " + adminCode + " " + adminName);
				} catch (Exception e) {
					Log.e(TAG, "ProcessGoogleAPILocationJsonString : " + e.toString());
				}
				break;
			}
			
		} catch (Exception e) {
			Log.e(TAG, "ProcessGoogleAPILocationJsonString : " + e.toString());
		}

		return success;
	}

	/**
	 * This method stops location updates from all the location providers.
	 */
	public void stopListening(String info) {
		// Remove the listener you previously added
		try {
			try {
				if (gpsListener != null) {
					//Log.w(TAG, "Stopping Listening  1>>>>>");
					locationManager.removeUpdates(gpsListener);
					gpsListener = null;
					//Log.w(TAG, "<<<< Stopped Listening 1");
				}
			} catch (Exception e) {
				Log.e(TAG, "stopListenting: Exception while removing gpsListener: " + e.toString());
			}

			try {
				if (networkListener != null) {
					//Log.w(TAG, "Stopping Listening  2>>>>>");
					locationManager.removeUpdates(networkListener);
					networkListener = null;
					//Log.w(TAG, "<<<< Stopped Listening 2");
				}
			} catch (Exception e) {
				Log.e(TAG, "stopListenting: Exception while removing networkListener: " + e.toString());
			}
			
			//Log.e(TAG, "stopListening: due to: " + info);
		} catch (Exception e) {
			Log.e(TAG, "stopListening: main : " + e.toString());
		}
	}
	
	/**
	 * If a valid location update is not yet available, get the best possible last known location. 
	 * Otherwise, if a location update is available but is not reverse geocoded yet, then attempt
	 * to reverse geocode the location to obtain the country, region information.
	 */
	public void findBestEffortLocation() {
		try {
			if (locationManager == null) {
				locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
			}
			
			//Log.w(TAG, "Getting best effor location using GPS");
			if (loc == null) {
				loc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

				if (loc == null) {
					loc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
				}
			}

			if (!isRevGeocoded() && loc != null) {
				reverseGeocodeLocation();
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception while try to find best effort location" + e.getStackTrace());
		}
	}
	
	/**
	 * Set the location to the input value.
	 * 
	 * @param location
	 */
	public void setLoc(Location location) {
		if (isDoingRevGeocoding) {
			return;
		}

		this.loc = location;
	}
	
	/**
	 * Update the state of the reverse geocoding process: true if done, false otherwise.
	 *  
	 * @param isRevGeocoded
	 */
	public void setRevGeocoded(boolean isRevGeocoded) {
		this.isRevGeocoded = isRevGeocoded;
	}

	/**
	 * Get the latitude attribute of the location.
	 * 
	 * @return latitude
	 */
	public double getLatitude() {
		if (loc != null) {
			return loc.getLatitude();
		}
		return 0;
	}

	/**
	 * Return the longitude attribute of the location.
	 * s
	 * @return longitude
	 */
	public double getLongitude() {
		if (loc != null) {
			return loc.getLongitude();
		}
		return 0;
	}

	public LocationManager getLocationManager() {
		return locationManager;
	}

	public Location getLoc() {
		return this.loc;
	}

	public String getDebugString() {
		return this.debugString;
	}

	public String getCountryCode() {
		return countryCode;
	}

	public String getAdminCode() {
		return adminCode;
	}

	public String getCountryName() {
		return countryName;
	}

	public String getAdminName() {
		return adminName;
	}

	public boolean isRevGeocoded() {
		return isRevGeocoded;
	}
}

/**
 * Get location updates from a single location provider and perform operation on the
 * results such as reverse geo-coding the location information and sending the results
 * to the server. An instance of this class is created by the LocationState class for
 * each location provider to get a best effort location using various location
 * providers available.
 * 
 * @author Ashish Patro
 * 
 */
class ObtainLocation implements LocationListener {
	public static final String TAG = "ObtainLocation";

	private LocationState locationState;
	private int revGeoFailureCount = 0;
	private int skipLocationUpdates = 0;

	public ObtainLocation (LocationState locationState) {
		this.locationState = locationState;
	}

	public void onStatusChanged(String provider, int status, Bundle extras) {
		//Log.w(TAG, "Status Changed : " + provider);
	}

	public void onProviderEnabled(String provider) {
		//Log.w(TAG, "Provider Enabled : " + provider);
	}

	public void onProviderDisabled(String provider) {
		//Log.w(TAG, "Provider Disabled : " + provider);
	}

	/**
	 * This method is called if an updated location is available for the client.
	 * (non-Javadoc)
	 * @see android.location.LocationListener#onLocationChanged(android.location.Location)
	 */
	public void onLocationChanged(Location location) {
		
		try {
			if (locationState.getLoc() != null) {// || location == null) {
				boolean revGeoSuccess = false;

				if (!locationState.isRevGeocoded()) {
					revGeoSuccess = locationState.reverseGeocodeLocation();

					if (!revGeoSuccess) {
						revGeoFailureCount++;
					}
					
					if (revGeoFailureCount > 1) {
						locationState.stopListening("revGeoFailureCount > 1");
						return;
					}
				} else {
					revGeoSuccess = true;
				}

				return;
			}

			/*
			 *  Accept the location updated when any one of the following conditions are statisfied.
			 *  - The location accuracy is better than MAX_INACCURACY value, if an accuracy value is present.
			 *  - The location accuracy information is not available.
			 *  - The location updates were skipped for at least 3 times.
			 */
			if ((location.hasAccuracy() && location.getAccuracy() < Constants.MAX_INACCURACY) ||
					!location.hasAccuracy() || skipLocationUpdates > 3) {
				locationState.setLoc(location);
				locationState.reverseGeocodeLocation();
			} else {
				skipLocationUpdates ++;
			}
		} catch (Exception e) {
			Log.e(TAG, "onLocationChanged : " + e.toString());
		}
	}
}

/**
 * Used to manage the location updates by the Library.
 * @author Ashish Patro
 */
class LocationUpdateManager implements Runnable {
	public static final String TAG = "LocationUpdateThread";
	
	private Context mContext;
    private MainStatsManager resourceConsumptionStats;
    
	/**
	 * Location Related State for the application is stored within a LocationState instance.
	 */
    private LocationState locState = null;

	/**
	 * A value of True denotes that atleast one location information has been sent.
	 */
	private boolean firstLocationUpdateSent = false;
	
	/**
	 * Get status of location updates.
	 */
	private boolean isRunning = false;

	public LocationUpdateManager(Context mContext,
			MainStatsManager resourceConsumptionStats) {
		this.mContext = mContext;
		this.resourceConsumptionStats = resourceConsumptionStats;
	}

	public void run() {
		
		try {	
			
			isRunning = true;
			Thread.sleep(Constants.INIT_LOCATION_SLEEP_TIME);
			
			// Initialize the location state object.
			locState = new LocationState(mContext);
			
			while (isRunning) {
				new Thread(new Runnable() {

					public void run() {
						Looper.prepare();
						final Handler mHandler = new Handler();
						mHandler.post(new Runnable() {
							public void run() {

								if (locState != null) {

									if (locState.getLoc() == null) {
										locState.findBestEffortLocation();
									}

									if (locState.getLoc() != null) {

										if (!sendLocationData()) {
											resetLocationUpdates(" sendLocationData is false");

											//Log.w(TAG, "Warning.. Couldn't send location " +
											//"data. Stopped Location updates.");
										} else {
											firstLocationUpdateSent = true;
										}
									}

									locState.stopListening(TAG + ".getLocationUpdates");
								}
								
								locState.initLocationState();
								//locState = new LocationState(mContext);
							}
						});
						Looper.loop();
					}
				}).start();

				Thread.sleep(Constants.LOC_UPDATE_FREQUENCY); // * 1 / 4);
			}
		} catch (Exception e) {
			Log.e(TAG, "getLocationUpdates : " + e);
		}
	}
	
	/**
	 * Stop listening to the location updates.
	 * 
	 * @param message - Debug message for the method call.
	 */
	public void resetLocationUpdates(String message) {
		//Log.e(TAG, "resetLocationUpdates called due to: " + info);
		isRunning = false;

		try {
			if (locState != null) {
				locState.stopListening("Insight.resetLocationUpdates");
				//locState = null;
			}
		} catch (Exception e) {
			Log.e(TAG, "Exception while resetting location state: " +
					e.toString());
		}
	}
	
	/**
	 * Sends the data such as location co-ordinates, reverse-geocoded location to the
	 * measurement server. For privacy purposes, send only a rounded off value of the 
	 * actual location coordinates.
	 * 
	 * @return True, if Operation successful, otherwise return false
	 */
	public boolean sendLocationData() {
		boolean success = false;
		String joinStr = "@";
		DecimalFormat df = new DecimalFormat("###.#");
		 
		try {
			String coordinateString = Constants.HIDE_ACTUAL_LOCATION ?  
					 df.format(locState.getLatitude()) + joinStr + df.format(locState.getLongitude()) :
					locState.getLatitude() + joinStr + locState.getLongitude();

			String locationInfo = coordinateString + joinStr + locState.getCountryCode() + joinStr + locState.getAdminName();

			//Log.i(TAG, "Sending location data : " + locationInfo);
			success = resourceConsumptionStats.sendLocationMessage(locationInfo);
		} catch (Exception e) {
			Log.e(TAG, "sendRemainingData : " + e.toString());
		}

		return success;
	}

	public boolean isFirstLocationUpdateSent() {
		return firstLocationUpdateSent;
	}
	
	public LocationState getLocState() {
		return locState;
	}
}
