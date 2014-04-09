InsightClient
=============

Insight is a measurement toolkit that allows mobile application developers to collect application analytics and understand  various aspects about the usage of their applications. Insight allows application developers to collect a diverse set of statistics from their application: device type information, client sessions, client location, CPU + memory consumption statistics, battery drain statistics, application specific events.

Furthermore, Insight performs light-weight network latency measurements during the application sessions. This is useful for correlating the network performance with the aforementioned data. For example, by tracking in-app purchases made by users and the network quality statistics of sessions in which these purchases were made, developers can infer how user revenue may be impacted by quality across different networks. 

A version of this toolkit was used for the measurements in our CoNEXT 2013 paper: Capturing mobile experience in the wild: a tale of two apps: http://dl.acm.org/citation.cfm?id=2535391

This repository contains the source code for the client-side Insight implementation. Before using the Insight client, developers need to setup the Insight servers to collect and store data from the Insight clients. The server code and setup instructions are available at the following location: https://github.com/patroashish/InsightServers

To use Insight with your application, include the Insight client code as a library (jar file) or copy the source code directly into the application. To package Insight client code as a jar file, export the "com/wisc/insightlib/" directory in the src/ folder. Then, add the following lines of code to the main activity in your Android application:

1. In the onStart() method, add the following 3 lines of code at beginning of the function (after super.onStart()) with specified changes. This code instantiates a new Insight session when the main activity is started.

		super.onStart();
		
		InsightLib.setServerHostname("example.com"); // Update the server hostname with your Insight server's hostname.
		InsightLib.setApplicationCharID("testUser"); // Update with user ID the application specific user ID information.
		InsightLib.startSession(this.getApplicationContext()); // Start the Insight session.
		
		// Other application code.
		
2. In the onStop() method, add the 'InsightLib.endSession()' call at beginning of the function (after super.onStop()). Insight waits for a few seconds and then ends the current session when the main activity is stopped.

		super.onStop();
		
		InsightLib.endSession();  // Insight API call.
		
		// Other application code.

3. In the onDestroy() method, add the 'InsightLib.finish()' call at beginning of the function (before super.onDestroy()). Insight immediately ends the current session before the main activity is destroyed.
		
		InsightLib.finish();

		super.onDestroy(); // Insight API call.
		
		// Other application code.


The following Insight API calls can be used to log application specific events inside any application related activity class. The Insight client code relays this information to the server to be logged for future analytics.

1. Logs an event denoted by the eventID. It increments the counter for the input event (starting at 0).
		
		InsightLib.captureEvent(Integer eventID);
	
2. Logs an event corresponding to the eventID and the value corresponding to the event instance.
     
		InsightLib.captureEventValue(Integer eventID, Double value);
	
3. Logs an event corresponding to the eventID and the value string corresponding to the event instance.
		
		InsightLib.captureEventString(Integer eventID, String value);
  	 
4.. The following pair of calls should be wrapped around a download event (e.g., downloading an image file,   transmitting data to a server etc.). These call record the duration and the bytes transferred during the download event.
  
		// Call the method before the start of the download.
		long downloadId = InsightLib.downloadStarted();

		/*
		 * Perform the download activity.
		*/
			
		// Call the method after the end of the download.
		InsightLib.downloadEnded(downloadId);

Along with the Insight client code, this repository contains a trivial main activity class (InsightLibTester.java) to explain how to use Insight within your application.
