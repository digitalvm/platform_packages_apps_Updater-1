package co.copperhead.updater;

import static android.os.Build.DEVICE;
import static android.os.Build.VERSION.INCREMENTAL;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RecoverySystem;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngine.ErrorCodeConstants;
import android.os.UpdateEngineCallback;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Service extends IntentService {
    private static final String TAG = "Service";
    private static final int NOTIFICATION_ID = 1;
    private static final int PENDING_REBOOT_ID = 1;
    private static final int CONNECT_TIMEOUT = 60000;
    private static final int READ_TIMEOUT = 60000;
    private static final File CARE_MAP_PATH = new File("/data/ota_package/care_map.txt");
    private static final File UPDATE_PATH = new File("/data/ota_package/update.zip");
    private static final String PREFERENCE_CHANNEL = "channel";
    private static final String PREFERENCE_DOWNLOAD_FILE = "download_file";
    private static final String PREFERENCE_IDLE_REBOOT = "idle_reboot";

    private boolean updating = false;

    public Service() {
        super(TAG);
    }

    private URLConnection fetchData(final String path) throws IOException {
        final URL url = new URL(getString(R.string.url) + path);
        final URLConnection urlConnection = url.openConnection();
        urlConnection.setConnectTimeout(CONNECT_TIMEOUT);
        urlConnection.setReadTimeout(READ_TIMEOUT);
        return urlConnection;
    }

    private void applyUpdate(final long payloadOffset, final String[] headerKeyValuePairs) {
        final CountDownLatch monitor = new CountDownLatch(1);
        final UpdateEngine engine = new UpdateEngine();
        engine.bind(new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                Log.d(TAG, "onStatusUpdate: " + status + ", " + percent);
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                if (errorCode == ErrorCodeConstants.SUCCESS) {
                    Log.d(TAG, "onPayloadApplicationComplete success");
                    PeriodicJob.cancel(Service.this);
                    annoyUser();
                } else {
                    Log.d(TAG, "onPayloadApplicationComplete: " + errorCode);
                    updating = false;
                }
                UPDATE_PATH.delete();
                monitor.countDown();
            }
        });
        UPDATE_PATH.setReadable(true, false);
        engine.applyPayload("file://" + UPDATE_PATH, payloadOffset, 0, headerKeyValuePairs);
        try {
            monitor.await();
        } catch (InterruptedException e) {}
    }

    private void onDownloadFinished(final long targetBuildDate) throws IOException, GeneralSecurityException {
        try {
            RecoverySystem.verifyPackage(UPDATE_PATH,
                (int progress) -> Log.d(TAG, "verifyPackage: " + progress + "%"), null);

            final ZipFile zipFile = new ZipFile(UPDATE_PATH);

            final ZipEntry metadata = zipFile.getEntry("META-INF/com/android/metadata");
            if (metadata == null) {
                throw new GeneralSecurityException("missing metadata file");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(metadata)));
            String device = null;
            long timestamp = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                String[] pair = line.split("=");
                if ("post-timestamp".equals(pair[0])) {
                    timestamp = Long.parseLong(pair[1]);
                } else if ("pre-device".equals(pair[0])) {
                    device = pair[1];
                }
            }
            if (timestamp != targetBuildDate) {
                throw new GeneralSecurityException("timestamp does not match server metadata");
            }
            if (!DEVICE.equals(device)) {
                throw new GeneralSecurityException("device mismatch");
            }

            final ZipEntry careMapEntry = zipFile.getEntry("care_map.txt");
            if (careMapEntry == null) {
                Log.w(TAG, "care_map.txt missing");
                if (CARE_MAP_PATH.exists()) {
                    CARE_MAP_PATH.delete();
                }
            } else {
                final InputStream careMapData = zipFile.getInputStream(careMapEntry);
                final OutputStream careMap = new FileOutputStream(CARE_MAP_PATH);

                int bytesRead;
                final byte[] buffer = new byte[8192];
                while ((bytesRead = careMapData.read(buffer)) != -1) {
                    careMap.write(buffer, 0, bytesRead);
                }
                careMap.close();
                CARE_MAP_PATH.setReadable(true, false);
            }

            final List<String> lines = new ArrayList<String>();
            long payloadOffset = 0;

            final Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            long offset = 0;
            while (zipEntries.hasMoreElements()) {
                final ZipEntry entry = (ZipEntry) zipEntries.nextElement();
                final long extra = entry.getExtra() == null ? 0 : entry.getExtra().length;
                final long zipHeaderLength = 30;
                offset += zipHeaderLength + entry.getName().length() + extra;
                if (!entry.isDirectory()) {
                    if ("payload.bin".equals(entry.getName())) {
                        payloadOffset = offset;
                    } else if ("payload_properties.txt".equals(entry.getName())) {
                        reader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(entry)));
                        for (String line; (line = reader.readLine()) != null; ) {
                            lines.add(line);
                        }
                    }
                    offset += entry.getCompressedSize();
                }
            }

            applyUpdate(payloadOffset, lines.toArray(new String[lines.size()]));
        } catch (GeneralSecurityException e) {
            UPDATE_PATH.delete();
            throw e;
        }
    }

    private void annoyUser() {
        final SharedPreferences preferences = Settings.getPreferences(this);
        if (preferences.getBoolean(PREFERENCE_IDLE_REBOOT, false)) {
            IdleReboot.schedule(this);
        }

        final PendingIntent reboot = PendingIntent.getBroadcast(this, PENDING_REBOOT_ID, new Intent(this, RebootReceiver.class), 0);
        final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, new Notification.Builder(this)
            .addAction(R.drawable.ic_restart, getString(R.string.notification_reboot_action), reboot)
            .setCategory(Notification.CATEGORY_SYSTEM)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setDefaults(Notification.DEFAULT_ALL)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setSmallIcon(R.drawable.ic_update_white_24dp)
            .build());
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "onHandleIntent");

        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        final WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        try {
            wakeLock.acquire();

            if (updating) {
                Log.d(TAG, "updating already, returning early");
                return;
            }
            updating = true;

            final SharedPreferences preferences = Settings.getPreferences(this);
            final String channel = SystemProperties.get("sys.update.channel",
                preferences.getString(PREFERENCE_CHANNEL, "stable"));

            Log.d(TAG, "fetching metadata for " + DEVICE + "-" + channel);
            InputStream input = fetchData(DEVICE + "-" + channel).getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            final String[] metadata = reader.readLine().split(" ");
            reader.close();

            final String targetIncremental = metadata[0];
            final long targetBuildDate = Long.parseLong(metadata[1]);
            final long sourceBuildDate = SystemProperties.getLong("ro.build.date.utc", 0);
            if (targetBuildDate <= sourceBuildDate) {
                Log.d(TAG, "targetBuildDate: " + targetBuildDate + " not higher than sourceBuildDate: " + sourceBuildDate);
                return;
            }

            String downloadFile = preferences.getString(PREFERENCE_DOWNLOAD_FILE, null);
            long downloaded = UPDATE_PATH.length();

            final String incrementalUpdate = DEVICE + "-incremental-" + INCREMENTAL + "-" + targetIncremental + ".zip";
            final String fullUpdate = DEVICE + "-ota_update-" + targetIncremental + ".zip";

            if (incrementalUpdate.equals(downloadFile) || fullUpdate.equals(downloadFile)) {
                Log.d(TAG, "resume fetch of " + downloadFile + " from " + downloaded + " bytes");
                final HttpURLConnection connection = (HttpURLConnection) fetchData(downloadFile);
                connection.setRequestProperty("Range", "bytes=" + downloaded + "-");
                if (connection.getResponseCode() == 416) {
                    Log.d(TAG, "download completed previously");
                    onDownloadFinished(targetBuildDate);
                    return;
                }
                input = connection.getInputStream();
            } else {
                try {
                    Log.d(TAG, "fetch incremental " + incrementalUpdate);
                    downloadFile = incrementalUpdate;
                    input = fetchData(downloadFile).getInputStream();
                } catch (IOException e) {
                    Log.d(TAG, "incremental not found, fetch full update " + fullUpdate);
                    downloadFile = fullUpdate;
                    input = fetchData(downloadFile).getInputStream();
                }
                downloaded = 0;
            }

            final OutputStream output = new FileOutputStream(UPDATE_PATH, downloaded != 0);
            preferences.edit().putString(PREFERENCE_DOWNLOAD_FILE, downloadFile).commit();

            int bytesRead;
            long last = System.nanoTime();
            final byte[] buffer = new byte[8192];
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                downloaded += bytesRead;
                final long now = System.nanoTime();
                if (now - last > 1000 * 1000 * 1000) {
                    Log.d(TAG, "downloaded " + downloaded + " bytes");
                    last = now;
                }
            }
            output.close();
            input.close();

            Log.d(TAG, "download completed");
            onDownloadFinished(targetBuildDate);
        } catch (IOException | GeneralSecurityException e) {
            Log.e(TAG, "failed to download and install update", e);
            updating = false;
            PeriodicJob.scheduleRetry(this);
        } finally {
            Log.d(TAG, "release wake locks");
            wakeLock.release();
            TriggerUpdateReceiver.completeWakefulIntent(intent);
        }
    }
}
