/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.maps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.IMapsProvider;
import org.telegram.messenger.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps without Google: OpenStreetMap data drawn by osmdroid, with CARTO's tiles (light and dark) and Esri's imagery
 * for the satellite view. Implements Telegram's map interface, so the location screens work with it unchanged.
 */
public final class OsmMapsProvider implements IMapsProvider {

    private static boolean configured;

    private static void configure(Context context) {
        if (configured) {
            return;
        }
        configured = true;
        final org.osmdroid.config.IConfigurationProvider c = Configuration.getInstance();
        c.load(context, context.getSharedPreferences("solegram_osm", Context.MODE_PRIVATE));
        c.setUserAgentValue("Solegram/" + com.solegram.messenger.Solegram.getVersionName() + " (" + context.getPackageName() + ")");
        final File base = new File(context.getCacheDir(), "osmdroid");
        c.setOsmdroidBasePath(base);
        c.setOsmdroidTileCache(new File(base, "tiles"));
        c.setTileFileSystemCacheMaxBytes(80L * 1024 * 1024);
        c.setTileFileSystemCacheTrimBytes(60L * 1024 * 1024);
    }

    // region Tiles

    private static final class XyzSource extends OnlineTileSourceBase {
        private final boolean yx;

        XyzSource(String name, int maxZoom, String copyright, boolean yx, String... urls) {
            super(name, 0, maxZoom, 256, ".png", urls, copyright, new TileSourcePolicy(2,
                TileSourcePolicy.FLAG_NO_BULK | TileSourcePolicy.FLAG_NO_PREVENTIVE | TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
                    | TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED));
            this.yx = yx;
        }

        @Override
        public String getTileURLString(long index) {
            final int z = MapTileIndex.getZoom(index), x = MapTileIndex.getX(index), y = MapTileIndex.getY(index);
            return getBaseUrl() + z + "/" + (yx ? y + "/" + x : x + "/" + y + mImageFilenameEnding);
        }
    }

    private static final String OSM = "© OpenStreetMap contributors";
    static final OnlineTileSourceBase LIGHT = new XyzSource("CartoVoyager", 20, OSM + " © CARTO", false,
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/", "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/");
    static final OnlineTileSourceBase DARK = new XyzSource("CartoDark", 20, OSM + " © CARTO", false,
        "https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/");
    static final OnlineTileSourceBase SATELLITE = new XyzSource("EsriImagery", 19, "Esri, Maxar, Earthstar Geographics", true,
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/");

    // endregion

    @Override
    public void initializeMaps(Context context) {
        configure(context);
    }

    @Override
    public IMapView onCreateMapView(Context context) {
        configure(context.getApplicationContext());
        return new OsmMapView(context);
    }

    @Override
    public IMarkerOptions onCreateMarkerOptions() {
        return new OsmMarkerOptions();
    }

    @Override
    public ICircleOptions onCreateCircleOptions() {
        return new OsmCircleOptions();
    }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() {
        return new OsmBoundsBuilder();
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLng(LatLng latLng) {
        return new OsmCameraUpdate(latLng, -1, null, 0);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(LatLng latLng, float zoom) {
        return new OsmCameraUpdate(latLng, zoom, null, 0);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) {
        return new OsmCameraUpdate(null, -1, ((OsmBounds) bounds).box, padding);
    }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) {
        return new OsmStyle(resId == R.raw.mapstyle_night);
    }

    /** No separate app to install: the map is drawn here. */
    @Override
    public String getMapsAppPackageName() {
        return ApplicationLoader.applicationContext.getPackageName();
    }

    @Override
    public int getInstallMapsString() {
        return R.string.InstallGoogleMaps;
    }

    // region Small value types

    private static final class OsmStyle implements IMapStyleOptions {
        final boolean night;

        OsmStyle(boolean night) {
            this.night = night;
        }
    }

    private static final class OsmCameraUpdate implements ICameraUpdate {
        final LatLng target;
        final float zoom;
        final BoundingBox box;
        final int padding;

        OsmCameraUpdate(LatLng target, float zoom, BoundingBox box, int padding) {
            this.target = target;
            this.zoom = zoom;
            this.box = box;
            this.padding = padding;
        }
    }

    private static final class OsmBounds implements ILatLngBounds {
        final BoundingBox box;

        OsmBounds(BoundingBox box) {
            this.box = box;
        }

        @Override
        public LatLng getCenter() {
            final GeoPoint c = box.getCenterWithDateLine();
            return new LatLng(c.getLatitude(), c.getLongitude());
        }
    }

    private static final class OsmBoundsBuilder implements ILatLngBoundsBuilder {
        private final ArrayList<GeoPoint> points = new ArrayList<>();

        @Override
        public ILatLngBoundsBuilder include(LatLng latLng) {
            points.add(new GeoPoint(latLng.latitude, latLng.longitude));
            return this;
        }

        @Override
        public ILatLngBounds build() {
            BoundingBox box = BoundingBox.fromGeoPointsSafe(points);
            if (points.size() == 1 || box.getLatitudeSpan() < 1e-6 && box.getLongitudeSpanWithDateLine() < 1e-6) {
                final GeoPoint p = points.isEmpty() ? new GeoPoint(0d, 0d) : points.get(0);
                box = new BoundingBox(p.getLatitude() + 0.002, p.getLongitude() + 0.002, p.getLatitude() - 0.002, p.getLongitude() - 0.002);
            }
            return new OsmBounds(box);
        }
    }

    private static final class OsmMarkerOptions implements IMarkerOptions {
        LatLng position;
        Bitmap bitmap;
        int resId;
        float anchorU = 0.5f, anchorV = 1f;
        String title, snippet;
        boolean flat;

        @Override
        public IMarkerOptions position(LatLng latLng) {
            position = latLng;
            return this;
        }

        @Override
        public IMarkerOptions icon(Bitmap bitmap) {
            this.bitmap = bitmap;
            return this;
        }

        @Override
        public IMarkerOptions icon(int resId) {
            this.resId = resId;
            return this;
        }

        @Override
        public IMarkerOptions anchor(float lat, float lng) {
            anchorU = lat;
            anchorV = lng;
            return this;
        }

        @Override
        public IMarkerOptions title(String title) {
            this.title = title;
            return this;
        }

        @Override
        public IMarkerOptions snippet(String snippet) {
            this.snippet = snippet;
            return this;
        }

        @Override
        public IMarkerOptions flat(boolean flat) {
            this.flat = flat;
            return this;
        }
    }

    private static final class OsmCircleOptions implements ICircleOptions {
        LatLng center;
        double radius;
        int strokeColor, fillColor, strokeWidth = 2;
        List<PatternItem> pattern;

        @Override
        public ICircleOptions center(LatLng latLng) {
            center = latLng;
            return this;
        }

        @Override
        public ICircleOptions radius(double radius) {
            this.radius = radius;
            return this;
        }

        @Override
        public ICircleOptions strokeColor(int color) {
            strokeColor = color;
            return this;
        }

        @Override
        public ICircleOptions fillColor(int color) {
            fillColor = color;
            return this;
        }

        @Override
        public ICircleOptions strokePattern(List<PatternItem> patternItems) {
            pattern = patternItems;
            return this;
        }

        @Override
        public ICircleOptions strokeWidth(int width) {
            strokeWidth = width;
            return this;
        }
    }

    // endregion

    // region The view

    public static final class OsmMapView implements IMapView {
        private final FrameLayout container;
        private final MapView mapView;
        private ITouchInterceptor dispatchInterceptor, interceptInterceptor;
        private Runnable onLayoutListener;
        private OsmMap map;

        @SuppressLint("ClickableViewAccessibility")
        OsmMapView(Context context) {
            mapView = new MapView(context);
            mapView.setTileSource(LIGHT);
            mapView.setMultiTouchControls(true);
            mapView.setTilesScaledToDpi(true);
            mapView.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
            mapView.setMinZoomLevel(2d);
            mapView.setVerticalMapRepetitionEnabled(false);
            mapView.setScrollableAreaLimitLatitude(MapView.getTileSystem().getMaxLatitude(), MapView.getTileSystem().getMinLatitude(), 0);
            final CopyrightOverlay copyright = new CopyrightOverlay(context);
            copyright.setAlignBottom(true);
            copyright.setAlignRight(true);
            copyright.setTextSize(9);
            mapView.getOverlays().add(copyright);
            container = new FrameLayout(context) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (map != null) {
                        map.onTouch(ev);
                    }
                    if (dispatchInterceptor != null) {
                        return dispatchInterceptor.onInterceptTouchEvent(ev, super::dispatchTouchEvent);
                    }
                    return super.dispatchTouchEvent(ev);
                }

                @Override
                public boolean onInterceptTouchEvent(MotionEvent ev) {
                    if (interceptInterceptor != null) {
                        return interceptInterceptor.onInterceptTouchEvent(ev, super::onInterceptTouchEvent);
                    }
                    return super.onInterceptTouchEvent(ev);
                }

                @Override
                protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                    super.onLayout(changed, left, top, right, bottom);
                    if (map != null) {
                        map.onLayout();
                    }
                    if (onLayoutListener != null) {
                        onLayoutListener.run();
                    }
                }
            };
            container.addView(mapView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        @Override
        public View getView() {
            return container;
        }

        @Override
        public void getMapAsync(Consumer<IMap> callback) {
            if (map == null) {
                map = new OsmMap(mapView);
            }
            final OsmMap m = map;
            AndroidUtilities.runOnUIThread(() -> callback.accept(m));
        }

        @Override
        public void onResume() {
            mapView.onResume();
            if (map != null) {
                map.resumeLocation();
            }
        }

        @Override
        public void onPause() {
            mapView.onPause();
            if (map != null) {
                map.pauseLocation();
            }
        }

        @Override
        public void onCreate(Bundle savedInstance) {
        }

        @Override
        public void onDestroy() {
            if (map != null) {
                map.pauseLocation();
            }
            mapView.onDetach();
        }

        @Override
        public void onLowMemory() {
        }

        @Override
        public void setOnDispatchTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            dispatchInterceptor = touchInterceptor;
        }

        @Override
        public void setOnInterceptTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            interceptInterceptor = touchInterceptor;
        }

        @Override
        public void setOnLayoutListener(Runnable callback) {
            onLayoutListener = callback;
        }
    }

    // endregion

    // region The map

    private static final class OsmMap implements IMap {
        private final MapView mapView;
        private int padLeft, padTop, padRight, padBottom;
        private boolean night;
        private int mapType = MAP_TYPE_NORMAL;
        private Runnable idleListener, moveListener;
        private OnCameraMoveStartedListener moveStartedListener;
        private OnMarkerClickListener markerClickListener;
        private Consumer<Location> myLocationListener;
        private MyLocationNewOverlay myLocation;
        private boolean myLocationWanted;
        private boolean touching, gestureReported;
        private final ArrayList<Runnable> afterLayout = new ArrayList<>();
        private final Runnable idle = () -> {
            if (idleListener != null && !touching) {
                idleListener.run();
            }
        };

        OsmMap(MapView mapView) {
            this.mapView = mapView;
            mapView.addMapListener(new MapListener() {
                @Override
                public boolean onScroll(ScrollEvent event) {
                    moved();
                    return false;
                }

                @Override
                public boolean onZoom(ZoomEvent event) {
                    moved();
                    return false;
                }
            });
        }

        private void moved() {
            if (moveListener != null) {
                moveListener.run();
            }
            AndroidUtilities.cancelRunOnUIThread(idle);
            AndroidUtilities.runOnUIThread(idle, 250);
        }

        void onTouch(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touching = true;
                    gestureReported = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!gestureReported && moveStartedListener != null) {
                        gestureReported = true;
                        moveStartedListener.onCameraMoveStarted(OnCameraMoveStartedListener.REASON_GESTURE);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    AndroidUtilities.cancelRunOnUIThread(idle);
                    AndroidUtilities.runOnUIThread(idle, 250);
                    break;
            }
        }

        void onLayout() {
            if (mapView.getWidth() > 0 && !afterLayout.isEmpty()) {
                final ArrayList<Runnable> run = new ArrayList<>(afterLayout);
                afterLayout.clear();
                for (Runnable r : run) {
                    r.run();
                }
            }
        }

        private void whenLaidOut(Runnable r) {
            if (mapView.getWidth() > 0 && mapView.getHeight() > 0) {
                r.run();
            } else {
                afterLayout.add(r);
            }
        }

        private void applyTiles() {
            mapView.setTileSource(mapType == MAP_TYPE_NORMAL ? (night ? DARK : LIGHT) : SATELLITE);
        }

        @Override
        public void setMapType(int mapType) {
            this.mapType = mapType;
            applyTiles();
        }

        /** The map's centre for a point shown in the middle of the padded area (Telegram's sheets cover the map). */
        private GeoPoint centerFor(LatLng target, double zoom) {
            final GeoPoint point = new GeoPoint(target.latitude, target.longitude);
            final int dx = (padRight - padLeft) / 2, dy = (padBottom - padTop) / 2;
            if (dx == 0 && dy == 0) {
                return point;
            }
            final double size = MapView.getTileSystem().MapSize(zoom);
            final double px = MapView.getTileSystem().getX01FromLongitude(point.getLongitude(), true) * size + dx;
            final double py = MapView.getTileSystem().getY01FromLatitude(point.getLatitude(), true) * size + dy;
            return new GeoPoint(MapView.getTileSystem().getLatitudeFromY01(py / size, true), MapView.getTileSystem().getLongitudeFromX01(px / size, true));
        }

        private void apply(ICameraUpdate update, boolean animated, long duration, ICancelableCallback callback) {
            final OsmCameraUpdate u = (OsmCameraUpdate) update;
            if (animated && moveStartedListener != null) {
                moveStartedListener.onCameraMoveStarted(OnCameraMoveStartedListener.REASON_API_ANIMATION);
            }
            whenLaidOut(() -> {
                try {
                    if (u.box != null) {
                        mapView.zoomToBoundingBox(u.box, animated, u.padding + Math.max(Math.max(padLeft, padRight), Math.max(padTop, padBottom)) / 2);
                    } else {
                        final double zoom = u.zoom >= 0 ? u.zoom : mapView.getZoomLevelDouble();
                        final GeoPoint center = centerFor(u.target, zoom);
                        if (animated) {
                            mapView.getController().animateTo(center, zoom, duration);
                        } else {
                            mapView.getController().setZoom(zoom);
                            mapView.getController().setCenter(center);
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(callback::onFinish, animated ? duration + 50 : 0);
                }
            });
        }

        @Override
        public void animateCamera(ICameraUpdate update) {
            apply(update, true, 500, null);
        }

        @Override
        public void animateCamera(ICameraUpdate update, ICancelableCallback callback) {
            apply(update, true, 500, callback);
        }

        @Override
        public void animateCamera(ICameraUpdate update, int duration, ICancelableCallback callback) {
            apply(update, true, Math.max(1, duration), callback);
        }

        @Override
        public void moveCamera(ICameraUpdate update) {
            apply(update, false, 0, null);
        }

        @Override
        public float getMaxZoomLevel() {
            return (float) mapView.getMaxZoomLevel();
        }

        @Override
        public float getMinZoomLevel() {
            return (float) mapView.getMinZoomLevel();
        }

        @Override
        public void setMyLocationEnabled(boolean enabled) {
            myLocationWanted = enabled;
            if (enabled) {
                if (myLocation == null) {
                    final Drawable dot = ContextCompat.getDrawable(mapView.getContext(), R.drawable.sg_osm_my_location);
                    myLocation = new MyLocationNewOverlay(new GpsMyLocationProvider(mapView.getContext()), mapView) {
                        @Override
                        public void onLocationChanged(Location location, IMyLocationProvider source) {
                            super.onLocationChanged(location, source);
                            final Consumer<Location> listener = myLocationListener;
                            if (listener != null && location != null) {
                                AndroidUtilities.runOnUIThread(() -> listener.accept(location));
                            }
                        }
                    };
                    if (dot instanceof BitmapDrawable) {
                        myLocation.setPersonIcon(((BitmapDrawable) dot).getBitmap());
                    } else if (dot != null) {
                        final Bitmap bitmap = Bitmap.createBitmap(AndroidUtilities.dp(22), AndroidUtilities.dp(22), Bitmap.Config.ARGB_8888);
                        dot.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                        dot.draw(new android.graphics.Canvas(bitmap));
                        myLocation.setPersonIcon(bitmap);
                        myLocation.setDirectionArrow(bitmap, bitmap);
                    }
                    myLocation.setPersonAnchor(0.5f, 0.5f);
                    myLocation.setDirectionAnchor(0.5f, 0.5f);
                    myLocation.setDrawAccuracyEnabled(true);
                    mapView.getOverlays().add(myLocation);
                }
                resumeLocation();
            } else if (myLocation != null) {
                myLocation.disableMyLocation();
            }
            mapView.invalidate();
        }

        void resumeLocation() {
            if (myLocation != null && myLocationWanted) {
                try {
                    myLocation.enableMyLocation();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }

        void pauseLocation() {
            if (myLocation != null) {
                myLocation.disableMyLocation();
            }
        }

        @Override
        public IUISettings getUiSettings() {
            return new IUISettings() {
                @Override
                public void setZoomControlsEnabled(boolean enabled) {
                }

                @Override
                public void setMyLocationButtonEnabled(boolean enabled) {
                }

                @Override
                public void setCompassEnabled(boolean enabled) {
                }
            };
        }

        @Override
        public void setOnCameraIdleListener(Runnable callback) {
            idleListener = callback;
        }

        @Override
        public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener onCameraMoveStartedListener) {
            moveStartedListener = onCameraMoveStartedListener;
        }

        @Override
        public CameraPosition getCameraPosition() {
            final double zoom = mapView.getZoomLevelDouble();
            IGeoPoint c = mapView.getMapCenter();
            final int dx = (padRight - padLeft) / 2, dy = (padBottom - padTop) / 2;
            if ((dx != 0 || dy != 0) && mapView.getWidth() > 0) {
                c = mapView.getProjection().fromPixels(mapView.getWidth() / 2 - dx, mapView.getHeight() / 2 - dy);
            }
            return new CameraPosition(new LatLng(c.getLatitude(), c.getLongitude()), (float) zoom);
        }

        @Override
        public void setOnMapLoadedCallback(Runnable callback) {
            whenLaidOut(() -> AndroidUtilities.runOnUIThread(callback, 300));
        }

        @Override
        public IProjection getProjection() {
            return latLng -> {
                final Point out = new Point();
                mapView.getProjection().toPixels(new GeoPoint(latLng.latitude, latLng.longitude), out);
                return out;
            };
        }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {
            padLeft = left;
            padTop = top;
            padRight = right;
            padBottom = bottom;
        }

        @Override
        public void setMapStyle(IMapStyleOptions style) {
            night = style instanceof OsmStyle && ((OsmStyle) style).night;
            applyTiles();
        }

        @Override
        public IMarker addMarker(IMarkerOptions markerOptions) {
            final OsmMarkerOptions o = (OsmMarkerOptions) markerOptions;
            final Marker marker = new Marker(mapView);
            marker.setInfoWindow(null);
            if (o.position != null) {
                marker.setPosition(new GeoPoint(o.position.latitude, o.position.longitude));
            }
            final OsmMarker wrapper = new OsmMarker(marker, mapView);
            if (o.bitmap != null) {
                wrapper.setIcon(o.bitmap);
            } else if (o.resId != 0) {
                wrapper.setIcon(o.resId);
            }
            marker.setAnchor(o.anchorU, o.anchorV);
            marker.setFlat(o.flat);
            marker.setTitle(o.title);
            marker.setSnippet(o.snippet);
            marker.setOnMarkerClickListener((m, view) -> markerClickListener != null && markerClickListener.onClick(wrapper));
            // markers stay under the copyright line and the own location
            final int at = Math.max(0, mapView.getOverlays().size() - (myLocation != null ? 2 : 1));
            mapView.getOverlays().add(at, marker);
            mapView.invalidate();
            return wrapper;
        }

        @Override
        public void setOnMyLocationChangeListener(Consumer<Location> callback) {
            myLocationListener = callback;
        }

        @Override
        public void setOnMarkerClickListener(OnMarkerClickListener markerClickListener) {
            this.markerClickListener = markerClickListener;
        }

        @Override
        public void setOnCameraMoveListener(Runnable callback) {
            moveListener = callback;
        }

        @Override
        public ICircle addCircle(ICircleOptions circleOptions) {
            final OsmCircleOptions o = (OsmCircleOptions) circleOptions;
            final OsmCircle circle = new OsmCircle(mapView, o);
            mapView.getOverlays().add(0, circle.polygon);
            mapView.invalidate();
            return circle;
        }
    }

    private static final class OsmMarker implements IMarker {
        private final Marker marker;
        private final MapView mapView;
        private Object tag;

        OsmMarker(Marker marker, MapView mapView) {
            this.marker = marker;
            this.mapView = mapView;
        }

        @Override
        public Object getTag() {
            return tag;
        }

        @Override
        public void setTag(Object tag) {
            this.tag = tag;
        }

        @Override
        public LatLng getPosition() {
            final GeoPoint p = marker.getPosition();
            return new LatLng(p.getLatitude(), p.getLongitude());
        }

        @Override
        public void setPosition(LatLng latLng) {
            marker.setPosition(new GeoPoint(latLng.latitude, latLng.longitude));
            mapView.invalidate();
        }

        @Override
        public void setRotation(int rotation) {
            marker.setRotation(-rotation);
            mapView.invalidate();
        }

        @Override
        public void setIcon(Bitmap bitmap) {
            marker.setIcon(new BitmapDrawable(mapView.getResources(), bitmap));
            mapView.invalidate();
        }

        @Override
        public void setIcon(int resId) {
            final Drawable d = ContextCompat.getDrawable(mapView.getContext(), resId);
            if (d != null) {
                marker.setIcon(d);
            }
            mapView.invalidate();
        }

        @Override
        public void remove() {
            mapView.getOverlays().remove(marker);
            mapView.invalidate();
        }
    }

    private static final class OsmCircle implements ICircle {
        final Polygon polygon;
        private final MapView mapView;
        private GeoPoint center;
        private double radius;

        OsmCircle(MapView mapView, OsmCircleOptions o) {
            this.mapView = mapView;
            polygon = new Polygon(mapView);
            polygon.setInfoWindow(null);
            final Paint outline = polygon.getOutlinePaint();
            outline.setColor(o.strokeColor);
            outline.setStrokeWidth(o.strokeWidth);
            outline.setAntiAlias(true);
            if (o.pattern != null && !o.pattern.isEmpty()) {
                final float[] intervals = new float[o.pattern.size() + o.pattern.size() % 2];
                for (int i = 0; i < o.pattern.size(); i++) {
                    final PatternItem item = o.pattern.get(i);
                    intervals[i] = item instanceof PatternItem.Dash ? ((PatternItem.Dash) item).length
                        : item instanceof PatternItem.Gap ? ((PatternItem.Gap) item).length : 1;
                }
                if (intervals.length > o.pattern.size()) {
                    intervals[intervals.length - 1] = intervals[intervals.length - 2];
                }
                outline.setPathEffect(new DashPathEffect(intervals, 0));
            }
            polygon.getFillPaint().setColor(o.fillColor);
            center = o.center == null ? new GeoPoint(0d, 0d) : new GeoPoint(o.center.latitude, o.center.longitude);
            radius = o.radius;
            update();
        }

        private void update() {
            polygon.setPoints(Polygon.pointsAsCircle(center, Math.max(1, radius)));
            mapView.invalidate();
        }

        @Override
        public void setStrokeColor(int color) {
            polygon.getOutlinePaint().setColor(color);
            mapView.invalidate();
        }

        @Override
        public void setFillColor(int color) {
            polygon.getFillPaint().setColor(color);
            mapView.invalidate();
        }

        @Override
        public void setRadius(double radius) {
            this.radius = radius;
            update();
        }

        @Override
        public double getRadius() {
            return radius;
        }

        @Override
        public void setCenter(LatLng latLng) {
            center = new GeoPoint(latLng.latitude, latLng.longitude);
            update();
        }

        @Override
        public void remove() {
            mapView.getOverlays().remove(polygon);
            mapView.invalidate();
        }
    }

    // endregion
}
