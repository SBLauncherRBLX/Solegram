package org.telegram.messenger;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class NotificationImageProvider extends ContentProvider implements NotificationCenter.NotificationCenterDelegate {

	private static String authority;
	private static UriMatcher matcher;

	private HashSet<String> waitingForFiles = new HashSet<>();
	private final Object sync = new Object();
	private HashMap<String, Long> fileStartTimes = new HashMap<>();
	// Solegram: accounts whose fileLoaded we observe, registered on demand (UI thread only)
	private final boolean[] observedAccounts = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

	public static String getAuthority() {
		if (authority == null) {
			authority = ApplicationLoader.getApplicationId() + ".notification_image_provider";
		}
		return authority;
	}

	private static UriMatcher getUriMatcher() {
		if (matcher == null) {
			matcher = new UriMatcher(UriMatcher.NO_MATCH);
			matcher.addURI(getAuthority(), "msg_media_raw/#/*", 1); // content://org.telegram..../msg_media_raw/account/filename.ext
		}
		return matcher;
	}

	@Override
	public boolean onCreate() {
		// Solegram: upstream looped 0..getActivatedAccountsCount()-1 here, which skips accounts in higher
		// slots (e.g. only 0 and 5 logged in) and runs before any config is loaded; see observeAccount()
		return true;
	}

	private void observeAccount(int account) {
		if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
			return;
		}
		AndroidUtilities.runOnUIThread(() -> {
			if (!observedAccounts[account]) {
				observedAccounts[account] = true;
				NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
			}
		});
	}

	@Override
	public void shutdown() {
		AndroidUtilities.runOnUIThread(() -> { // Solegram: only the accounts observeAccount() registered
			for (int i = 0; i < observedAccounts.length; i++) {
				if (observedAccounts[i]) {
					observedAccounts[i] = false;
					NotificationCenter.getInstance(i).removeObserver(this, NotificationCenter.fileLoaded);
				}
			}
		});
	}

	@Nullable
	@Override
	public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
		return null;
	}

	@Nullable
	@Override
	public String getType(@NonNull Uri uri) {
		return null;
	}

	@Nullable
	@Override
	public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
		return null;
	}

	@Override
	public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
		return 0;
	}

	@Override
	public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
		return 0;
	}

	@Nullable
	@Override
	public String[] getStreamTypes(@NonNull Uri uri, @NonNull String mimeTypeFilter) {
		if (mimeTypeFilter.startsWith("*/") || mimeTypeFilter.startsWith("image/")) {
			return new String[]{"image/jpeg", "image/png", "image/webp"};
		}
		return null;
	}

	@Nullable
	@Override
	public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
		if (!"r".equals(mode)) {
			throw new SecurityException("Can only open files for read");
		}
		if (getUriMatcher().match(uri) == 1) {
			List<String> path = uri.getPathSegments();
			int account = Integer.parseInt(path.get(1));
			String name = path.get(2);
			String finalPath = uri.getQueryParameter("final_path");
			String fallbackPath = uri.getQueryParameter("fallback");
			File finalFile = new File(finalPath);
			ApplicationLoader.postInitApplication();
			if (AndroidUtilities.isInternalUri(Uri.fromFile(finalFile))) {
				throw new SecurityException("trying to read internal file");
			}
			if (!finalFile.exists()) {
				observeAccount(account); // Solegram: wake up as soon as this account finishes the download
				Long _startTime = fileStartTimes.get(name);
				long startTime = _startTime != null ? _startTime : System.currentTimeMillis();
				if (_startTime == null) {
					fileStartTimes.put(name, startTime);
				}
				while (!finalFile.exists()) {
					if (System.currentTimeMillis() - startTime >= 3000) {
						if (BuildVars.LOGS_ENABLED) {
							FileLog.w("Waiting for " + name + " to download timed out");
						}
						if (TextUtils.isEmpty(fallbackPath)) {
							throw new FileNotFoundException("Download timed out");
						}
						File file = new File(fallbackPath);
						if (AndroidUtilities.isInternalUri(Uri.fromFile(file))) {
							throw new SecurityException("trying to read internal file");
						}
						return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
					}
					synchronized (sync) {
						waitingForFiles.add(name);
						try {
							sync.wait(1000);
						} catch (InterruptedException ignore) {
						}
					}
				}
				if (AndroidUtilities.isInternalUri(Uri.fromFile(finalFile))) {
					throw new SecurityException("trying to read internal file");
				}
			}
			return ParcelFileDescriptor.open(finalFile, ParcelFileDescriptor.MODE_READ_ONLY);
		}
		throw new FileNotFoundException("Invalid URI");
	}

	@Override
	public void didReceivedNotification(int id, int account, Object... args) {
		if (id == NotificationCenter.fileLoaded) {
			synchronized (sync) {
				String name = (String) args[0];
				if (waitingForFiles.remove(name)) {
					fileStartTimes.remove(name);
					sync.notifyAll();
				}
			}
		}
	}
}
