// Copyright 2020 Courville Software
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

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.archos.environment.ArchosUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NetworkRefreshJob extends JobService implements DefaultLifecycleObserver {

    private static final Logger log = LoggerFactory.getLogger(NetworkRefreshJob.class);

    @Override
    public void onCreate() {
        super.onCreate();
        if (log.isDebugEnabled()) log.debug("onCreate");
        // Register as a lifecycle observer
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (log.isDebugEnabled()) log.debug("onStartJob");
        Context context = getApplicationContext();
        Intent intent = new Intent(context, NetworkAutoRefresh.class);
        intent.setAction(NetworkAutoRefresh.ACTION_RESCAN_INDEXED_FOLDERS);
        intent.setPackage(ArchosUtils.getGlobalContext().getPackageName());
        context.sendBroadcast(intent);
        // reschedule the job for next period
        NetworkScannerUtil.scheduleJob(getApplicationContext());
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    @Override
    public void onStop(LifecycleOwner owner) {
        // App in background
        if (log.isDebugEnabled()) log.debug("onStop: LifecycleOwner app in background");
        stopSelf();
    }

    @Override
    public void onStart(LifecycleOwner owner) {
        // App in foreground
        if (log.isDebugEnabled()) log.debug("onStart: LifecycleOwner app in foreground");
    }
}