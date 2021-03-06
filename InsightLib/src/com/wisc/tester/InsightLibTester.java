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

package com.wisc.tester;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.net.URLConnection;

import com.wisc.insightlib.InsightLib;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

/**
 * This is a trivial activity class that explains the usage of Insight API calls. 
 * To use Insight with your android application, you should include the Insight API calls 
 * in the same manner as shown in this activity.
 * 
 * The following example is for a study application which has various studying related activities: creating
 * flash cards, reading a subject such as Geography, taking a quiz etc.
 * 
 * More details are available at: https://github.com/patroashish/InsightClient
 * 
 * @author Ashish Patro
 */
public class InsightLibTester extends Activity {
	
	private static final String TAG = "InsightLibTester";
	/**
	 *  Maintain State about the variable. 
	 */
	public static String state = null;
	
	TextView test = null;
	Button testEvent = null;
	Button testDownload = null;
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        test = (TextView) findViewById(R.id.testStatus);
        testEvent = (Button) findViewById(R.id.testEventCalls);
        testDownload = (Button) findViewById(R.id.testDownloadCalls);
        
    	testEvent.setOnClickListener(new OnClickListener() {

    		public void onClick(View v) {
    			// Testing all the Insight API calls for logging application specific events.
    			Log.i(TAG, "Logging test events using Insight..");
    			
    			// Example usage of an Insight call to record a flash card creation event. 
    			captureFlashCardCreationEvent();
    			
    			// Example usage of an Insight call to record the user studying Geography within the application.
    			recordStudyTypeEvent("Geography");
    			
    			// Example usage of an Insight call to record the score during a quiz.
    			recordStudyScoreEvent(100.0); // Probably a very good geography student :P
    		}
    	});
    	
    	testDownload.setOnClickListener(new OnClickListener() {

    		public void onClick(View v) {
    			// Testing all the Insight API calls for logging download events
    			Log.i(TAG, "Logging test download events using Insight..");
    			
    			// Example usage of an Insight call to record the download activity of an image file.
    			downloadApiExample();
    		}
    	});
    }
    
    @Override
	protected void onStart() {
		super.onStart();
		
		Log.w(TAG, "onStart called...");
		
		/**
		 * Insight API calls.
		 */
		InsightLib.setServerHostname("insightserver.example"); // Replace this with your Insight server address.
		InsightLib.setApplicationCharID("testUser"); // Optional: You can use this API to record you application specific username.
		
		// Marks the start of Insight session.
		InsightLib.startSession(this.getApplicationContext());
		
		/**
		 * Intialize the application state.
		 */
		try {
			state = (String) getLastNonConfigurationInstance();

			if (state == null) {
				// TODO: Uncomment for Insight
				/*
				Log.w(TAG , "OnCreate Null");
				InsightLib.startSession(this.getApplicationContext());
				state = "Started";
				*/
			} else {
				Log.w(TAG , "OnCreate Old One");
			}
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());
		}
	}
    
    @Override
	public Object onRetainNonConfigurationInstance() {
		Log.i(TAG , "onRetainNonConfigurationInstance - Returning App State");
		return state;
	}

	@Override
	protected void onStop() {
		super.onStop();
		Log.w(TAG, "onStop called...");
		
		/*
		 * Insight API call.
		 */
		// Waits for Constants.SESSION_END_WAIT seconds in the background and then ends Insight session.
		InsightLib.endSession();
	}
	
	@Override
	protected void onDestroy() {
		/*
		 * Insight API call.
		 */
		// Immediately ends the session before the application gets destroyed.
		// IMPORTANT: This call should be placed before super.onDestroy().
		InsightLib.finish();
		
		super.onDestroy();
		Log.w(TAG, "onDestroy called...");
	}
	
	// Event example.
	public void captureFlashCardCreationEvent() {
		// Capture the create flash card event. This method call increases the counter for this event by 1.
		InsightLib.captureEvent(EVENTS.CREATE_FLASH_CARD.getCode());
	}
	
	// Event-value pair example. The API allow the developer to store key-value tuples
	// related to various events during the sessions.
	public void recordStudyScoreEvent(double score) {
		// Capture the 'record score' event. This method store the event-value tuple with Insight.
		// Example a study score of 87.0
		InsightLib.captureEventValue(EVENTS.RECORD_STUDY_SCORE.getCode(), score);
	}
	
	// Event-string pair example. The API allow the developer to store key-value (string) tuples
	// related to various events during the sessions.
	public void recordStudyTypeEvent(String type) {
		// Capture the 'record study type' event. This method registers the event-string tuple with Insight.
		// Example a study score of 87.0
		InsightLib.captureEventString(EVENTS.STUDY_ACTIVITY_TYPE.getCode(), type);
	}
		
	// Example for the Insight.downloadStarted() and Insight.downloadEnded() API.
	// This API captures the size of the downloads as well the duration. 
	public void downloadApiExample() {
		try {
			// Call the method before the start of the download.
			long downloadId = InsightLib.downloadStarted();
			
			URL url = new URL("http://www.mapsofworld.com/india/maps/india-map.gif");
			URLConnection conn = url.openConnection();
			conn.setDoOutput(true);
			
			OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream());
			wr.write("");
			wr.flush();

			BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			String line;
			String response = "";

			while ((line = rd.readLine()) != null) {
				response += line;
			}

			wr.close();
			rd.close();
			
			//Log.i(TAG, "Response: " + response);
			
			// Call the method after the end of the download.
			InsightLib.downloadEnded(downloadId);
			
		} catch (Exception e) {
			Log.i(TAG, "Exception while downloading data...");
		}
	}
}
