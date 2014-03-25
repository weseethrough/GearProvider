/**============================== FILE HEADER ==================================
*
*           Copyright (c) 2013 Samsung R&D Institute India- Bangalore Pvt. Ltd (SRI-B)
*                      Samsung Confidential Proprietary
*                            All Rights Reserved
*          This software is the confidential and proprietary information 
*          of Samsung R&D Institute India- Bangalore Pvt. Ltd (SRI-B).
*
*         You shall not disclose such Confidential Information and shall use
*		  it only in accordance with the terms of the license agreement you entered 
*		  into with Samsung R&D Institute India- Bangalore Pvt. Ltd (SRI-B).
================================================================================
*                               Module Name :Gallery Provider B App
================================================================================
* File                : SASmartViewProviderImpl.java
*
* Author(s)           : Amit Singh
*
* Date                : 12 December 2013
*
* Description         : This file handles the backend  service of the  provider  and  extends the  SAAgent class
================================================================================
*                            Modification History
*-------------------------------------------------------------------------------
*    Date    |       Name      |        Modification
*-------------------------------------------------------------------------------
*            |                 |
==============================================================================*/

package com.raceyourself.android.samsung;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.json.JSONException;
import org.json.JSONObject;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.glassfitgames.glassfitplatform.gpstracker.GPSTracker;
import com.glassfitgames.glassfitplatform.gpstracker.Helper;
import com.glassfitgames.glassfitplatform.gpstracker.SyncHelper;
import com.glassfitgames.glassfitplatform.models.Device;
import com.glassfitgames.glassfitplatform.models.Preference;
import com.raceyourself.samsungprovider.R;
import com.raceyourself.android.samsung.models.GpsPositionData;
import com.raceyourself.android.samsung.models.GpsStatusResp;
import com.raceyourself.android.samsung.models.SAModel;
import com.raceyourself.android.samsung.models.WebLinkReq;
import com.roscopeco.ormdroid.ORMDroidApplication;
import com.samsung.android.sdk.accessory.SAAgent;
import com.samsung.android.sdk.accessory.SAPeerAgent;
import com.samsung.android.sdk.accessory.SASocket;

/**
 * @author s.amit
 *
 */
public class ProviderService extends SAAgent {
	
    public static final String TAG = "RaceYourselfProvider";
    public final int DEFAULT_CHANNEL_ID = 104;
    private final String SERVER_TOKEN = "3hrJfCEZwQbACyUB";
    private static final String EULA_KEY = "EulaAccept";
    private static final String DISCLAIMER_KEY = "DisclaimerAccept";

	private final IBinder mBinder = new LocalBinder();
	private static HashMap<Integer, RaceYourselfSamsungProviderConnection> mConnectionsMap = null;
	private static GPSTracker gpsTracker = null;
	private static GpsDataSender gpsDataSender = null;
	private Timer timer = new Timer();
	
	private boolean registered = false; // have we registered the device with the server yet? Required for inserting stuff into the db.

	/**
	 * @author s.amit
	 *
	 */
	public class LocalBinder extends Binder {
		public ProviderService getService() {
			return ProviderService.this;
		}
	}

	 /**
     * 
     * @param intent
     * @return IBinder
     */
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}
	
	@Override
    public boolean onUnbind(Intent intent) {
        stopTracking();
        return false;
    }
	

    /**
     * Called when the service is started
     */
	@Override
	public void onCreate() {
		super.onCreate();
		ORMDroidApplication.initialize(this);  // init the database
		
		// check EULA
		Boolean eulaAccept = Preference.getBoolean(EULA_KEY);
		if (eulaAccept == null || !eulaAccept.booleanValue()) popupEula();
		else {
			Boolean disclaimerAccept = Preference.getBoolean(DISCLAIMER_KEY);
			if (disclaimerAccept == null || !disclaimerAccept.booleanValue()) popupDisclaimer();
			else {
				if(!Helper.getInstance(this).isBluetoothBonded()) popupBluetoothDialog();
				else {
					ensureDeviceIsRegistered();
				
					if(registered){
						authorize();
					
						trySync();
					}
					
				}
				
			}
			
		}
		// check disclaimer
		
		
		// make sure we have a record for the user
		Helper.getUser();
		
		Log.v(TAG, "service created");
	}
	
	@Override
	public void onLowMemory() {
		Log.e(TAG, "onLowMemory  has been hit better to do  graceful  exit now");
//		Toast.makeText(getBaseContext(), "!!!onLowMemory!!!", Toast.LENGTH_LONG)
//		.show();
		closeConnection();
		super.onLowMemory();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.i(TAG, "Service Stopped.");
	}

	public ProviderService() {
		super(TAG, RaceYourselfSamsungProviderConnection.class);
	}

	public boolean closeConnection() {
	    
	    Log.d(TAG, "closeConnection called");

		if (mConnectionsMap != null) {
			List<Integer> listConnections = new ArrayList<Integer>(
					mConnectionsMap.keySet());
			if (listConnections != null) {
				for (Integer s : listConnections) {
					Log.i(TAG, "KEYS found are" + s);
					mConnectionsMap.get(s).close();
					mConnectionsMap.remove(s);
				}
				
				// if no connections left
				if (mConnectionsMap.isEmpty()) {
				    // stop sending updates
				    Log.d(TAG, "No connections remaining, destroying GPS tracker");
				    stopTracking();
				}
			}
		} else
			Log.e(TAG, "mConnectionsMap is null");

		return true;
	}

    /**
     * 
     * @param uThisConnection
     * @param result
     */
	@Override
	protected void onServiceConnectionResponse(SASocket uThisConnection, int result) {
		if (result == CONNECTION_SUCCESS) {
			if (uThisConnection != null) {
			    
			    RaceYourselfSamsungProviderConnection myConnection = (RaceYourselfSamsungProviderConnection) uThisConnection;
			    Log.d(TAG,"onServiceConnection connectionID = "+myConnection.mConnectionId);
				
				if (mConnectionsMap == null) {
					mConnectionsMap = new HashMap<Integer, RaceYourselfSamsungProviderConnection>();
				}
				myConnection.mConnectionId = (int) (System.currentTimeMillis() & 255);
				mConnectionsMap.put(myConnection.mConnectionId, myConnection);

				// make sure we have a device ID (initially from the server) - req for writes to db
				ensureDeviceIsRegistered();
				
				// init GPS tracker to start searching for position
				ensureGps();
				
			} else
				Log.e(TAG, "SASocket object is null");
		} else
			Log.e(TAG, "onServiceConnectionResponse result error =" + result);
		
	}

    /**
     * 
     * @param connectedPeerId
     * @param channelId
     * @param data
     */
	private void onDataAvailableonChannel(String connectedPeerId,
			long channelId, String data) {

        Log.d(TAG, "Received message on channel " + channelId + " from peer " + connectedPeerId
                + ": " + data);
		
		SAModel response = null;
		
		ensureDeviceIsRegistered();
		
		// decide what to do based on the message
		if (data.contains(SAModel.GPS_STATUS_REQ)) {
		    ensureGps();
		    if (gpsTracker.hasPosition()) {
		        response = new GpsStatusResp(GpsStatusResp.GPS_READY);
		    } else if (gpsTracker.isGpsEnabled()) {
		        response = new GpsStatusResp(GpsStatusResp.GPS_ENABLED);
		    } else {
		        response = new GpsStatusResp(GpsStatusResp.GPS_DISABLED);
		        popupGpsDialog();
		    }
		} else if (data.contains(SAModel.START_TRACKING_REQ)) {
		    startTracking();
		} else if (data.contains(SAModel.STOP_TRACKING_REQ)) {
		    stopTracking();
		} else if (data.contains(SAModel.AUTHENTICATION_REQ)) {
		    // usually called when the user launches the app on gear
		    authorize();
		    trySync();
		    // Popup webview with user/passwd boxes
		    //Intent authenticationIntent = new Intent (this, AuthenticationActivity.class);
		    //authenticationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		    //authenticationIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		    //startActivity(authenticationIntent);
		} else if (data.contains(SAModel.LOG_ANALYTICS)) {
		    Helper.logEvent(data);
		} else if (data.contains(SAModel.WEB_LINK_REQ)) {
		    JSONObject json;
            try {
                json = new JSONObject(data);
                String uri = WebLinkReq.fromJSON(json).getUri();
                launchWebBrowser(uri);
            } catch (JSONException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }

		} else {
			Log.e(TAG, "onDataAvailableonChannel: Unknown request received");
		}
		
		// send the response
		if (response != null) {
		    send(connectedPeerId, response);
		}
	}
	
	private void popupBluetoothDialog() {
		final AlertDialog.Builder builder = new AlertDialog.Builder(this);
		
		builder.setMessage("Your bluetooth is not connected to gear, would you like to connect it?")
			   .setCancelable(false)
			   .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
				
				@Override
				public void onClick(DialogInterface dialog, int which) {
					// TODO Auto-generated method stub
					Intent bluetooth = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
					bluetooth.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(bluetooth);
					
					ensureDeviceIsRegistered();
       				
       				if(registered){
       					authorize();
       					
       					trySync();	
       				}
       				
       				
				}
			})
			.setNegativeButton("No", new DialogInterface.OnClickListener() {
				
				@Override
				public void onClick(final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
					// TODO Auto-generated method stub
					dialog.cancel();
				}
			})
            .setTitle("RaceYourself Gear Edition");
		
		final AlertDialog alert = builder.create();
		alert.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
		alert.show();
	}
	
	private void popupGpsDialog() {
	    final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Your GPS seems to be disabled, do you want to enable it?")
               .setCancelable(false)
               .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                   public void onClick(@SuppressWarnings("unused") final DialogInterface dialog, 
                                       @SuppressWarnings("unused") final int id) {
                	   Intent gps = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                	   gps.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                       startActivity(gps);
                   }
               })
               .setNegativeButton("No", new DialogInterface.OnClickListener() {
                   public void onClick(final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
                        dialog.cancel();
                   }
               })
               .setTitle("RaceYourself Gear Edition");
        final AlertDialog alert = builder.create();
        alert.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        alert.show();
	}
	
	private void popupNetworkDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("RaceYourself needs to connect to the internet to register your device. Please check your connection and press retry.")
               .setCancelable(false)
               .setPositiveButton("Retry", new DialogInterface.OnClickListener() {
                   public void onClick(@SuppressWarnings("unused") final DialogInterface dialog, 
                                       @SuppressWarnings("unused") final int id) {
                       ensureInternet();
                   }
               })
               .setNegativeButton("Quit", new DialogInterface.OnClickListener() {
                   public void onClick(final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
                        ProviderService.this.stopSelf();
                   }
               })
               .setTitle("RaceYourself Gear Edition");
        final AlertDialog alert = builder.create();
        alert.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        alert.show();
    }
	
	private void popupEula() {
	    
	    final String message = new String("End-user license agreement.\n\nBy clicking accept you agree to abide by RaceYourself's terms and conditions of use.\n\nDetails can be found at http://www.raceyourself.com/gear/#eula");
	    //Linkify.addLinks(message, Linkify.WEB_URLS);
	    
	    //final TextView view = new TextView(this);
	    //view.setText(message);
	    //view.setMovementMethod(LinkMovementMethod.getInstance());
	    //view.setOnClickListener(new OnClickListener() {
	    //    public void onClick(View onClick) {                 
	    //        launchWebBrowser("http://www.raceyourself.com/gear/#eula");
	    //    }
	    //});
	    
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
               .setCancelable(true)
               .setPositiveButton("Agree", new DialogInterface.OnClickListener() {
                   public void onClick(@SuppressWarnings("unused") final DialogInterface dialog, 
                                       @SuppressWarnings("unused") final int id) {
                       Preference.setBoolean(EULA_KEY, Boolean.TRUE);
                       Boolean disclaimerAccept = Preference.getBoolean(DISCLAIMER_KEY);
           				if (disclaimerAccept == null || !disclaimerAccept.booleanValue()) popupDisclaimer();
                   }
               })
               /* The following doesn't allow synchronous return to the dialog. Probably need an activity here.
                * TODO: fix this so the link is clickable
                * .setNeutralButton("Details", new DialogInterface.OnClickListener() {
                   public void onClick(final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
                        // link to website
                       launchWebBrowser("http://www.raceyourself.com/gear/#eula");
                   }
               })*/
               .setNegativeButton("Decline", new DialogInterface.OnClickListener() {
                   public void onClick(final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
                        ProviderService.this.stopSelf();
                   }
               })
               .setTitle("RaceYourself Gear Edition");
        final AlertDialog alert = builder.create();
        alert.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        alert.show();
    }
	
	private void popupDisclaimer() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("RaceYourself Gear Edition")
               .setMessage("Disclaimer\n\nBy clicking accept you agree to take full responsibility for you own safety whilst using RaceYourself, and accept that RaceYourself will not be held liable for any personal injory or illness sustained through use of this application.\n\nYou agree to the full RaceYourself disclaimer which can be viewed online at https://www.raceyourself.com/gear/#disclaimer")
               .setCancelable(false)
               .setPositiveButton("Accept", new DialogInterface.OnClickListener() {
                   public void onClick(@SuppressWarnings("unused") final DialogInterface dialog, 
                                       @SuppressWarnings("unused") final int id) {
                        Preference.setBoolean(DISCLAIMER_KEY, Boolean.TRUE);
                        if(!Helper.getInstance(ProviderService.this).isBluetoothBonded()) popupBluetoothDialog();
	       				else {
	       					ensureDeviceIsRegistered();
	       				
	       					if(registered){
	       						authorize();
	       						trySync();
	       					}
	       					
	       				}
                   }
               })
               .setNegativeButton("Decline", new DialogInterface.OnClickListener() {
                   public void onClick(final DialogInterface dialog, @SuppressWarnings("unused") final int id) {
                        ProviderService.this.stopSelf();
                   }
               });
        final AlertDialog alert = builder.create();
        alert.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        alert.show();
    }
	
	private void send(String connectedPeerId, SAModel message) {
	    RaceYourselfSamsungProviderConnection conn = mConnectionsMap.get(Integer.parseInt(connectedPeerId));
        try {
            Log.d(TAG, "Sending message on channel " + DEFAULT_CHANNEL_ID + ": " + message.toJSON().toString());
            conn.send(DEFAULT_CHANNEL_ID, message.toJSON().toString().getBytes());
        } catch (IllegalArgumentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (JSONException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
	}
	
	private void ensureDeviceIsRegistered() {
	    if (registered) {
	        // registered, and we know about it locally
	        return;
	    } else if (Device.self() != null) {
	        // registered previously, update local variable
	        registered = true;
	        return;
	    } else {
	        // not yet registered, attempt to register
            ensureInternet();
            
            Thread deviceRegistration = new Thread(new Runnable() {
                @Override
                public void run() {
                        
                    try {
                    	Device self = SyncHelper.registerDevice();
                        self.self = true;
                        self.save();
                        registered = true;
                        
                        authorize();
       					
       					trySync();
       					
                    	} catch (IOException e) {
                    		Log.e(TAG, "Error registering device");
                    }
                }
            });
            deviceRegistration.start(); 
        }
	}
	
	private void ensureGps() {
	    // start listening for GPS updates
	    Log.d(TAG,"ensureGps called");
        if (gpsTracker == null) gpsTracker = new GPSTracker(this);
        gpsTracker.setIndoorMode(false);
        gpsTracker.onResume();
	}
	
    private void startTracking() {

        Log.d(TAG,"startTracking called");
        ensureGps();
        gpsTracker.startTracking();
        
        if (gpsDataSender == null) {
            Log.d(TAG, "Starting to send regular GPS data messages");
            gpsDataSender = new GpsDataSender();
            timer.scheduleAtFixedRate(gpsDataSender, 0, 500);
        }
    }

    private void stopTracking() {
	    
	    // stop sending updates
        Log.d(TAG,"stopTracking called");
	    if (gpsDataSender != null) {
            Log.d(TAG, "Stopping regular GPS data messages");
            gpsDataSender.cancel();
            gpsDataSender = null;
        }
	    
	    // stop listening for GPS/sensors
	    if (gpsTracker != null) {
	        gpsTracker.stopTracking();
	        gpsTracker.onPause();
	    }
        trySync();  // need to sync every now and then. End of each race seems reasonable. It runs in a background thread.
	}
	
	private void ensureInternet() {
	    if (!Helper.getInstance(this).hasInternet()) {
            popupNetworkDialog();
        }
	}
	
	private void authorize() {
    	AccountManager mAccountManager = AccountManager.get(this);
    	List<Account> accounts = new ArrayList<Account>();
    	accounts.addAll(Arrays.asList(mAccountManager.getAccountsByType("com.google")));
    	accounts.addAll(Arrays.asList(mAccountManager.getAccountsByType("com.googlemail")));
        String email = null;
        for (Account account : accounts) {
            if (account.name != null && account.name.contains("@")) {
                email = account.name;
                break;
            }
        }
        // Potential fault: Can there be multiple accounts? Do we need to sort or provide a selector?
       
        // hash email so we don't send user's identity to server
        // can't guarantee uniqueness but want very low probability of collisions
        // using SHA-256 means we'd expect a collision on approx. our 1-millionth user
        // TODO: make this more unique before Samsung sell 1m Gear IIs.
        MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(email.getBytes());
            String hash = new String(messageDigest.digest());
            hash = new String(Base64.encode(hash.getBytes(), Base64.DEFAULT)).replace("@", "_").replace("\n", "_");  //base64 encode and substitute @ symbols
            hash += "@hashed.raceyourself.com";  // make it look like an email so it passes server validation
            Helper.login(hash, SERVER_TOKEN);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Implementation of SHA-256 algorithm not found. Authorisation failed. Exiting.");
            throw new RuntimeException();
        }
	}
	
	private void trySync() {
	    if (Helper.getInstance(this).hasInternet()) {
	        authorize();
	        SyncHelper.getInstance(this).start();
	        // if wither of these fail, they dump a stack trace to the log and execution continues
	    }
	    // if no internet, don't bother
	}
	
	private class GpsDataSender extends TimerTask {
        public void run() {
            if (gpsTracker.hasPosition()) {
                Log.d(TAG, "Sending new position over SAP");
                SAModel gpsData = new GpsPositionData(gpsTracker);
                // send to all connected peers
                for (RaceYourselfSamsungProviderConnection c : mConnectionsMap.values()) {
                    send(String.valueOf(c.mConnectionId), gpsData);
                }
            }
        }
    }
	
	
    /**
     * 
     * @param peerAgent
     * @param result
     */
	@Override
	protected void onFindPeerAgentResponse(SAPeerAgent peerAgent, int result) {

		Log.i(TAG,
				"onPeerAgentAvailable: Use this info when you want provider to initiate peer id = "
						+ peerAgent.getPeerId());
		Log.i(TAG,
				"onPeerAgentAvailable: Use this info when you want provider to initiate peer name= "
						+ peerAgent.getAccessory().getName());
	}

    /**
     * 
     * @param error
     * @param errorCode
     */
	@Override
	protected void onError(String error, int errorCode) {
		// TODO Auto-generated method stub
		Log.e(TAG,"ERROR: " + errorCode + ": " + error);
	}
	
	private void launchWebBrowser(String uri) {
	    Intent myIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        myIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(myIntent);
	}
	
	// service connection inner class
	
	 /**
     * 
     * @author amit.s5
     *
     */
	public class RaceYourselfSamsungProviderConnection extends SASocket {

		public static final String TAG = "RYSamsungProviderConnection";
		private int mConnectionId;

	    /**
	     * 
	     */
		public RaceYourselfSamsungProviderConnection() {
			super(RaceYourselfSamsungProviderConnection.class.getName());
		}

	    /**
	     * 
	     * @param channelId
	     * @param data
	     * @return
	     */
		@Override
		public void onReceive(int channelId, byte[] data) {
			Log.i(TAG, "onReceive ENTER channel = " + channelId);
			String strToUpdateUI = new String(data);
			onDataAvailableonChannel(String.valueOf(mConnectionId), channelId, //getRemotePeerId()
					strToUpdateUI);
		}

		//@Override
//		public void onSpaceAvailable(int channelId) {
//			 Log.v(TAG, "onSpaceAvailable: " + channelId);
//		}

	    /**
	     * 
	     * @param channelId
	     * @param errorString
	     * @param error
	     */
		@Override
		public void onError(int channelId, String errorString, int error) {
			Log.e(TAG, "Connection is not alive ERROR: " + errorString + "  "
					+ error);
		}

	    /**
	     * 
	     * @param errorCode
	     */
		@Override
		public void onServiceConnectionLost(int errorCode) {

			Log.e(TAG, "onServiceConectionLost  for peer = "
					+ mConnectionId + "error code =" + errorCode);
			if (mConnectionsMap != null) {
				    mConnectionsMap.remove(mConnectionId);
				    if (mConnectionsMap.isEmpty()) {
	                    // turn off GPS tracker and sensor service
	                    Log.d(TAG, "No connections remaining, destroying GPS tracker");
	                    stopTracking();
	                }

            }

        }

    }
	
}
