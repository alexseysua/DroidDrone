/* AUTO-GENERATED FILE.  DO NOT MODIFY.
 *
 * This class was automatically generated by the
 * java mavlink generator tool. It should not be modified by hand.
 */

package com.MAVLink.enums;

/**
 * 
        States of the mission state machine.
        Note that these states are independent of whether the mission is in a mode that can execute mission items or not (is suspended).
        They may not all be relevant on all vehicles.
      
 */
public class MISSION_STATE {
   public static final int MISSION_STATE_UNKNOWN = 0; /* The mission status reporting is not supported. | */
   public static final int MISSION_STATE_NO_MISSION = 1; /* No mission on the vehicle. | */
   public static final int MISSION_STATE_NOT_STARTED = 2; /* Mission has not started. This is the case after a mission has uploaded but not yet started executing. | */
   public static final int MISSION_STATE_ACTIVE = 3; /* Mission is active, and will execute mission items when in auto mode. | */
   public static final int MISSION_STATE_PAUSED = 4; /* Mission is paused when in auto mode. | */
   public static final int MISSION_STATE_COMPLETE = 5; /* Mission has executed all mission items. | */
   public static final int MISSION_STATE_ENUM_END = 6; /*  | */
}