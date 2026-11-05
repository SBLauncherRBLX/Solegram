/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.maps;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.IMapsProvider;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.Locale;

/**
 * Maps other than Google's: which map the location screens draw (Google, or OpenStreetMap where there are no
 * Google services or by choice), and which app routes open in.
 */
public final class SolegramMaps {

    public static final int ENGINE_AUTO = 0;
    public static final int ENGINE_GOOGLE = 1;
    public static final int ENGINE_OSM = 2;

    public static final int NAV_ASK = 0;
    public static final int NAV_GOOGLE = 1;
    public static final int NAV_YANDEX_MAPS = 2;
    public static final int NAV_YANDEX_NAVI = 3;
    public static final int NAV_2GIS = 4;
    public static final int NAV_ORGANIC = 5;
    public static final int NAV_OSMAND = 6;
    public static final int NAV_WAZE = 7;
    public static final int NAV_COUNT = 8;

    private static final String[] NAV_PACKAGES = {null, "com.google.android.apps.maps", "ru.yandex.yandexmaps", "ru.yandex.yandexnavi",
        "ru.dublgis.dgismobile", "app.organicmaps", "net.osmand", "com.waze"};

    private static IMapsProvider osm;
    private static Boolean googleServices;

    private SolegramMaps() {
    }

    // region The map in the app

    /** The provider the location screens use now (ApplicationLoader asks here first); null keeps Telegram's own. */
    public static IMapsProvider provider() {
        if (!useOsm()) {
            return null;
        }
        if (osm == null) {
            osm = new OsmMapsProvider();
        }
        return osm;
    }

    public static boolean useOsm() {
        final int engine = SolegramConfig.mapEngine;
        return engine == ENGINE_OSM || engine == ENGINE_AUTO && !hasGoogleServices();
    }

    public static boolean hasGoogleServices() {
        if (googleServices == null) {
            boolean ok;
            try {
                ok = com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ApplicationLoader.applicationContext)
                    == com.google.android.gms.common.ConnectionResult.SUCCESS;
            } catch (Throwable e) {
                ok = false;
            }
            googleServices = ok;
        }
        return googleServices;
    }

    public static String engineName(int engine) {
        switch (engine) {
            case ENGINE_GOOGLE:
                return "Google Maps";
            case ENGINE_OSM:
                return "OpenStreetMap";
            default:
                return LocaleController.getString(R.string.SG_MapEngineAuto);
        }
    }
    // endregion

    // region Routes

    public static String navName(int app) {
        switch (app) {
            case NAV_GOOGLE:
                return "Google Maps";
            case NAV_YANDEX_MAPS:
                return LocaleController.getString(R.string.SG_MapsYandexMaps);
            case NAV_YANDEX_NAVI:
                return LocaleController.getString(R.string.SG_MapsYandexNavi);
            case NAV_2GIS:
                return "2GIS";
            case NAV_ORGANIC:
                return "Organic Maps";
            case NAV_OSMAND:
                return "OsmAnd";
            case NAV_WAZE:
                return "Waze";
            default:
                return LocaleController.getString(R.string.SG_MapsAskApp);
        }
    }

    public static boolean installed(int app) {
        final String pkg = app > 0 && app < NAV_PACKAGES.length ? NAV_PACKAGES[app] : null;
        if (pkg == null) {
            return true;
        }
        try {
            ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * A route to a point in the app the user chose (from where they are, when that is known); the phone's own choice
     * of map apps when that app is not installed or "Ask" is set.
     */
    public static void openDirections(Activity activity, double fromLat, double fromLon, boolean hasFrom, double toLat, double toLon) {
        if (activity == null) {
            return;
        }
        final int app = SolegramConfig.mapsNavApp;
        String uri = null;
        switch (app) {
            case NAV_GOOGLE:
                uri = hasFrom ? String.format(Locale.US, "http://maps.google.com/maps?saddr=%f,%f&daddr=%f,%f", fromLat, fromLon, toLat, toLon)
                    : String.format(Locale.US, "http://maps.google.com/maps?saddr=&daddr=%f,%f", toLat, toLon);
                break;
            case NAV_YANDEX_MAPS:
                uri = hasFrom ? String.format(Locale.US, "yandexmaps://maps.yandex.ru/?rtext=%f,%f~%f,%f&rtt=auto", fromLat, fromLon, toLat, toLon)
                    : String.format(Locale.US, "yandexmaps://maps.yandex.ru/?rtext=~%f,%f&rtt=auto", toLat, toLon);
                break;
            case NAV_YANDEX_NAVI:
                uri = String.format(Locale.US, "yandexnavi://build_route_on_map?lat_to=%f&lon_to=%f", toLat, toLon);
                break;
            case NAV_2GIS:
                uri = String.format(Locale.US, "dgis://2gis.ru/routeSearch/rsType/car/to/%f,%f", toLon, toLat);
                break;
            case NAV_ORGANIC:
                uri = String.format(Locale.US, "om://route?dll=%f,%f&daddr=%s&type=vehicle", toLat, toLon, Uri.encode(LocaleController.getString(R.string.SG_MapsDestination)));
                break;
            case NAV_OSMAND:
                uri = String.format(Locale.US, "osmand.api://navigate?dest_lat=%f&dest_lon=%f&profile=car", toLat, toLon);
                break;
            case NAV_WAZE:
                uri = String.format(Locale.US, "https://waze.com/ul?ll=%f,%f&navigate=yes", toLat, toLon);
                break;
        }
        if (uri != null && installed(app)) {
            try {
                final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                intent.setPackage(NAV_PACKAGES[app]);
                activity.startActivity(intent);
                return;
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        openPoint(activity, toLat, toLon, null);
    }

    /** A point (or an address) in the phone's choice of map apps. */
    public static void openPoint(Activity activity, double lat, double lon, String query) {
        final String uri = query != null ? "geo:0,0?q=" + Uri.encode(query)
            : String.format(Locale.US, "geo:%f,%f?q=%f,%f", lat, lon, lat, lon);
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            activity.startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.SG_MapsOpenIn)));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
    // endregion
}
