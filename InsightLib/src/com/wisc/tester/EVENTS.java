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

/**
 * This enum struct provides an example on how to create events for use for the Insight framework.
 * 
 * @author Ashish Patro
 */
public enum EVENTS {
	CREATE_FLASH_CARD(0),
	STUDYING_FLASH_CARD(1),
	SCORING(2),
	BACK_TO_STUDY(3),
	RECORD_STUDY_SCORE(4),
	STUDY_ACTIVITY_TYPE(5);
	
	private int code;

	private EVENTS (int code) {
		this.code = code;
	}

	public int getCode() { return code; }
}
