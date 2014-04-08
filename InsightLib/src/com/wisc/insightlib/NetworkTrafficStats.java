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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.os.SystemClock;
import android.util.Log;

/**
 * Stores the statistics related to network data transfers. 
 * @author Ashish Patro
 */
public class NetworkTrafficStats {
	/**
	 * Constants.
	 */
	private static String TAG = "NetworkTrafficStats";
	
	/**
	 * Save the start time of initialization.
	 */
	private long startTime;
	
	/**
	 * Stores the uid of the monitored process.
	 */
	private long packageUid;
	
	/**
	 * Maintains a map for data transfer vs time taken. 
	 */
	private Vector<Triplet<Pair<Long, Long>, Long, Long>> downloadTransferList;
	
	/**
	 * Used for serialized access to downloadTransferList;
	 */
	private final Lock lock = new ReentrantLock();
	
	/**
	 * Maintains information about the currently running measurement.
	 */
	//private boolean isMeasurementRunning;

	//private long currMeasurmentTxBytes;
	//private long currMeasurmentRxBytes;
	//private long currMeasurmentTimestamp;
	
	/**
	 * Can we use Uid related stats. 
	 */
	private boolean isUidStatsAvailable = true;
	private boolean isInitalEventCaptured = false;

	/**
	 * Variables for maintaining download state.
	 */
	Random randomGen = new Random();
	HashMap<Long, DownloadState> downloadStates = 
		new HashMap<Long, NetworkTrafficStats.DownloadState>();
	
	/**
	 * Stores the statistics about TCP data transferred and received.
	 */
	private long totalTxBytes, totalRxBytes, mobileTxBytes, mobileRxBytes, appTxBytes, appRxBytes;
	
	public NetworkTrafficStats(long packageUid) {
		this.packageUid = packageUid;
		
		startTime = System.currentTimeMillis();
		
		//TODO: Removed
		/*
		isMeasurementRunning = false;
		currMeasurmentTxBytes = 0;
		currMeasurmentRxBytes = 0;
		*/
		downloadTransferList = new Vector<Triplet<Pair<Long, Long>, Long, Long>>();
		
		totalTxBytes = readTotalTxBytes();
		totalRxBytes = readTotalRxBytes();
		mobileTxBytes = readMobileTxBytes();
		mobileRxBytes = readMobileRxBytes();
		appTxBytes = readUidTxBytes(packageUid);
		appRxBytes = readUidRxBytes(packageUid);
		
		if (appTxBytes <= 0 || appRxBytes <= 0) {
			isUidStatsAvailable = false;
			appTxBytes = readTotalTxBytes();
			appRxBytes = readTotalRxBytes();
			
			Log.d(TAG, "Not using uid...");
		}
		
		// TODO: Currently forced to keep the non-network code here. Refactor later.
		// Capture event at the start of the session
		if (!isInitalEventCaptured) {
			isInitalEventCaptured = true;
			InsightLib.captureEventValue(Constants.EVENT_START_TX_TOTAL_BYTES, (double) totalTxBytes);
			InsightLib.captureEventValue(Constants.EVENT_START_TX_APP_BYTES, (double) mobileTxBytes);
			InsightLib.captureEventValue(Constants.EVENT_START_TX_MOBILE_BYTES, (double) appTxBytes);
					
			InsightLib.captureEventValue(Constants.EVENT_START_RX_TOTAL_BYTES, (double) totalRxBytes);
			InsightLib.captureEventValue(Constants.EVENT_START_RX_APP_BYTES, (double) mobileRxBytes);
			InsightLib.captureEventValue(Constants.EVENT_START_RX_MOBILE_BYTES, (double) appRxBytes);
			
			InsightLib.captureEventValue(Constants.EVENT_UPTIME, (double) SystemClock.uptimeMillis()/1000);
			InsightLib.captureEventValue(Constants.EVENT_ELAPSED_REALTIME, (double) SystemClock.elapsedRealtime()/1000);
		}
		
		// Test code for traffic stats.
		/*
		Log.w(TAG, "Uid : " + uid + "\n");
		Log.w(TAG, "totalTxBytes : " + totalTxBytes + "\n");
		Log.w(TAG, "totalRxBytes : " + totalRxBytes + "\n");
		Log.w(TAG, "mobileTxBytes : " + mobileTxBytes + "\n");
		Log.w(TAG, "mobileRxBytes : " + mobileRxBytes + "\n");
		Log.w(TAG, "appTxBytes : " + appTxBytes + "\n");
		Log.w(TAG, "appRxBytes : " + appRxBytes + "\n");
		
		Log.w(TAG, "TrafficStats totalTxBytes : " + TrafficStats.getTotalTxBytes() + "\n");
		Log.w(TAG, "TrafficStats totalRxBytes : " + TrafficStats.getTotalRxBytes() + "\n");
		Log.w(TAG, "TrafficStats mobileTxBytes : " + TrafficStats.getMobileTxBytes() + "\n");
		Log.w(TAG, "TrafficStats mobileRxBytes : " + TrafficStats.getMobileRxBytes() + "\n");
		Log.w(TAG, "TrafficStats appTxBytes : " + TrafficStats.getUidTxBytes((int) uid) + "\n");
		Log.w(TAG, "TrafficStats appRxBytes : " + TrafficStats.getUidRxBytes((int) uid) + "\n");
		*/
	}
	
	public void endSession() {
		totalTxBytes = readTotalTxBytes() - totalTxBytes;
		totalRxBytes = readTotalRxBytes() - totalRxBytes;
		mobileTxBytes = readMobileTxBytes() - mobileTxBytes;
		mobileRxBytes = readMobileRxBytes() - mobileRxBytes;
		appTxBytes = readUidTxBytes(packageUid) - appTxBytes;
		appRxBytes = readUidRxBytes(packageUid) - appRxBytes;
		
		if (totalTxBytes < 0) {
			totalTxBytes = 0;
		}
		
		if (totalRxBytes < 0) {
			totalRxBytes = 0;
		}
		
		if (mobileTxBytes < 0) {
			mobileTxBytes = 0;
		}
		
		if (mobileRxBytes < 0) {
			mobileRxBytes = 0;
		}
		
		if (appTxBytes < 0) {
			appTxBytes = 0;
		}
		
		if (appRxBytes < 0) {
			appRxBytes = 0;
		}
	}
	
	/**
	 * Returns a formatted string about the download statistics.
	 * 
	 * @return download stats string.
	 */
	public String getDownloadStatsString() {
		StringBuilder downloadStatsString = new StringBuilder();

		try {
			lock.lock();
			
			for (int i = 0; i < downloadTransferList.size(); i++) {
				downloadStatsString.append(downloadTransferList.elementAt(i).first.first + "#" + 
						downloadTransferList.elementAt(i).first.second + "#" +
						downloadTransferList.elementAt(i).value1 + "#" +
						downloadTransferList.elementAt(i).value2 + "@");
			}
			
			resetDownloadTransferList();
		} catch (Exception ex) {
			Log.e(TAG, "An exception occured while creating the download statistics string: ", ex);
			downloadStatsString = new StringBuilder();
		}
		
		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in getDownloadStatsString: ", e);
		}

		return downloadStatsString.toString();
	}
	
	/**
	 * Returns a formatted string about the aggregate network statistics.
	 * 
	 * @return Network stats string 
	 */
	public String getNetworkStatsString() {
		return totalTxBytes + "@" + totalRxBytes + "@" +
		mobileTxBytes + "@" + mobileRxBytes + "@" + 
		appTxBytes + "@" + appRxBytes;
	}
	
	/**
	 * Used to log the start of a download event (e.g., an image, data, file).
	 * 
	 * @return Returns a unique identifier to identify the current download event.
	 */
	public long downloadStarted() {
		Long currNum = (Math.abs(randomGen.nextLong()) % 10000);
		
		try {
			lock.lock();

			downloadStates.put(currNum, new DownloadState(readUidTxBytes(packageUid), 
				readUidRxBytes(packageUid), System.currentTimeMillis()));
				
			Log.i(TAG, "Download Started.. " + currNum);
		} catch (Exception e) {
			
			Log.e(TAG, "Exception while starting a new download measurement..");
		}
		
		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in downloadStarted: ", e);
		}
		
		Log.i(TAG, "Return Download Started..");
		return currNum;
	}
	
	/**
	 * Used to log the end of the input download event 
	 *
	 * @param downloadId
	 */
	public void downloadEnded(long downloadId) {
		try {

			if (!downloadStates.containsKey(downloadId)) {
				Log.w(TAG, "No download present.. " + downloadId);
				return;
			}
			
			long tmpTimestamp = System.currentTimeMillis();
			long tmpTxBytes = readUidTxBytes(packageUid);
			long tmpRxBytes = readUidRxBytes(packageUid);
			
			lock.lock();
			
			DownloadState downloadState = downloadStates.get(downloadId);
			
			//Log.w(TAG, "" + downloadState.currMeasurmentTimestamp);
			//Log.w(TAG, "" + downloadState.currMeasurmentTxBytes);
			//Log.w(TAG, "" + downloadState.currMeasurmentRxBytes);
			
			if (downloadState.currMeasurmentRxBytes > 0 || downloadState.currMeasurmentTxBytes > 0) {
				downloadTransferList.add(new Triplet<Pair<Long, Long>, Long, Long>
				(new Pair<Long, Long>(tmpTxBytes - downloadState.currMeasurmentTxBytes,
						 tmpRxBytes - downloadState.currMeasurmentRxBytes),
						(tmpTimestamp - startTime) / 1000,
						tmpTimestamp - downloadState.currMeasurmentTimestamp));
			}

			downloadStates.remove(downloadId);

			Log.w(TAG, "Download Ended.. " + downloadId); //: " + currMeasurmentTimestamp);
		} catch (Exception e) {
			Log.e(TAG, "Exception while ending a download measurement..");
		}

		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in downloadEnded: ", e);
		}
	}
	
	
	/**
	 * Reset the download event information. 
	 */
	private void resetDownloadTransferList() {
		try {
			lock.lock();

			downloadTransferList.clear();
			downloadStates.clear();
		} catch (Exception e) {
			Log.e(TAG, "Exception in resetDownloadEnded..");
		}

		try {
			lock.unlock();
		} catch (Exception e) {
			Log.e(TAG, "An exception occured while unlocking in resetDownloadEnded: ", e);
		}	
	}

	/**
	 * Return the current value of the input network attribute
	 * 
	 * @param whatStat - the statistic for which the current value is queried
	 * 
	 * @return the value of the input statistic
	 */
	private static long getTotalStat(String whatStat) {
        File netdir = new File("/sys/class/net");

        File[] nets = netdir.listFiles();
        if (nets == null) {
            return 0;
        }
        
        long total = 0;
        StringBuffer strbuf = new StringBuffer();
        
        for (File net : nets) {
        	// Don't use statistics from the localhost interface.
        	if (net.getPath().endsWith("/lo")) {
        		//Log.i(TAG, "getTotalStat: " + "Skipping " + net.getPath());
        		continue;
        	}
        	//Log.i(TAG, "getTotalStat: " + "Using: " + net.getPath());
        	
            strbuf.append(net.getPath()).append(File.separator).append("statistics")
                    .append(File.separator).append(whatStat);
            
            total += Utils.getNumberFromFile(strbuf.toString());
            strbuf.setLength(0);
        }
        
        return total;
    }
	
	/**
     * Returns the array of two possible File locations for a given
     * statistic.
     */
    private static File[] mobileFiles(String whatStat) {
        // Note that we stat them at runtime to see which is
        // available, rather than here, to guard against the files
        // coming & going later as modules shut down (e.g. airplane
        // mode) and whatnot.  The runtime stat() isn't expensive compared
        // to the previous charset conversion that happened before we
        // were reusing File instances.
        File[] files = new File[2];
        
        files[0] = new File("/sys/class/net/rmnet0/statistics/" + whatStat);
        files[1] = new File("/sys/class/net/ppp0/statistics/" + whatStat);
        
        return files;
    }
    
    /**
     * Take a set of possible input files containing the desired statistics. Return the value within the first valid input file.
     * 
     * @param files
     * 
     * @return The value within the input input files.
     */
    private static long getMobileStat(File[] files) {
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            
            if (!file.exists()) {
                continue;
            }
            
            try {
                new RandomAccessFile(file, "r");
                return Utils.getNumberFromFile(file.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Exception opening TCP statistics file " + file.getAbsolutePath(), e);
            }
        }
        
        return 0L;
    }

    /**
    *  Get total number of transmitted bytes received through rmnet0 or ppp0.
    *
    * @return number of transmitted bytes through rmnet0 or ppp0
    */
    public static long readMobileTxBytes() {
    	return getMobileStat(mobileFiles("tx_bytes"));
    }

   /**
    *  Get total number of received bytes received through rmnet0 or ppp0.
    *
    * @return number of received bytes through rmnet0 or ppp0
    */
    private long readMobileRxBytes() {
    	return getMobileStat(mobileFiles("rx_bytes"));
    }
	
    /**
     *  Get total number of transmitted bytes.
     *
     * @return number of transmitted bytes.
     */
	private long readTotalTxBytes() {
		return getTotalStat("tx_bytes");
	}
	
	/**
     *  Get total number of received bytes.
     *
     * @return number of received bytes.
     */
	private long readTotalRxBytes() {
		return getTotalStat("rx_bytes");
	}
	
	/**
     *  Get total number of transmitted bytes for the uid. The uid is the unique android application identifier.
     *
     * @return number of transmitted bytes for the uid.
     */
	private long readUidTxBytes(long uid) {
		//Log.i(TAG, "readUidTxBytes: Using uid" + uid); 
		if (isUidStatsAvailable) {
			return Utils.getNumberFromFile("/proc/uid_stat/" + uid + "/tcp_snd");
		} else {
			//Log.i(TAG, "tx: UID not available");
			return readTotalTxBytes();
		}
	}
	
	/**
     *  Get total number of received bytes for the uid. The uid is the unique android application identifier.
     *
     * @return number of received bytes for the uid.
     */
	private long readUidRxBytes(long uid) {
		//Log.i(TAG, "readUidRxBytes: Using uid" + uid);
		if (isUidStatsAvailable) {
			return Utils.getNumberFromFile("/proc/uid_stat/" + uid + "/tcp_rcv");
		} else {
			//Log.i(TAG, "rx: UID not available");
			return readTotalRxBytes();
		}
	}
	
	/**
	 * Each object stores the number of bytes transmitted and received during a download event (between the Start and End download events).
	 * 
	 * @author Ashish Patro
	 */
	class DownloadState {
		public long currMeasurmentTxBytes;
		public long currMeasurmentRxBytes;
		public long currMeasurmentTimestamp;
		
		public DownloadState(long currMeasurmentTxBytes, long currMeasurmentRxBytes, 
				long currMeasurmentTimestamp) {
			this.currMeasurmentTxBytes = currMeasurmentTxBytes;
			this.currMeasurmentRxBytes = currMeasurmentRxBytes;
			this.currMeasurmentTimestamp = currMeasurmentTimestamp;
		}
	}
}
