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

package org.leeroy.mediascraper;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import android.os.Looper;
import android.provider.BaseColumns;

import org.leeroy.mediaplayer.utils.trakt.TraktService;
import org.leeroy.medialib.R;
import org.leeroy.mediaprovider.DeleteFileCallback;
import org.leeroy.environment.NetworkState;
import org.leeroy.mediaprovider.video.VideoStore;
import org.leeroy.mediaprovider.video.VideoProvider;
import org.leeroy.mediaprovider.video.WrapperChannelManager;
import org.leeroy.mediascraper.preprocess.SearchInfo;
import org.leeroy.mediascraper.preprocess.SearchPreprocessor;
import org.leeroy.mediascraper.xml.MovieScraper3;
import org.leeroy.mediascraper.xml.ShowScraper4;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Created by alexandre on 20/05/15.
 */
public class AutoScrapeService extends Service {
    public static final String EXPORT_EVERYTHING = "export_everything";
    public static final String RESCAN_EVERYTHING = "rescan_everything";
    public static final String RESCAN_MOVIES = "rescan_movies";
    public static final String RESCAN_COLLECTIONS = "rescan_collections";
    public static final String RESCAN_ONLY_DESC_NOT_FOUND = "rescan_only_desc_not_found";
    private static final int PARAM_NOT_SCRAPED = 0;
    private static final int PARAM_SCRAPED = 1;
    private static final int PARAM_ALL = 2;
    private static final int PARAM_SCRAPED_NOT_FOUND = 3;
    private static final int PARAM_MOVIES = 4;
    private static final Logger log = LoggerFactory.getLogger(AutoScrapeService.class);

    public static final String PREFERENCE_LAST_TIME_VIDEO_SCRAPED_UTC = "last_time_video_scraped_utc";

    // window size used to split queries to db
    private final static int WINDOW_SIZE = 2000;

    private static volatile boolean sIsScraping = false;
    static int sNumberOfFilesRemainingToProcess = 0;
    static int sTotalNumberOfFilesRemainingToProcess = 0;
    static int sNumberOfFilesScraped = 0;
    static int sNumberOfFilesNotScraped = 0;
    public static String KEY_ENABLE_AUTO_SCRAP ="enable_auto_scrap_key";
    private final static String[] SCRAPER_ACTIVITY_COLS = {
            // Columns needed by the activity
            BaseColumns._ID,
            VideoStore.MediaColumns.DATA,
            VideoStore.MediaColumns.TITLE,
            VideoStore.Video.VideoColumns.SCRAPER_MOVIE_ID,
            VideoStore.Video.VideoColumns.SCRAPER_EPISODE_ID,
            VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_TYPE,
            VideoStore.Video.VideoColumns.SCRAPER_VIDEO_ONLINE_ID,
            VideoStore.Video.VideoColumns.SCRAPER_E_SEASON
    };
    private Thread mThread;
    private boolean restartOnNextRound = false;
    private AutoScraperBinder mBinder;
    private Thread mExportingThread;
    private static Handler mHandler = new Handler(Looper.getMainLooper());

    private static Context mContext;
    
    private static PowerManager.WakeLock mWakeLock;

    private static final int NOTIFICATION_ID = 4;
    private NotificationManager nm;
    private NotificationCompat.Builder nb;
    private static final String notifChannelId = "AutoScrapeService_id";
    private static final String notifChannelName = "AutoScrapeService";
    private static final String notifChannelDescr = "AutoScrapeService";

    private static Boolean scrapeOnlyMovies = false;

    private volatile static boolean isForeground = true;
    private static boolean isForceAfterNetworkScan = false;
    private static int networkScanCount = 0;
    private static final Object networkScanLock = new Object();
    private static final String PREF_IS_SCRAPE_DIRTY = "is_scrape_dirty";

    /**
     * Ugly implementation based on a static variable, guessing that there is only one instance at a time (seems to be true...)
     * @return true if AutoScrape service is running
     */
    public static boolean isScraping() {
        return sIsScraping;
    }

    /**
     * Ugly implementation based on a static variable, guessing that there is only one instance at a time (seems to be true...)
     * @return the number of files that are currently in the queue for scraping
     */
    public static int getNumberOfFilesRemainingToProcess() {
        return sTotalNumberOfFilesRemainingToProcess;
    }

    public static void startService(Context context) {
        log.debug("startService in foreground");
        mContext = context.getApplicationContext();
        acquireWakeLock(context);
        Intent intent = new Intent(context, AutoScrapeService.class);
        try {
            // Try to start as foreground service, but fall back if not allowed
            // This can fail when the app is in the background on Android 12+
            ContextCompat.startForegroundService(context, intent);
        } catch (IllegalStateException e) {
            log.warn("startService: Unable to start foreground service, falling back to regular service", e);
            // Fall back to regular startService which is allowed when app is in background
            context.startService(intent);
        }
    }

    public static void startServiceAfterNetworkScan(Context context) {
        log.debug("startServiceAfterNetworkScan - forced start after network scan");
        mContext = context.getApplicationContext();
        Intent intent = new Intent(context, AutoScrapeService.class);
        intent.putExtra("FORCE_AFTER_NETWORK_SCAN", true);
        try {
            // Try to start as foreground service
            // This is called from BroadcastReceiver (NetworkAutoRefresh) which may not have permission
            // in certain conditions (app in background, Android 12+)
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            // If startForegroundService fails (ForegroundServiceStartNotAllowedException or similar),
            // fall back to regular startService which is allowed from BroadcastReceiver
            log.warn("startServiceAfterNetworkScan: Unable to start foreground service ({}), falling back to regular service", e.getClass().getSimpleName());
            try {
                context.startService(intent);
            } catch (Exception fallbackError) {
                log.error("startServiceAfterNetworkScan: Both startForegroundService and startService failed", fallbackError);
            }
        }
    }

    public static void resetNetworkScanCount() {
        synchronized (networkScanLock) {
            if (networkScanCount > 0) {
                log.warn("resetNetworkScanCount: resetting orphaned counter from {} to 0", networkScanCount);
            }
            networkScanCount = 0;
            isForceAfterNetworkScan = false;
        }
    }

    public static void incrementNetworkScanCount() {
        synchronized (networkScanLock) {
            networkScanCount++;
            log.debug("incrementNetworkScanCount: count is now {}", networkScanCount);
        }
    }

    public static void decrementNetworkScanCount() {
        synchronized (networkScanLock) {
            if (networkScanCount > 0) {
                networkScanCount--;
                log.debug("decrementNetworkScanCount: count is now {}", networkScanCount);
                if (networkScanCount == 0) {
                    log.debug("decrementNetworkScanCount: all network scans complete, resetting force flag");
                    isForceAfterNetworkScan = false;
                }
            } else {
                log.debug("decrementNetworkScanCount: count is already 0, this might be a standalone scan");
            }
        }
    }

    public static int getNetworkScanCount() {
        synchronized (networkScanLock) {
            return networkScanCount;
        }
    }
    
    public static void acquireWakeLock(Context context) {
        //Keep screen on for scraping.
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LeeroyFlixScraper::WakeLock");
        mWakeLock.acquire();
    }

    public void cleanup() {
        log.debug("cleanup");
        if (mThread != null && mThread.isAlive()) {
            saveDirtyState(true);
        }
        sIsScraping = false;
        isForeground = false;
        // Note: isForceAfterNetworkScan is now managed by networkScanCount
        // Stop the scraping thread if it's running
        if (mThread != null) {
            mThread.interrupt();
            mThread = null;
        }
        // Stop the exporting thread if it's running
        if (mExportingThread != null) {
            mExportingThread.interrupt();
            mExportingThread = null;
        }
        // Cancel the notification
        nm.cancel(NOTIFICATION_ID);
        stopService();
    }

    // Used by system. Don't call
    public AutoScrapeService() {
        log.debug("AutoScrapeService() {}", this);
    }

    @Override
    public void onCreate() {
        try {
            super.onCreate();
            log.debug("onCreate() {}", this);

            // need to do that early to avoid ANR on Android 26+
            nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel nc = new NotificationChannel(notifChannelId, notifChannelName,
                        nm.IMPORTANCE_LOW);
                nc.setDescription(notifChannelDescr);
                if (nm != null)
                    nm.createNotificationChannel(nc);
            }
            nb = new NotificationCompat.Builder(this, notifChannelId)
                    .setSmallIcon(R.drawable.stat_notify_scraper)
                    .setContentTitle(getString(R.string.scraping_in_progress))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setTicker(null).setOnlyAlertOnce(true).setOngoing(true).setAutoCancel(true);
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, nb.build(),
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
            } catch (Exception e) {
                // Handle ForegroundServiceStartNotAllowedException on Android 12+ when permission is not available
                // The service will still run but without the foreground notification
                log.warn("onCreate: Unable to start foreground service ({}), service will continue without foreground priority", e.getClass().getSimpleName());
            }
            mBinder = new AutoScraperBinder();
        } catch (Throwable t) {
            // Catch any unexpected exceptions during onCreate to prevent service crash
            log.error("onCreate: Unexpected error during service creation", t);
            mBinder = new AutoScraperBinder();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            super.onStartCommand(intent, flags, startId);
            log.debug("onStartCommand");

            // Ensure notification manager and builder are initialized (race condition protection)
            if (nm == null) {
                nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            }
            if (nb == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                    NotificationChannel nc = new NotificationChannel(notifChannelId, notifChannelName,
                            nm.IMPORTANCE_LOW);
                    nc.setDescription(notifChannelDescr);
                    nm.createNotificationChannel(nc);
                }
                nb = new NotificationCompat.Builder(this, notifChannelId)
                        .setSmallIcon(R.drawable.stat_notify_scraper)
                        .setContentTitle(getString(R.string.scraping_in_progress))
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setTicker(null).setOnlyAlertOnce(true).setOngoing(true).setAutoCancel(true);
            }

            // Call startForeground to satisfy the requirement of startForegroundService()
            if (nb != null && nm != null) {
                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, nb.build(),
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
                } catch (Exception e) {
                    // Handle ForegroundServiceStartNotAllowedException on Android 12+ when permission is not available
                    log.warn("onStartCommand: Unable to start foreground service ({}), service will continue without foreground priority", e.getClass().getSimpleName());
                }
            } else {
                log.warn("onStartCommand: Unable to start foreground - notification resources not available");
                // If we can't start foreground, stop the service immediately to avoid crash
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (Throwable t) {
            // Catch any unexpected exceptions during initialization to prevent service crash
            log.error("onStartCommand: Unexpected error during initialization", t);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            // Check for dirty state and restart scraping if needed
            isForeground = true;
            isForceAfterNetworkScan = intent != null && intent.getBooleanExtra("FORCE_AFTER_NETWORK_SCAN", false);
            if (isForceAfterNetworkScan) {
                log.debug("onStartCommand: Force start after network scan - ensuring isForeground = true");
                isForeground = true;
            }
            if (isDirtyState()) {
                log.debug("onStartCommand: Rescanning everything due to dirty state");
                // Reset the dirty flag in SharedPreferences
                saveDirtyState(false);
                startScraping(false, false);
                // START_STICKY: Persistent service that monitors ContentObserver for new videos
                // If killed by system, it will restart and check dirty state to resume interrupted operations
                return START_STICKY;
            }

            if (log.isDebugEnabled() && intent != null && intent.getAction()==null) log.debug("onStartCommand: action is nul!!!");
            if (log.isDebugEnabled() && intent != null && intent.getAction()!=null) log.debug("onStartCommand: action {}", intent.getAction());
            try {
                if(intent!=null) {
                    if(intent.getAction()!=null&&intent.getAction().equals(EXPORT_EVERYTHING)) {
                        log.debug("onStartCommand: EXPORT_EVERYTHING");
                        startExporting();
                    } else if (intent.getAction()!=null&&intent.getAction().equals(RESCAN_MOVIES)) {
                        scrapeOnlyMovies = true;
                        log.debug("onStartCommand: RESCAN_MOVIES, scrapeOnlyMovies={}", scrapeOnlyMovies);
                        startScraping(true, intent.getBooleanExtra(RESCAN_ONLY_DESC_NOT_FOUND, false));
                    } else {
                        log.debug("onStartCommand: RESCAN_EVERYTHING");
                        startScraping(intent.getBooleanExtra(RESCAN_EVERYTHING, false), intent.getBooleanExtra(RESCAN_ONLY_DESC_NOT_FOUND, false));
                    }
                } else {
                    log.debug("onStartCommand: rescan incremental");
                    startScraping(false, false);
                }
            } catch (Exception e) {
                log.error("onStartCommand: Exception in service operation", e);
                // Save dirty state if operation was interrupted
                if (sIsScraping) {
                    saveDirtyState(true);
                }
            }
            // START_STICKY: Persistent service that monitors ContentObserver for new videos
            // If killed by system, it will restart and check dirty state to resume interrupted operations
            return START_STICKY;
        } catch (Throwable t) {
            // Catch any unexpected exceptions in scraping operations to prevent service crash
            log.error("onStartCommand: Unexpected error during scraping operations", t);
            if (sIsScraping) {
                saveDirtyState(true);
            }
            return START_STICKY;
        }
    }

    protected void startExporting() {
        log.debug("startExporting {}", String.valueOf(mExportingThread == null || !mExportingThread.isAlive()));
        nb.setContentTitle(getString(R.string.nfo_export_in_progress)).setWhen(System.currentTimeMillis());
        if (mExportingThread == null || !mExportingThread.isAlive()) {
            mExportingThread = new Thread() {

                public void run() {
                    Cursor cursor = getFileListCursor(PARAM_SCRAPED, null, null, null);
                    final int numberOfRows = cursor.getCount();
                    sTotalNumberOfFilesRemainingToProcess = numberOfRows;
                    cursor.close();
                    log.debug("starting thread {}", numberOfRows);

                    NfoWriter.ExportContext exportContext = new NfoWriter.ExportContext();

                    int index = 0;
                    int window = WINDOW_SIZE;
                    int count = 0;
                    do {
                        if (index + window > numberOfRows)
                            window = numberOfRows - index;
                        log.debug("startExporting: new batch fetching cursor from index{} over window {} entries, {}<={}", index, window, (index + window), numberOfRows);
                        cursor = getFileListCursor(PARAM_SCRAPED, BaseColumns._ID, index, window);
                        log.debug("startExporting: new batch cursor has size {}", cursor.getCount());

                        sNumberOfFilesRemainingToProcess = window;

                        while (cursor.moveToNext() && (isForeground || isForceAfterNetworkScan) && !Thread.currentThread().isInterrupted()
                                && PreferenceManager.getDefaultSharedPreferences(AutoScrapeService.this).getBoolean(AutoScrapeService.KEY_ENABLE_AUTO_SCRAP, true)) {
                            if (sTotalNumberOfFilesRemainingToProcess > 0)
                                nm.notify(NOTIFICATION_ID, nb.setContentText(getString(R.string.remaining_videos_to_process) + " " + sTotalNumberOfFilesRemainingToProcess).build());
                            Uri fileUri = Uri.parse(cursor.getString(cursor.getColumnIndex(VideoStore.MediaColumns.DATA)));
                            long movieID = cursor.getLong(cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_MOVIE_ID));
                            long episodeID = cursor.getLong(cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_EPISODE_ID));
                            final int scraperType = cursor.getInt(cursor.getColumnIndex(VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_TYPE));
                            BaseTags baseTags = null;
                            log.trace("startExporting: {} fileUri {}", movieID, fileUri);
                            if (scraperType == BaseTags.TV_SHOW) {
                                baseTags = TagsFactory.buildEpisodeTags(AutoScrapeService.this, episodeID);
                            } else if (scraperType == BaseTags.MOVIE) {
                                baseTags = TagsFactory.buildMovieTags(AutoScrapeService.this, movieID);
                            }
                            sNumberOfFilesRemainingToProcess--;
                            sTotalNumberOfFilesRemainingToProcess--;
                            if (baseTags == null)
                                continue;
                            log.trace("startExporting: Base tag created, exporting {}", fileUri);
                            if (exportContext != null && fileUri != null)
                                try {
                                    NfoWriter.export(fileUri, baseTags, exportContext);
                                } catch (IOException e) {
                                    log.error("caught IOException: ", e);
                                }
                        }
                        index += window;
                        cursor.close();
                    } while (index < numberOfRows && (isForeground || isForceAfterNetworkScan) && !Thread.currentThread().isInterrupted());
                    sIsScraping = false;
                    // Note: isForceAfterNetworkScan is now managed by networkScanCount
                    // Exit foreground mode and remove notification since export is complete
                    stopForeground(true);
                    cursor.close();
                }
            };
            mExportingThread.start();
        }
    }
    @Override
    public void onDestroy() {
        log.debug("onDestroy() {}", this);
        cleanup();
        super.onDestroy();
    }

    /**
     * Register content observer and start autoscrap if enabled
     * @param context
     */
    public static void registerObserver(Context context) {
        log.debug("registerObserver");
        // Extract application context immediately and don't reference the original context parameter
        // This prevents the ContentObserver from capturing the Activity context
        Context appContext = context.getApplicationContext();
        registerObserverInternal(appContext);
    }

    private static void registerObserverInternal(final Context appContext) {
        appContext.getContentResolver().registerContentObserver(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, false, new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                // Check if auto scraping is enabled and the app is in the foreground (or forced after network scan)
                if (PreferenceManager.getDefaultSharedPreferences(appContext).getBoolean(KEY_ENABLE_AUTO_SCRAP, true) && (isForeground || isForceAfterNetworkScan)) {
                    // Check if a scraping operation is already in progress
                    if (isScraping()) {
                        log.trace("registerObserver.onChange: already scraping, not launching service!");
                        return;
                    }

                    // Look for all the videos not yet processed and not located in the Camera folder
                    String[] selectionArgs = new String[]{ Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getPath() + "/Camera" + "/%" };
                    Cursor cursor = appContext.getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, SCRAPER_ACTIVITY_COLS, WHERE_NOT_SCRAPED, selectionArgs, null);

                    if (cursor != null) {
                        final int cursorGetCount = cursor.getCount();
                        if (cursorGetCount > 0) {
                            log.debug("registerObserver: onChange getting {} videos not yet scraped, launching service.", cursorGetCount);
                            AutoScrapeService.startService(appContext);
                        } else {
                            log.debug("registerObserver: onChange getting {} videos not yet scraped -> not launching service!", cursorGetCount);
                        }
                        cursor.close();
                    }
                }
            }
        });
    }

    public static boolean isEnable(Context context) {
    return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(AutoScrapeService.KEY_ENABLE_AUTO_SCRAP, true);
    }

    public class AutoScraperBinder extends Binder {
        public AutoScrapeService getService(){
            return AutoScrapeService.this;
        }
    }
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    protected void startScraping(final boolean rescrapAlreadySearched, final boolean onlyNotFound) {
        log.debug("startScraping: {}", String.valueOf(mThread == null || !mThread.isAlive()));
        nb.setContentTitle(getString(R.string.scraping_in_progress)).setWhen(System.currentTimeMillis());

        if(mThread==null || !mThread.isAlive()) {
            mThread = new Thread() {

                public int mNetworkOrScrapErrors; //when errors equals to number of files to scrap, stop looping.
                boolean notScraped;
                boolean noScrapeError;
                int totalNumberOfFilesScraped = 0;

                public void run() {
                    sIsScraping = true;
                    boolean shouldRescrapAll = rescrapAlreadySearched;
                    log.debug("startScraping: startThread {}", (mThread==null || !mThread.isAlive()) );
                    if (log.isDebugEnabled()) {
                        if (shouldRescrapAll && scrapeOnlyMovies)
                            log.debug("startScraping: go for all movies");
                        else if (shouldRescrapAll && onlyNotFound)
                            log.debug("startScraping: go for scraped not found");
                        else if (shouldRescrapAll)
                            log.debug("startScraping: go for scrape all");
                        else
                            log.debug("startScraping: go for not scraped");
                        log.debug("startScraping: isLocalNetworkConnected={}, isNetworkConnected={}", NetworkState.isLocalNetworkConnected(AutoScrapeService.this), NetworkState.isNetworkConnected(AutoScrapeService.this));
                        log.debug("startScraping: is AutoScrapeService enabled? {}", isEnable(AutoScrapeService.this));
                    }

                    do {
                        mNetworkOrScrapErrors = 0;
                        sNumberOfFilesScraped = 0;
                        sNumberOfFilesRemainingToProcess = 0;
                        sNumberOfFilesNotScraped = 0;
                        restartOnNextRound = false;
                        // find all videos not scraped yet looking at VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_ID
                        // and get the final count (it could change while scrape is in progress)
                        Cursor cursor = getFileListCursor(shouldRescrapAll&&onlyNotFound ?PARAM_SCRAPED_NOT_FOUND:shouldRescrapAll?PARAM_ALL:PARAM_NOT_SCRAPED, null, null, null);
                        int numberOfRows = cursor.getCount(); // total number of files to be processed
                        sTotalNumberOfFilesRemainingToProcess = numberOfRows;
                        cursor.close();

                        NfoWriter.ExportContext exportContext = null;
                        if (NfoWriter.isNfoAutoExportEnabled(AutoScrapeService.this))
                            exportContext = new NfoWriter.ExportContext();
                        // now process the files to be scraped by batch of WINDOW_SIZE not to exceed the CursorWindow size limit and crash in case of large collection
                        // note that since the db is modified during the scrape process removing non scraped entries fetching WINDOW_SIZE from index 0 is the good strategy
                        int window = WINDOW_SIZE;
                        int numberOfRowsRemaining = numberOfRows;
                        do {
                            if (window > numberOfRowsRemaining)
                                window = numberOfRowsRemaining;
                            //log.debug("startScraping: new batch fetching cursor from index 0, window {} entries <={}", window, numberOfRowsRemaining);
                            cursor = getFileListCursor(shouldRescrapAll && onlyNotFound ? PARAM_SCRAPED_NOT_FOUND :
                                            scrapeOnlyMovies ? PARAM_MOVIES :
                                                shouldRescrapAll ? PARAM_ALL :
                                                        PARAM_NOT_SCRAPED,
                                    BaseColumns._ID, null, window);
                            //log.debug("startScraping: new batch cursor has size {}", cursor.getCount());
                            //log.trace("startScraping: dump cursor {}", DatabaseUtils.dumpCursorToString(cursor));

                            sNumberOfFilesRemainingToProcess = window;
                            restartOnNextRound = true;
                            while (cursor.moveToNext() && isEnable(AutoScrapeService.this) && (isForeground || isForceAfterNetworkScan) && !Thread.currentThread().isInterrupted()) {
                                // stop if disconnected while scraping
                                if (!NetworkState.isLocalNetworkConnected(AutoScrapeService.this) && !NetworkState.isNetworkConnected(AutoScrapeService.this)) {
                                    cursor.close();
                                    sNumberOfFilesRemainingToProcess = 0;
                                    log.debug("startScraping disconnected from network calling stopSelf");
                                    stopSelf();
                                    return;
                                }

                                String title = cursor.getString(cursor.getColumnIndex(VideoStore.MediaColumns.TITLE));
                                Uri fileUri = Uri.parse(cursor.getString(cursor.getColumnIndex(VideoStore.MediaColumns.DATA)));
                                Uri scrapUri = title != null && !title.isEmpty() ? Uri.parse("/" + title + ".mp4") : fileUri;
                                long ID = cursor.getLong(cursor.getColumnIndex(BaseColumns._ID));

                                // for now there is no error and file is not scraped
                                notScraped = true;
                                noScrapeError = true;
                                //log.trace("startScraping processing scrapUri {}, with ID {}, number of remaining files to be processed: {}", scrapUri, ID, sTotalNumberOfFilesRemainingToProcess);
                                if (sTotalNumberOfFilesRemainingToProcess > 0)
                                    nm.notify(NOTIFICATION_ID, nb.setContentText(getString(R.string.remaining_videos_to_process) + " " + sTotalNumberOfFilesRemainingToProcess).build());

                                if (NfoParser.isNetworkNfoParseEnabled(AutoScrapeService.this) && !fileUri.toString().toLowerCase().startsWith("upnp")) {

                                    BaseTags tags = NfoParser.getTagForFile(fileUri, AutoScrapeService.this);
                                    if (tags != null) {
                                        //log.trace("startScraping: found NFO");
                                        // if poster url are in nfo or in folder, download is automatic
                                        // if no poster available, try to scrap with good title,
                                        if (ID != -1) {
                                            //log.trace("startScraping: NFO ID != -1 {}", ID);
                                            // ugly but necessary to avoid poster delete when replacing tag
                                            if (tags.getDefaultPoster() != null)
                                                DeleteFileCallback.DO_NOT_DELETE.add(tags.getDefaultPoster().getLargeFile());
                                            if (tags instanceof EpisodeTags) {
                                                if (((EpisodeTags) tags).getEpisodePicture() != null) {
                                                    DeleteFileCallback.DO_NOT_DELETE.add(((EpisodeTags) tags).getEpisodePicture().getLargeFile());
                                                }
                                                if (((EpisodeTags) tags).getShowTags() != null && ((EpisodeTags) tags).getShowTags().getDefaultPoster() != null) {
                                                    DeleteFileCallback.DO_NOT_DELETE.add(((EpisodeTags) tags).getShowTags().getDefaultPoster().getLargeFile());
                                                }
                                            }
                                            //log.trace("startScraping: NFO tags.save ID={}", ID);
                                            tags.save(AutoScrapeService.this, ID);
                                            DeleteFileCallback.DO_NOT_DELETE.clear();
                                        } else {
                                            //log.trace("startScraping: oh oh NFO ID = -1 ");
                                        }
                                        //found NFO thus still no error but scraped
                                        notScraped = false;
                                        sNumberOfFilesScraped++;
                                        noScrapeError = true;
                                        if (tags.getPosters() == null && tags.getDefaultPoster() == null &&
                                                (!(tags instanceof EpisodeTags) || ((EpisodeTags) tags).getShowTags().getPosters() == null)) {//special case for episodes : check show
                                            if (tags.getTitle() != null && !tags.getTitle().isEmpty()) { //if a title is specified in nfo, use it to scrap file
                                                scrapUri = Uri.parse("/" + tags.getTitle() + ".mp4");
                                                //log.trace("startScraping: no posters using title {}", tags.getTitle());
                                            }
                                            //log.trace("startScraping: no posters ");
                                            //poster not found thus not scraped and no error
                                            notScraped = true;
                                            noScrapeError = true;
                                        }
                                        //log.trace("startScraping: NFO found, notScaped {}, noScrapeError {} for {}", notScraped, noScrapeError, fileUri);
                                    }
                                }
                                if (notScraped && noScrapeError) { //look for online details
                                    //log.trace("startScraping: NFO NOT found");
                                    ScrapeDetailResult result = null;
                                    boolean searchOnline = !shouldRescrapAll;
                                    if (shouldRescrapAll) {
                                        //log.trace("startScraping: rescraping all");
                                        long videoID = cursor.getLong(cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_VIDEO_ONLINE_ID));
                                        final int scraperType = cursor.getInt(cursor.getColumnIndex(VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_TYPE));

                                        if (scraperType == BaseTags.TV_SHOW) {
                                            // get the whole season
                                            long season = cursor.getLong(cursor.getColumnIndex(VideoStore.Video.VideoColumns.SCRAPER_E_SEASON));
                                            Bundle b = new Bundle();
                                            b.putInt(Scraper.ITEM_REQUEST_SEASON, (int) season);

                                            //log.trace("startScraping: rescraping episode for tvId {}, season {}", videoID, season);
                                            SearchResult searchResult = new SearchResult(SearchResult.tvshow, title, (int) videoID);
                                            searchResult.setFile(fileUri);
                                            searchResult.setScraper(new ShowScraper4(AutoScrapeService.this));
                                            result = ShowScraper4.getDetails(new SearchResult(SearchResult.tvshow, title, (int) videoID), b);
                                        } else if (scraperType == BaseTags.MOVIE) {
                                            //log.trace("startScraping: rescraping movie {}", videoID);
                                            SearchResult searchResult = new SearchResult(SearchResult.movie, title, (int) videoID);
                                            searchResult.setFile(fileUri);
                                            searchResult.setScraper(new MovieScraper3(AutoScrapeService.this));
                                            result = MovieScraper3.getDetails(searchResult, null);
                                        } else searchOnline = !searchOnline = !title.regionMatches(true, 0, "VOB_", 0, 4);
                                    }
                                    if (searchOnline) {
                                        //log.trace("startScraping: searching online {}", title);
                                        SearchInfo searchInfo = SearchPreprocessor.instance().parseFileBased(fileUri, scrapUri);
                                        Scraper scraper = new Scraper(AutoScrapeService.this);
                                        result = scraper.getAutoDetails(searchInfo);
                                        //log.trace("startScraping: {} {}", ((result.tag != null) ? result.tag.getTitle() : null), ((result.tag != null) ? result.tag.getOnlineId() : null));
                                    }

                                    if (result != null && result.tag != null && ID != -1 && result.tag.getTitle() != null && !result.tag.getTitle().equals("(NULL)")) {
                                        result.tag.setVideoId(ID);
                                        //ugly but necessary to avoid poster delete when replacing tag
                                        if (result.tag.getDefaultPoster() != null) {
                                            DeleteFileCallback.DO_NOT_DELETE.add(result.tag.getDefaultPoster().getLargeFile());
                                        }
                                        if (result.tag instanceof EpisodeTags) {
                                            if (((EpisodeTags) result.tag).getEpisodePicture() != null) {
                                                DeleteFileCallback.DO_NOT_DELETE.add(((EpisodeTags) result.tag).getEpisodePicture().getLargeFile());
                                            }
                                            if (((EpisodeTags) result.tag).getShowTags() != null && ((EpisodeTags) result.tag).getShowTags().getDefaultPoster() != null) {
                                                DeleteFileCallback.DO_NOT_DELETE.add(((EpisodeTags) result.tag).getShowTags().getDefaultPoster().getLargeFile());
                                            }
                                        }
                                        //log.trace("startScraping: online result.tag.save ID={}", ID);

                                        result.tag.save(AutoScrapeService.this, ID);
                                        DeleteFileCallback.DO_NOT_DELETE.clear();
                                        // result exists thus scraped and no error for now
                                        notScraped = false;
                                        sNumberOfFilesScraped++;
                                        noScrapeError = true;
                                        //if (result.tag.getTitle() != null)
                                        //    log.trace("startScraping: info {}", result.tag.getTitle());

                                        if (exportContext != null) {
                                            // also auto-export all the data

                                            if (fileUri != null) {
                                                try {
                                                    //log.trace("startScraping: exporting NFO");
                                                    NfoWriter.export(fileUri, result.tag, exportContext);
                                                } catch (IOException e) {
                                                    log.error("Caught IOException: ", e);
                                                }
                                            }
                                            //log.trace("startScraping: online info, notScaped {}, noScrapeError {} for {}", notScraped, noScrapeError, fileUri);
                                        }
                                    } else if (result != null) {
                                        //not scraped, check for errors
                                        // for tvshow if search returns ScrapeStatus.OKAY but in details it returns ScrapeStaus.ERROR_PARSER it is not counted as a scraping error
                                        // this allows the video to be marked as not to be rescraped
                                        notScraped = true;
                                        noScrapeError = result.status != ScrapeStatus.ERROR && result.status != ScrapeStatus.ERROR_NETWORK && result.status != ScrapeStatus.ERROR_NO_NETWORK;
                                        if (!noScrapeError) {
                                            log.trace("startScraping: file {} scrape error", fileUri);
                                        } else {
                                            sNumberOfFilesNotScraped++;
                                        }
                                        //log.trace("startScraping: file {} not scraped among {}", fileUri, sNumberOfFilesNotScraped);
                                    }
                                }

                                if (notScraped && noScrapeError && !shouldRescrapAll) { //in case of network error, don't go there, and don't save in case we are rescraping already scraped videos
                                    // Failed => set the scraper fields to -1 so that we will be able
                                    // to skip this file when launching the automated process again
                                    //log.trace("startScraping: file {} not scraped without error -> mark it as not to be scraped again", fileUri);
                                    ContentValues cv = new ContentValues(2);
                                    cv.put(VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_ID, String.valueOf(-1));
                                    cv.put(VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_TYPE, String.valueOf(-1));
                                    getContentResolver().update(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, cv, BaseColumns._ID + "=?", new String[]{Long.toString(ID)});
                                    
                                    // Since scraping failed, create thumbnail now if deferred thumbnails are enabled
                                    if (VideoProvider.DEFER_THUMBNAILS_FOR_SCRAPING) {
                                        //log.trace("startScraping: creating deferred thumbnail for failed scrape: {}", fileUri);
                                        try {
                                            String filePath = fileUri.toString();
                                            VideoProvider.MediaThumbRequest.createVideoThumbnail(AutoScrapeService.this, filePath, VideoStore.Video.Thumbnails.MINI_KIND);
                                            //log.trace("startScraping: deferred thumbnail created successfully for {}", fileUri);
                                            
                                            // Notify content resolver to refresh UI cursors
                                            getContentResolver().notifyChange(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, null);
                                        } catch (Exception e) {
                                            //log.warn("startScraping: failed to create thumbnail for {}", fileUri, e);
                                        }
                                    }
                                } else if (!noScrapeError) { // condition is scrapedOrError
                                    //log.trace("startScraping: file {} scraped but with error -> increase mNetworkOrScrapErrors", fileUri);
                                    mNetworkOrScrapErrors++;
                                }
                                sNumberOfFilesRemainingToProcess--;
                                sTotalNumberOfFilesRemainingToProcess--;
                                //log.debug("startScraping: #filesProcessed={}/{} ({}), #scrapOrNetworkErrors={}, #notScraped={}, current batch #filesToProcess={}/{}", sNumberOfFilesScraped, numberOfRows, sTotalNumberOfFilesRemainingToProcess, mNetworkOrScrapErrors, sNumberOfFilesNotScraped, sNumberOfFilesRemainingToProcess, window);
                            }
                            cursor.close();
                            numberOfRowsRemaining -= window;
                            totalNumberOfFilesScraped+= totalNumberOfFilesScraped;
                        } while (numberOfRowsRemaining > 0 && (isForeground || isForceAfterNetworkScan) && !Thread.currentThread().isInterrupted());
                        if (numberOfRows == mNetworkOrScrapErrors) { //when as many errors, we assume we don't have the internet or that the scraper returns an error, do not loop
                            restartOnNextRound = false;
                            //log.debug("startScraping: no internet or scraper errors, stop iterating");
                        } else {
                            //do not restartOnNextRound if all files are processed i.e. notScraped and scraped, do it only if mNetworkOrScrapErrors
                            if (sNumberOfFilesScraped + sNumberOfFilesNotScraped >= numberOfRows) restartOnNextRound = false;
                            //log.debug("startScraping: numberOfRows != mNetworkOrScrapErrors, {}!={}, #Scraped={}, #NotScraped={}, restartOnNextRound ={}", numberOfRows, mNetworkOrScrapErrors, sNumberOfFilesScraped, sNumberOfFilesNotScraped, restartOnNextRound);
                        }
                        shouldRescrapAll = false; //to avoid rescraping on next round
                        // final check if while scanning there was no more files to scrape added
                        cursor = getFileListCursor(shouldRescrapAll&&onlyNotFound ?PARAM_SCRAPED_NOT_FOUND:shouldRescrapAll?PARAM_ALL:PARAM_NOT_SCRAPED, null, null, null);
                        if(cursor.getCount()>0) {
                            restartOnNextRound = true;
                            //log.debug("startScraping: new entries to scrape found most likely added during scrape process, restartOnNextRound");
                        }
                        cursor.close();
                    } while(restartOnNextRound && (isForeground || isForceAfterNetworkScan) && !Thread.currentThread().isInterrupted()
                            &&PreferenceManager.getDefaultSharedPreferences(AutoScrapeService.this).getBoolean(AutoScrapeService.KEY_ENABLE_AUTO_SCRAP, true)); //if we had something to do, we look for new videos
                    sIsScraping = false;
                    // Note: isForceAfterNetworkScan is now managed by networkScanCount
                    // Exit foreground mode and remove notification since scraping is complete
                    stopForeground(true);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            WrapperChannelManager.refreshChannels(AutoScrapeService.this);
                        }
                    });
                    if (totalNumberOfFilesScraped > 0) {
                        // Save the last scraped timestamp in UTC seconds
                        long utcSeconds = System.currentTimeMillis() / 1000L;
                        PreferenceManager.getDefaultSharedPreferences(AutoScrapeService.this)
                        .edit()
                        .putLong(PREFERENCE_LAST_TIME_VIDEO_SCRAPED_UTC, utcSeconds).apply();
                        TraktService.onNewVideo(AutoScrapeService.this); // should be done only at the end to not resync in loop
                    }
                }
            };
            mThread.start();
        }
    }

    private static final String WHERE_BASE =
                    VideoStore.Video.VideoColumns.LEEROYFLIX_HIDE_FILE + "=0 AND " +
                    VideoStore.MediaColumns.DATA + " NOT LIKE ?";
    private static final String WHERE_NOT_SCRAPED =
            VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_ID + "=0 AND "+ WHERE_BASE;

    private static final String WHERE_SCRAPED_NOT_FOUND =
            VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_ID + "=-1 AND "+ WHERE_BASE;

    private static final String WHERE_SCRAPED =
            VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_ID + ">0 AND " + WHERE_BASE;

    private static final String WHERE_SCRAPED_ALL =
            VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_ID + ">=0 AND " + WHERE_BASE;

    private static final String WHERE_MOVIES =
            VideoStore.Video.VideoColumns.LEEROYFLIX_MEDIA_SCRAPER_ID + ">=0 AND " +
            VideoStore.Video.VideoColumns.SCRAPER_MOVIE_ID + " IS NOT NULL AND " + WHERE_BASE;

    private Cursor getFileListCursor(int scrapStatusParam, String sortOrder, Integer offset, Integer limit) {
        // Look for all the videos not yet processed and not located in the Camera folder
        final String cameraPath =  Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getPath() + "/Camera";
        String[] selectionArgs = new String[]{ cameraPath + "/%" };
        String where  = null;
        switch(scrapStatusParam){
            case PARAM_NOT_SCRAPED:
                where = WHERE_NOT_SCRAPED;
                break;
            case PARAM_SCRAPED:
                where = WHERE_SCRAPED;
                break;
            case PARAM_ALL:
                where = WHERE_SCRAPED_ALL;
                break;
            case PARAM_SCRAPED_NOT_FOUND:
                where = WHERE_SCRAPED_NOT_FOUND;
                break;
            case PARAM_MOVIES:
                where = WHERE_MOVIES;
                break;
            default:
                where = WHERE_BASE;
                break;
        }
        final String LIMIT = ((offset != null) ? offset + ",": "") + ((limit != null) ? limit : "");
        if (limit != null || offset != null) {
            return getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon().appendQueryParameter("limit", LIMIT).build(), SCRAPER_ACTIVITY_COLS, where, selectionArgs, sortOrder);
        } else {
            return getContentResolver().query(VideoStore.Video.Media.EXTERNAL_CONTENT_URI, SCRAPER_ACTIVITY_COLS, where, selectionArgs, sortOrder);
        }
    }

    public void stopService() {
        log.debug("stopService");
        stopForeground(true);
        if(mWakeLock!=null&&mWakeLock.isHeld())
            mWakeLock.release();
    }

    private void saveDirtyState(boolean dirtyState) {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(PREF_IS_SCRAPE_DIRTY, dirtyState)
                .apply();
    }

    private boolean isDirtyState() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_IS_SCRAPE_DIRTY, false);
    }

}