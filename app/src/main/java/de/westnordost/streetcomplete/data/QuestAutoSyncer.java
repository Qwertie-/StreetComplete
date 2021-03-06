package de.westnordost.streetcomplete.data;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.mapzen.android.lost.api.LocationListener;
import com.mapzen.android.lost.api.LocationRequest;
import com.mapzen.android.lost.api.LocationServices;
import com.mapzen.android.lost.api.LostApiClient;

import javax.inject.Inject;

import de.westnordost.osmapi.map.data.LatLon;
import de.westnordost.osmapi.map.data.OsmLatLon;
import de.westnordost.streetcomplete.Prefs;
import de.westnordost.streetcomplete.data.changesets.OpenChangesetsDao;
import de.westnordost.streetcomplete.data.download.MobileDataAutoDownloadStrategy;
import de.westnordost.streetcomplete.data.download.QuestAutoDownloadStrategy;
import de.westnordost.streetcomplete.data.download.WifiAutoDownloadStrategy;
import de.westnordost.streetcomplete.data.osm.upload.ChangesetAutoCloserReceiver;
import de.westnordost.streetcomplete.util.SphericalEarthMath;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;

/** Automatically downloads and uploads new quests around the user's location and uploads quests.
 *
 *  Respects the user preference to only sync on wifi or not sync automatically at all
 * */
public class QuestAutoSyncer implements LocationListener, LostApiClient.ConnectionCallbacks
{
	private static final String TAG_AUTO_DOWNLOAD = "AutoQuestSyncer";

	private final QuestController questController;
	private final MobileDataAutoDownloadStrategy mobileDataDownloadStrategy;
	private final WifiAutoDownloadStrategy wifiDownloadStrategy;
	private final Context context;
	private final SharedPreferences prefs;

	private LostApiClient lostApiClient;
	private LatLon pos;

	private boolean isConnected;
	private boolean isWifi;

	@Inject public QuestAutoSyncer(QuestController questController,
								   MobileDataAutoDownloadStrategy mobileDataDownloadStrategy,
								   WifiAutoDownloadStrategy wifiDownloadStrategy,
								   Context context, SharedPreferences prefs)
	{
		this.questController = questController;
		this.mobileDataDownloadStrategy = mobileDataDownloadStrategy;
		this.wifiDownloadStrategy = wifiDownloadStrategy;
		this.context = context;
		this.prefs = prefs;
		lostApiClient = new LostApiClient.Builder(context).addConnectionCallbacks(this).build();
	}

	public void onStart()
	{
		updateConnectionState();
		context.registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
	}

	public void onStop()
	{
		stopPositionTracking();
		context.unregisterReceiver(connectivityReceiver);
	}

	public void startPositionTracking()
	{
		if(!lostApiClient.isConnected()) lostApiClient.connect();
	}

	public void stopPositionTracking()
	{
		try // TODO remove when https://github.com/mapzen/lost/issues/143 is solved
		{
			if(lostApiClient.isConnected())
			{
				LocationServices.FusedLocationApi.removeLocationUpdates(lostApiClient, this);
				lostApiClient.disconnect();
			}
		} catch(NullPointerException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onConnected() throws SecurityException
	{
		LocationRequest request = LocationRequest.create()
				.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
				.setSmallestDisplacement(500)
				.setInterval(3 * 60 * 1000); // 3 minutes

		LocationServices.FusedLocationApi.requestLocationUpdates(lostApiClient, request, this);
	}

	@Override
	public void onConnectionSuspended() {}

	@Override public void onLocationChanged(Location location)
	{
		LatLon pos = new OsmLatLon(location.getLatitude(), location.getLongitude());
		// TODO remove when https://github.com/mapzen/lost/issues/142 is fixed
		if(this.pos != null)
		{
			if(SphericalEarthMath.distance(pos, this.pos) < 400) return;
		}
		this.pos = pos;
		triggerAutoDownload();
	}

	@Override public void onProviderEnabled(String provider) {}
	@Override public void onProviderDisabled(String provider) {}

	public void triggerAutoDownload()
	{
		if(!isAllowedByPreference()) return;
		if(pos == null) return;
		if(!isConnected) return;
		if(questController.isPriorityDownloadRunning()) return;

		Log.i(TAG_AUTO_DOWNLOAD, "Checking whether to automatically download new quests at "
				+ pos.getLatitude() + "," + pos.getLongitude());

		final QuestAutoDownloadStrategy downloadStrategy = isWifi ? wifiDownloadStrategy : mobileDataDownloadStrategy;

		new Thread(){ @Override public void run() {

			if(!downloadStrategy.mayDownloadHere(pos)) return;

			questController.download(
					downloadStrategy.getDownloadBoundingBox(pos),
					downloadStrategy.getQuestTypeDownloadCount(), false);
		}}.start();
	}

	public void triggerAutoUpload()
	{
		if(!isAllowedByPreference()) return;
		if(!isConnected) return;
		questController.upload();
		triggerDelayedClosingOfChangesets();
	}

	private void triggerDelayedClosingOfChangesets()
	{
		AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		long delayTime = System.currentTimeMillis() + OpenChangesetsDao.CLOSE_CHANGESETS_AFTER_INACTIVITY_OF;
		Intent intent = new Intent(context, ChangesetAutoCloserReceiver.class);
		PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, FLAG_CANCEL_CURRENT);
		alarmManager.set(AlarmManager.RTC_WAKEUP, delayTime, pi);
	}

	private boolean updateConnectionState()
	{
		ConnectivityManager connectivityManager
				= (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo info = connectivityManager.getActiveNetworkInfo();

		boolean newIsConnected = info != null && info.isConnected();
		boolean newIsWifi = newIsConnected && info.getType() == ConnectivityManager.TYPE_WIFI;

		boolean result = newIsConnected != isConnected || newIsWifi != isWifi;
		isConnected = newIsConnected;
		isWifi = newIsWifi;
		return result;
	}

	private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver()
	{
		@Override public void onReceive(Context context, Intent intent)
		{
			boolean connectionStateChanged = updateConnectionState();
			// connecting to i.e. mobile data after being disconnected from wifi -> not interested in that
			boolean isFailover = intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);
			if(!isFailover && connectionStateChanged && isConnected)
			{
				triggerAutoDownload();
				triggerAutoUpload();
			}
		}
	};

	private boolean isAllowedByPreference()
	{
		Prefs.Autosync p = Prefs.Autosync.valueOf(prefs.getString(Prefs.AUTOSYNC,"ON"));
		return  p == Prefs.Autosync.ON || p == Prefs.Autosync.WIFI && isWifi;
	}
}
