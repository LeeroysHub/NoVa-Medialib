// Copyright 2017 Archos SA
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

package com.archos.mediaprovider.video;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.archos.environment.ArchosUtils;

import io.sentry.SentryLevel;

/**
 * receiver for events that trigger mediastore import
 */
public class VideoStoreImportReceiver extends BroadcastReceiver {
    // /!\ DO NOT use slf4j master logger not initialized package name not known otherwise fileNotFound
    //private static final Logger log = LoggerFactory.getLogger(VideoStoreImportReceiver.class);

    private static final String TAG =  VideoStoreImportReceiver.class.getSimpleName();
    private static final boolean DBG = false;
    
    // Throttling to prevent broadcast loops - minimum 500ms between executions for the SAME URI
    private static final long MIN_INTERVAL_MS = 500;
    private static long sLastExecutionTime = 0;
    private static String sLastUri = null;

    public VideoStoreImportReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DBG) Log.d(TAG, "onReceive:" + intent);

        // Throttling to prevent broadcast storms - but only for duplicate URIs
        long currentTime = System.currentTimeMillis();
        String currentUri = intent.getData() != null ? intent.getData().toString() : null;
        synchronized (VideoStoreImportReceiver.class) {
            // Only throttle if it's the SAME URI within 500ms
            if (currentTime - sLastExecutionTime < MIN_INTERVAL_MS &&
                currentUri != null && currentUri.equals(sLastUri)) {
                if (DBG) Log.d(TAG, "onReceive: throttled - same URI too soon: " + currentUri);
                return;
            }
            sLastExecutionTime = currentTime;
            sLastUri = currentUri;
        }
        
        ArchosUtils.addBreadcrumb(SentryLevel.INFO, "VideoStoreImportReceiver.onReceive", "start NetworkScannerServiceVideo and VideoStoreImportService via intent if intent supported");
        // start network scan / removal service
        if (! ProcessLifecycleOwner.get().getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            // do not start services if app is in background
            if (DBG) Log.d(TAG, "onReceive: app is in background, do not start services");
            return;
        }
        NetworkScannerServiceVideo.startIfHandles(context, intent);
        // in addition and all other cases inform import service about the event but only if this is something we handle
        VideoStoreImportService.startIfHandles(context, intent);
    }

}
