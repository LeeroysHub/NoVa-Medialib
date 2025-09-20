// Copyright 2017 LeeroyFlix
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.leeroy.mediaprovider.video;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.preference.PreferenceManager;

import org.leeroy.environment.LeeroyFlixUtils;
import org.leeroy.filecorelibrary.FileUtils;
import org.leeroy.filecorelibrary.ftp.Session;
import org.leeroy.filecorelibrary.sftp.SFTPSession;
import org.leeroy.mediaplayer.filecoreextension.upnp2.UpnpServiceManager;
import org.leeroy.mediaplayer.utils.ShortcutDbAdapter;
import org.leeroy.mediaprovider.LeeroyFlixMediaIntent;
import org.leeroy.mediaprovider.video.VideoStore;
import org.leeroy.mediaprovider.video.VideoStore.MediaColumns;
import org.leeroy.mediascraper.AutoScrapeService;
import org.leeroy.environment.NetworkState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by alexandre on 26/06/15.
 */
public class NetworkAutoRefresh extends BroadcastReceiver implements DefaultLifecycleObserver {

    private static final Logger log = LoggerFactory.getLogger(NetworkAutoRefresh.class);

    private static volatile boolean isForeground = true;
    private static Application mApplication;

    public static final String ACTION_RESCAN_INDEXED_FOLDERS = "org.leeroy.mediaprovider.video.NetworkAutoRefresh";
    public static final String ACTION_FORCE_RESCAN_INDEXED_FOLDERS = "org.leeroy.mediaprovider.video.NetworkAutoRefresh_force";

    private static final String AUTO_RESCAN_ON_APP_RESTART = "auto_rescan_on_app_restart";

    public static final String AUTO_RESCAN_STARTING_TIME_PREF = "auto_rescan_starting_time";
    public static final String AUTO_RESCAN_PERIOD = "auto_rescan_period";
    public static final String AUTO_RESCAN_LAST_SCAN = "auto_rescan_last_scan";
    public static final String AUTO_RESCAN_ERROR = "auto_rescan_error";
    public static final int AUTO_RESCAN_ERROR_UNABLE_TO_REACH_HOST = -1;
    public static final int AUTO_RESCAN_ERROR_NO_WIFI = -2;

    @Override
    public void onReceive(Context context, Intent intent) {

        if(intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            //reset alarm on boot
            int startingTime = PreferenceManager.getDefaultSharedPreferences(context).getInt(AUTO_RESCAN_STARTING_TIME_PREF, -1);
            int periode = PreferenceManager.getDefaultSharedPreferences(context).getInt(AUTO_RESCAN_PERIOD,-1);
            if(startingTime!=-1&&periode>0){
                NetworkScannerUtil.scheduleNewRescan(context,startingTime,periode,false);
            }
            //start rescan if lastscan + period < current time (when has booted after scheduled time)
        }
        else if(intent.getAction().equals(ACTION_RESCAN_INDEXED_FOLDERS)||
                intent.getAction().equals(ACTION_FORCE_RESCAN_INDEXED_FOLDERS)) {
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            /*
                do not scan if auto scan and already scan lately (for example on restart of device) or if already scanning
             */
            if(((pref.getInt(AUTO_RESCAN_PERIOD,0)<=0)
                    &&!intent.getAction().equals(ACTION_FORCE_RESCAN_INDEXED_FOLDERS))
                    || org.leeroy.mediaprovider.video.NetworkScannerServiceVideo.isScannerAlive()
                    ) {
                SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss.SSS");
                Date dt = new Date();
                String S = sdf.format(dt);
                log.debug("onReceive: skipping rescan : {} period = {} is scanning ? {}", S, pref.getInt(AUTO_RESCAN_PERIOD, 0), String.valueOf(org.leeroy.mediaprovider.video.NetworkScannerReceiver.isScannerWorking()));
                return;
            }
            pref.edit().putLong(AUTO_RESCAN_LAST_SCAN, System.currentTimeMillis()).commit();
            log.debug("onReceive: received rescan intent");
            //updating
            Cursor cursor = null;
            List<Uri> toUpdate = new ArrayList<>();
            try {
                cursor = ShortcutDbAdapter.VIDEO.queryAllShortcuts(context);
                if (cursor != null && cursor.moveToFirst()) {
                    int pathKey = cursor.getColumnIndex(ShortcutDbAdapter.KEY_PATH);
                    int rescanKey = cursor.getColumnIndex(ShortcutDbAdapter.KEY_RESCAN);
                    do {
                        Uri uri = Uri.parse(cursor.getString(pathKey));
                        int rescan = cursor.getInt(rescanKey);
                        // if this uri is to be rescan automatically, add it to the list
                        if (rescan == 1) {
                            log.debug("onReceive: add to scan list {}", uri);
                            toUpdate.add(uri);
                        }
                    }
                    while (cursor.moveToNext());
                }
            } catch (Exception e) {
                log.error("onReceive: Error accessing shortcuts database", e);
                // Continue without shortcuts to prevent crash
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            ShortcutDbAdapter.VIDEO.close();
            if(NetworkState.isLocalNetworkConnected(context)) {
                PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(AUTO_RESCAN_ERROR, 0).commit();//reset error
                // Reset network scan counter at the start of a new batch to prevent orphaned counts
                AutoScrapeService.resetNetworkScanCount();
                boolean triggeredScan = false;
                int scanCount = 0;
                for (Uri uri : toUpdate) {
                    log.debug("onReceive: scanning {}", uri);
                    if (shouldSkipScanForInactiveServer(context, uri)) {
                        log.debug("onReceive: skip scan for inactive server {}", uri);
                        continue;
                    }
                    if("upnp".equals(uri.getScheme())){ //start upnp service
                        UpnpServiceManager.startServiceIfNeeded(context);
                    }
                    if("ftp".equalsIgnoreCase(uri.getScheme())||"ftps".equals(uri.getScheme()))
                        Session.getInstance().removeFTPClient(uri);
                    if("sftp".equalsIgnoreCase(uri.getScheme()))
                        SFTPSession.getInstance().removeSession(uri);
                    
                    //I send these off with a delay to stop the blocking and race conditions, until I refactor the app 
                    //This is about the best I can do to get it working. The 2000 time could probably be lowered, but if it aint broke..
                    Handler handler = new Handler(Looper.getMainLooper());
                    handler.postDelayed(() -> {
                        Intent refreshIntent = new Intent(LeeroyFlixMediaIntent.ACTION_VIDEO_SCANNER_SCAN_FILE, uri);
                        refreshIntent.putExtra(NetworkScannerServiceVideo.RECORD_ON_FAIL_PREFERENCE, AUTO_RESCAN_ERROR);
                        refreshIntent.putExtra(NetworkScannerServiceVideo.RECORD_END_OF_SCAN_PREFERENCE, AUTO_RESCAN_LAST_SCAN);
                        refreshIntent.setPackage(LeeroyFlixUtils.getGlobalContext().getPackageName());
                        context.sendBroadcast(refreshIntent);
                    }, (int) 100 + (scanCount * 2000L));
                }
                
                // Increment the network scan counter for each folder
                triggeredScan = true;
                scanCount++;
                //log.debug("onReceive: incremented network scan count for {}", uri);
                AutoScrapeService.incrementNetworkScanCount();
                        
                // Start AutoScrapeService after network scanning to scrape newly found videos
                if (triggeredScan && AutoScrapeService.isEnable(context)) {
                    log.debug("onReceive: starting AutoScrapeService after network scan, total folders: {}", scanCount);
                    AutoScrapeService.startServiceAfterNetworkScan(context);
                }
            }
            else{
                PreferenceManager.getDefaultSharedPreferences(context).edit().putInt(AUTO_RESCAN_ERROR, AUTO_RESCAN_ERROR_NO_WIFI).commit();//reset error
                NetworkScannerServiceVideo.notifyListeners();
            }
            log.debug("onReceive: received rescan intent end");
        }
    }

    private static boolean shouldSkipScanForInactiveServer(Context context, Uri uri) {
        if (uri == null) {
            return false;
        }
        if (!FileUtils.isNetworkShare(uri)) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        int port = uri.getPort();
        StringBuilder whereBuilder = new StringBuilder();
        whereBuilder.append(MediaColumns.DATA).append(" LIKE '").append(scheme).append("://").append(host);
        if (port != -1) {
            whereBuilder.append(":").append(port);
        }
        whereBuilder.append("/%'");
        String selection = whereBuilder.toString();

        ContentResolver cr = context.getContentResolver();
        Cursor c = null;
        boolean hasRow = false;
        boolean active = false;
        try {
            c = cr.query(VideoStore.SmbServer.getContentUri(), new String[]{"_id", VideoStore.SmbServer.SmbServerColumns.ACTIVE}, selection, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    hasRow = true;
                    if (c.getInt(1) == 1) {
                        active = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("shouldSkipScanForInactiveServer: query failed for {}", uri, e);
            return false;
        } finally {
            if (c != null) c.close();
        }
        // If we have no row we cannot decide, so allow scan.
        return hasRow && !active;
    }

    public static void init(Application application) {
        mApplication = application;
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new NetworkAutoRefresh());
    }

    public static void forceRescan(Context context){
        Intent intent = new Intent(context, NetworkAutoRefresh.class);
        intent.setAction(ACTION_FORCE_RESCAN_INDEXED_FOLDERS);
        intent.setPackage(LeeroyFlixUtils.getGlobalContext().getPackageName());
        context.sendBroadcast(intent);
    }

    public static int getLastError(Context context){
        return  PreferenceManager.getDefaultSharedPreferences(context).getInt(AUTO_RESCAN_ERROR, 0);
    }
    public static boolean autoRescanAtStart(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(AUTO_RESCAN_ON_APP_RESTART,false);
    }
    public static void setAutoRescanAtStart(Context context, boolean autoRescanAtStart) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(AUTO_RESCAN_ON_APP_RESTART,autoRescanAtStart).apply();
    }

    public static int getRescanPeriod(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(AUTO_RESCAN_PERIOD, 0);
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        log.debug("onStop: lifecycle foreground");
        isForeground = true;
        if (autoRescanAtStart(mApplication)) {
            forceRescan(mApplication);
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        isForeground = false;
        log.debug("onStop: lifecycle background");
    }
}
