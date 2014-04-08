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

import java.util.HashMap;
import java.util.Vector;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.util.Log;

/**
 * Stores the statistics related to events. 
 * @author Ashish Patro
 */
public class EventStatistics {
	/**
	 * Constants.
	 */
	private static final String TAG = "EventStatistics";
	
	/**
	 * Save the start time of initialization.
	 */
	private long startTime;
	
	/**
	 * Maintains a counter for various events. 
	 */
	private HashMap<Integer, Integer> eventCountMap;

	/**
	 * Maintains a list of various events ang their values.
	 */
	private Vector<Triplet<Integer, Long, Double>> eventValueList;
	
	/**
	 * Maintains a list of various events ang their values.
	 */
	private Vector<Triplet<Integer, Long, String>> eventStringList;
	
	/**
	 * Used for serialized access to the eventCountMap and eventList structures.
	 */
	private final Lock lock = new ReentrantLock();
	
	/**
	 * Initialize the data stuctures for the event statistics.
	 */
	public EventStatistics() {
		startTime = System.currentTimeMillis();
		
		eventCountMap = new HashMap<Integer, Integer>();
		eventValueList = new Vector<Triplet<Integer, Long, Double>>();
		eventStringList = new Vector<Triplet<Integer, Long, String>>();
	}
	
	/**
	 * Reset the event counter.
	 */
	private void resetEventCount() {
		eventCountMap = new HashMap<Integer, Integer>();
	}
	
	/**
	 * Reset the event-value tuple data.
	 */
	private void resetEventValues() {
		eventValueList = new Vector<Triplet<Integer, Long, Double>>();		
	}
	
	/**
	 * Reset the event-string tuple data.
	 */
	private void resetEventString() {
		eventStringList = new Vector<Triplet<Integer, Long, String>>();		
	}
	
	/**
	 * Logs an event denoted by the eventID. It increments the counter for the
	 * input event.
	 * 
	 * @param eventID
	 */
	public void captureEvent(Integer eventID) {
		try {
			lock.lock();
			
			Integer count = eventCountMap.get(eventID);
			
			if (count == null) {
				eventCountMap.put(eventID, 1);
			} else {
				eventCountMap.put(eventID, count + 1);
			}
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while capturing event: ", e);
		}
		
		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in captureEvent: ", e);
		}
	}

	/**
	 * Logs an event corresponding to the eventID and the value corresponding to
	 * the event instance.
	 * 
	 * @param eventID
	 * @param value
	 */
	/**
	 * @param eventID
	 * @param value
	 */
	public void captureEventValue(Integer eventID, Double value) {
		try {
			lock.lock();
			eventValueList.add(new Triplet<Integer, Long, Double>(eventID, (System.currentTimeMillis() - startTime) / 1000,  value));
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while capturing event-value: ", e);
		}

		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in captureEvenValue: ", e);
		}
	}
	
	/**
	 * Logs an event string corresponding to the eventID and the value corresponding to
	 * the event instance.
	 * 
	 * @param eventID
	 * @param value
	 */
	public void captureEventString(Integer eventID, String value) {
		try {
			lock.lock();
			eventStringList.add((new Triplet<Integer, Long, String>(eventID, (System.currentTimeMillis() - startTime) / 1000,  value)));
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while capturing event-value string: ", e);
		}

		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in captureEvenString: ", e);
		}
	}
	
	/**
	 * Returns a formatted string for the event count information collected by Insight.
	 * 
	 * @return formatted event count string.
	 */
	public String getEventCountStatsString() {
		StringBuilder statsString = new StringBuilder();
		
		try {
			lock.lock();
			
			for (Integer event : eventCountMap.keySet()) {
				statsString.append(event + "#" + 
						eventCountMap.get(event) + "@");
			}

			// Clean up here. Reset the event count stats after processing the event.
			resetEventCount();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while generating stats string: ", e);
			statsString = new StringBuilder();
		}
		
		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in getEventStatsString: ", e);
			//statsString = new StringBuilder();
		}
		
		return statsString.toString(); 
	}
	
	/**
	 * Returns a formatted string for the event value tuples collected by Insight.
	 * 
	 * @return formatted event value tuple string.
	 */
	public String getEventValueStatsString() {
		StringBuilder statsString = new StringBuilder();
		
		try {
			lock.lock();
			
			for (int i = 0; i < eventValueList.size(); i++) {
				statsString.append(eventValueList.elementAt(i).first + "#" + 
						eventValueList.elementAt(i).value1 + "#" +
						eventValueList.elementAt(i).value2 + "@");
			}
			
			// Clean up here. Reset the event value stats after processing the event.
			resetEventValues();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while generating stats string: ", e);
			statsString = new StringBuilder();
		}
		
		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in getEventStatsString: ", e);
			//statsString = new StringBuilder();
		}
		
		return statsString.toString(); 
	}
	
	/**
	 * Returns a formatted string for the event string tuples collected by Insight.
	 * 
	 * @return formatted event string tuple string.
	 */
	public String getEventStringStatsString() {
		StringBuilder statsString = new StringBuilder();
		
		try {
			lock.lock();
			
			for (int i = 0; i < eventStringList.size(); i++) {
				statsString.append(eventStringList.elementAt(i).first + "#" + 
						eventStringList.elementAt(i).value1 + "#" +
						eventStringList.elementAt(i).value2 + "@");
			}
			
			// Clean up here. Reset the event value stats after processing the event.
			resetEventString();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while generating stats string: ", e);
			statsString = new StringBuilder();
		}
		
		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in getEventStringStatsString: ", e);
			//statsString = new StringBuilder();
		}
		
		return statsString.toString(); 
	}
}
